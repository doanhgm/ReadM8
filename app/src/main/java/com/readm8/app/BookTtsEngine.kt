package com.readm8.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class VoiceOption(val label: String, val shortLabel: String, val edgeName: String, val isOnline: Boolean) {
    HOAI_MY("AI Nữ – Hoài My (Online)", "Hoài My", "vi-VN-HoaiMyNeural", true),
    NAM_MINH("AI Nam – Nam Minh (Online)", "Nam Minh", "vi-VN-NamMinhNeural", true),
    OFFLINE("Giọng trên máy (Offline)", "Offline", "", false)
}

data class TtsSegment(
    val domIndex: Int,
    val text: String
)

class BookTtsEngine(
    private val context: Context,
    private val onSegmentActive: (domIndex: Int, segIndex: Int, totalSegments: Int) -> Unit,
    private val onStateChanged: (isPlaying: Boolean, status: String) -> Unit,
    private val onChapterFinished: () -> Unit,
    private val onFallbackNotice: (msg: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fetchExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val ttsCacheDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
    private val sessionCounter = AtomicInteger(0)
    private val playerLock = Any()
    private val prefetchMap = ConcurrentHashMap<Int, Future<File?>>()

    private var mediaPlayer: MediaPlayer? = null
    private var offlineTts: TextToSpeech? = null
    private var offlineReady = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var segments: List<TtsSegment> = emptyList()
    private var currentSegIndex = 0
    private var fallbackNoticeShown = false

    @Volatile
    var isActive = false
        private set

    @Volatile
    var isPlaying = false
        private set

    var voice: VoiceOption = VoiceOption.HOAI_MY
    var speed: Float = 1.0f

    private var sleepRunnable: Runnable? = null
    var sleepMinutesLeft: Int = 0
        private set

    init {
        initOfflineTts()
    }

    private fun initOfflineTts() {
        offlineTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                offlineReady = true
                offlineTts?.language = Locale("vi", "VN")
                offlineTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        val parts = utteranceId?.split("_") ?: return
                        val sess = parts.getOrNull(0)?.toIntOrNull() ?: return
                        val idx = parts.getOrNull(1)?.toIntOrNull() ?: return
                        mainHandler.post {
                            if (sess == sessionCounter.get() && isPlaying) {
                                playSegmentInternal(sess, idx + 1)
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        val parts = utteranceId?.split("_") ?: return
                        val sess = parts.getOrNull(0)?.toIntOrNull() ?: return
                        val idx = parts.getOrNull(1)?.toIntOrNull() ?: return
                        mainHandler.post {
                            if (sess == sessionCounter.get() && isPlaying) {
                                playSegmentInternal(sess, idx + 1)
                            }
                        }
                    }
                })
            }
        }
    }

    fun start(paragraphs: List<String>, startDomIndex: Int) {
        stopInternal(clearState = false)
        segments = buildSegments(paragraphs)
        if (segments.isEmpty()) {
            onStateChanged(false, "Không có chữ để đọc")
            return
        }

        val startSeg = segments.indexOfFirst { it.domIndex >= startDomIndex }.let {
            if (it >= 0) it else 0
        }
        currentSegIndex = startSeg
        isActive = true
        isPlaying = true
        fallbackNoticeShown = false
        acquireWakeLock()

        val session = sessionCounter.incrementAndGet()
        cleanCacheFiles()
        onStateChanged(true, "Đang tải giọng ${voice.shortLabel}...")
        playSegmentInternal(session, currentSegIndex)
    }

    fun togglePlayPause() {
        if (!isActive || segments.isEmpty()) return
        if (isPlaying) {
            isPlaying = false
            synchronized(playerLock) {
                runCatching {
                    if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause()
                }
            }
            runCatching { offlineTts?.stop() }
            releaseWakeLock()
            onStateChanged(false, "Đã tạm dừng")
        } else {
            isPlaying = true
            acquireWakeLock()
            var resumedMp = false
            synchronized(playerLock) {
                val mp = mediaPlayer
                if (mp != null && voice.isOnline) {
                    resumedMp = runCatching {
                        mp.start()
                        true
                    }.getOrDefault(false)
                }
            }
            if (resumedMp) {
                onStateChanged(true, "Đang đọc (${voice.shortLabel})")
            } else {
                val session = sessionCounter.incrementAndGet()
                playSegmentInternal(session, currentSegIndex)
            }
        }
    }

    fun nextSegment() {
        if (!isActive || segments.isEmpty()) return
        val next = (currentSegIndex + 1).coerceAtMost(segments.lastIndex)
        jumpToSegment(next)
    }

    fun prevSegment() {
        if (!isActive || segments.isEmpty()) return
        val prev = (currentSegIndex - 1).coerceAtLeast(0)
        jumpToSegment(prev)
    }

    fun updateVoiceOrSpeed(newVoice: VoiceOption, newSpeed: Float) {
        voice = newVoice
        speed = newSpeed
        fallbackNoticeShown = false
        if (isActive && isPlaying) {
            jumpToSegment(currentSegIndex)
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepMinutesLeft = minutes
        if (minutes <= 0) return

        val r = Runnable {
            sleepMinutesLeft = 0
            if (isPlaying) togglePlayPause()
            onFallbackNotice("Đã hết giờ hẹn ngủ, tạm dừng đọc sách.")
        }
        sleepRunnable = r
        mainHandler.postDelayed(r, minutes * 60_000L)
    }

    fun stop() {
        stopInternal(clearState = true)
    }

    fun release() {
        stopInternal(clearState = true)
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        fetchExecutor.shutdownNow()
        runCatching { offlineTts?.shutdown() }
        offlineTts = null
        cleanCacheFiles()
    }

    private fun jumpToSegment(idx: Int) {
        currentSegIndex = idx
        stopMediaOnly()
        prefetchMap.values.forEach { it.cancel(true) }
        prefetchMap.clear()
        isPlaying = true
        acquireWakeLock()
        val session = sessionCounter.incrementAndGet()
        playSegmentInternal(session, idx)
    }

    private fun stopInternal(clearState: Boolean) {
        sessionCounter.incrementAndGet()
        isPlaying = false
        if (clearState) isActive = false
        stopMediaOnly()
        prefetchMap.values.forEach { it.cancel(true) }
        prefetchMap.clear()
        releaseWakeLock()
        if (clearState) onStateChanged(false, "")
    }

    private fun stopMediaOnly() {
        synchronized(playerLock) {
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
        runCatching { offlineTts?.stop() }
    }

    private fun playSegmentInternal(session: Int, index: Int) {
        if (session != sessionCounter.get() || !isPlaying) return
        if (index >= segments.size) {
            isPlaying = false
            releaseWakeLock()
            onChapterFinished()
            return
        }

        currentSegIndex = index
        val seg = segments[index]
        onSegmentActive(seg.domIndex, index, segments.size)

        if (!voice.isOnline) {
            playOffline(session, index, seg.text)
            return
        }

        val currentFuture = ensurePrefetch(session, index)
        if (index + 1 < segments.size) ensurePrefetch(session, index + 1)
        if (index + 2 < segments.size) ensurePrefetch(session, index + 2)

        fetchExecutor.execute {
            val file = runCatching { currentFuture.get(18, TimeUnit.SECONDS) }.getOrNull()
            mainHandler.post {
                if (session != sessionCounter.get() || !isPlaying) return@post
                if (file != null && file.exists() && file.length() > 100) {
                    playMp3File(session, index, file)
                } else {
                    if (!fallbackNoticeShown) {
                        fallbackNoticeShown = true
                        onFallbackNotice("Giọng AI tạm gián đoạn, chuyển sang giọng Offline")
                    }
                    playOffline(session, index, seg.text)
                }
            }
        }
    }

    private fun ensurePrefetch(session: Int, index: Int): Future<File?> {
        prefetchMap[index]?.let { return it }
        val future = fetchExecutor.submit(Callable {
            if (session != sessionCounter.get()) return@Callable null
            val seg = segments.getOrNull(index) ?: return@Callable null
            synthesizeEdgeTts(session, index, seg.text, voice.edgeName, speed)
        })
        val existing = prefetchMap.putIfAbsent(index, future)
        if (existing != null) {
            future.cancel(true)
            return existing
        }
        return future
    }

    private fun playMp3File(session: Int, index: Int, file: File) {
        stopMediaOnly()
        onStateChanged(true, "Đang đọc (${voice.shortLabel}) • ${index + 1}/${segments.size}")
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    runCatching { file.delete() }
                    prefetchMap.remove(index)
                    mainHandler.post {
                        if (session == sessionCounter.get() && isPlaying) {
                            playSegmentInternal(session, index + 1)
                        }
                    }
                }
                setOnErrorListener { _, _, _ ->
                    runCatching { file.delete() }
                    prefetchMap.remove(index)
                    mainHandler.post {
                        if (session == sessionCounter.get() && isPlaying) {
                            playSegmentInternal(session, index + 1)
                        }
                    }
                    true
                }
                prepare()
                start()
            }
            synchronized(playerLock) {
                mediaPlayer = mp
            }
        } catch (_: Exception) {
            playOffline(session, index, segments[index].text)
        }
    }

    private fun playOffline(session: Int, index: Int, text: String) {
        stopMediaOnly()
        val tts = offlineTts
        if (!offlineReady || tts == null) {
            onStateChanged(false, "Máy chưa có bộ giọng đọc Offline")
            isPlaying = false
            return
        }
        tts.setSpeechRate(speed)
        onStateChanged(true, "Đang đọc (Offline) • ${index + 1}/${segments.size}")
        val uid = "${session}_${index}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
    }

    // ==================== MICROSOFT EDGE NEURAL TTS ====================
    private fun synthesizeEdgeTts(
        session: Int,
        index: Int,
        text: String,
        voiceName: String,
        rateMultiplier: Float
    ): File? {
        if (session != sessionCounter.get()) return null
        val cleanText = escapeXml(text)
        if (cleanText.isBlank()) return null

        val trustedToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val gec = generateSecMsGec(trustedToken)
        val connId = UUID.randomUUID().toString().replace("-", "")
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$trustedToken" +
            "&Sec-MS-GEC=$gec" +
            "&Sec-MS-GEC-Version=1-130.0.2849.68" +
            "&ConnectionId=$connId"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
            )
            .build()

        val audioOut = ByteArrayOutputStream(32768)
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        val pct = ((rateMultiplier - 1.0f) * 100f).toInt().coerceIn(-50, 100)
        val rateStr = if (pct >= 0) "+$pct%" else "$pct%"
        val ts = utcTimestamp()
        val reqId = UUID.randomUUID().toString().replace("-", "")

        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val configMsg = "X-Timestamp:$ts\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(configMsg)

                val ssmlMsg = "X-RequestId:$reqId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${ts}Z\r\n" +
                    "Path:ssml\r\n\r\n" +
                    "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='vi-VN'>" +
                    "<voice name='$voiceName'><prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>$cleanText</prosody></voice>" +
                    "</speak>"
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                if (arr.size < 2) return
                val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                val audioStart = 2 + headerLen
                if (audioStart < arr.size) {
                    val header = String(arr, 2, headerLen, Charsets.UTF_8)
                    if (header.contains("Path:audio")) {
                        audioOut.write(arr, audioStart, arr.size - audioStart)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    completed.set(true)
                    webSocket.close(1000, "done")
                    latch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })

        val finishedInTime = latch.await(15, TimeUnit.SECONDS)
        if (!finishedInTime) ws.cancel()

        if (!completed.get() || audioOut.size() < 100 || session != sessionCounter.get()) {
            return null
        }

        val outFile = File(ttsCacheDir, "tts_${session}_${index}.mp3")
        return runCatching {
            outFile.writeBytes(audioOut.toByteArray())
            outFile
        }.getOrNull()
    }

    private fun generateSecMsGec(token: String): String {
        val winEpoch = 11644473600L
        var ticks = (System.currentTimeMillis() / 1000L) + winEpoch
        ticks -= (ticks % 300L)
        val ticks100ns = ticks * 10_000_000L
        val raw = "$ticks100ns$token"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun utcTimestamp(): String {
        val sdf = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '&' -> sb.append("&")
                '<' -> sb.append("<")
                '>' -> sb.append(">")
                '"' -> sb.append(""")
                '\'' -> sb.append("'")
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') {
                    sb.append(ch)
                }
            }
        }
        return sb.toString().trim()
    }

    private fun buildSegments(paragraphs: List<String>): List<TtsSegment> {
        val out = mutableListOf<TtsSegment>()
        paragraphs.forEachIndexed { domIdx, raw ->
            val cleaned = raw.replace(Regex("\\s+"), " ").trim()
            if (cleaned.length < 2) return@forEachIndexed
            if (cleaned.length <= 300) {
                out.add(TtsSegment(domIdx, cleaned))
            } else {
                var start = 0
                while (start < cleaned.length) {
                    val maxEnd = (start + 280).coerceAtMost(cleaned.length)
                    if (maxEnd >= cleaned.length) {
                        out.add(TtsSegment(domIdx, cleaned.substring(start).trim()))
                        break
                    }
                    val slice = cleaned.substring(start, maxEnd)
                    val cutSentence = maxOf(
                        slice.lastIndexOf(". "),
                        slice.lastIndexOf("! "),
                        slice.lastIndexOf("? "),
                        slice.lastIndexOf("; ")
                    )
                    val cutComma = slice.lastIndexOf(", ")
                    val cutSpace = slice.lastIndexOf(' ')
                    val cut = when {
                        cutSentence > 80 -> start + cutSentence + 1
                        cutComma > 120 -> start + cutComma + 1
                        cutSpace > 60 -> start + cutSpace
                        else -> maxEnd
                    }
                    val part = cleaned.substring(start, cut).trim()
                    if (part.isNotEmpty()) out.add(TtsSegment(domIdx, part))
                    start = cut
                    while (start < cleaned.length && cleaned[start] == ' ') start++
                }
            }
        }
        return out
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadM8:TtsWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun cleanCacheFiles() {
        runCatching {
            ttsCacheDir.listFiles()?.forEach { it.delete() }
        }
    }
}
