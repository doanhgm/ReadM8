package com.readm8.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

enum class VoiceOption(
    val label: String,
    val shortLabel: String,
    val edgeName: String,
    val isOnline: Boolean
) {
    HOAI_MY("AI Nữ – Hoài My (Online)", "Hoài My", "vi-VN-HoaiMyNeural", true),
    NAM_MINH("AI Nam – Nam Minh (Online)", "Nam Minh", "vi-VN-NamMinhNeural", true),
    OFFLINE("Giọng trên máy (Offline)", "Offline", "", false)
}

data class TtsSegment(
    val domIndex: Int,
    val text: String
)

private object EdgeTtsDrm {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_FULL_VERSION = "155.0.4283.45"
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0"
    private const val WIN_EPOCH = 11644473600L

    private val SHA256_INITIAL = intArrayOf(
        0x6A09E667,
        0xBB67AE85.toInt(),
        0x3C6EF372,
        0xA54FF53A.toInt(),
        0x510E527F,
        0x9B05688C.toInt(),
        0x1F83D9AB,
        0x5BE0CD19
    )

    private val SHA256_STATE = intArrayOf(
        0xFD9FBEA2.toInt(),
        0x91243CE7.toInt(),
        0xBE499094.toInt(),
        0xCD2E1E0A.toInt(),
        0x6C006F49,
        0x04BD35AB,
        0xFEB0A7E4.toInt(),
        0x03EFFBD2
    )

    private val SHA256_SCHEDULE = intArrayOf(
        0xEB24BF9E.toInt(),
        0x1558691F,
        0xA0C09EE2.toInt(),
        0x0CFA32BA,
        0xA622EE8C.toInt(),
        0xD1DD9491.toInt(),
        0xC0DD25A0.toInt(),
        0xF9E70AB6.toInt(),
        0xF0C31573.toInt(),
        0x7058CE4E,
        0x7188E606,
        0x00E5AB33,
        0xD258B060.toInt(),
        0x72766564
    )

    private val SHA256_ROUND_CONSTANTS = intArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt()
    )

    private val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()
    private val SHA256_BLOCK_CONSTANTS = SHA256_ROUND_CONSTANTS.copyOf().apply {
        this[14] = SHA256_ADJUSTMENT
    }
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0

    private val CS64_COEFF_A = intArrayOf(
        0xE82E91B1.toInt(),
        0x44DE24C6,
        0x3BEB65BC,
        0xC89C5933.toInt(),
        0x8D1EC211.toInt(),
        0x314EF5D5,
        0xE92491B1.toInt(),
        0xF0A5B9D5.toInt(),
        0x06064CF3,
        0xDA79FA60.toInt(),
        0xBED20560.toInt(),
        0x89F9C9F8.toInt(),
        0x35FA38C6,
        0xCB49E3C6.toInt()
    )

    private val CS64_COEFF_B = intArrayOf(
        0xB32700D0.toInt(),
        0x88DFF51D.toInt(),
        0x29514038,
        0xB328CBDF.toInt(),
        0xD405B4D5.toInt(),
        0x26E55F3C,
        0x607801C6,
        0x82B2C1FB.toInt(),
        0xFA51A3DA.toInt(),
        0x207CC464,
        0x3C3C74F3,
        0xD2D14B61.toInt(),
        0xBB04A2C1.toInt(),
        0xC5626167.toInt()
    )

    private val CS64_FACTOR = 0x82893933.toInt()
    private val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000

    @Volatile
    private var clockSkewSeconds: Long = 0L

    @Volatile
    private var cachedTicks: Long = -1L

    @Volatile
    private var cachedToken: String = ""

    fun adjustClockSkewIfNeeded(serverDateHeader: String): Boolean {
        val serverSec = parseRfc2616Date(serverDateHeader) ?: return false
        val clientSec = (System.currentTimeMillis() / 1000L) + clockSkewSeconds
        val diff = serverSec - clientSec
        if (abs(diff) < 5L) return false
        clockSkewSeconds += diff
        cachedTicks = -1L
        return true
    }

    private fun parseRfc2616Date(dateStr: String): Long? {
        return runCatching {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val parsed = sdf.parse(dateStr.trim()) ?: return null
            parsed.time / 1000L
        }.getOrNull()
    }

    fun generateSecMsGecV2(): String {
        val unixSec = (System.currentTimeMillis() / 1000L) + clockSkewSeconds
        var ticks = unixSec + WIN_EPOCH
        ticks -= ((ticks % 300L) + 300L) % 300L
        val ticks100ns = ticks * 10_000_000L

        if (ticks100ns == cachedTicks && cachedToken.isNotEmpty()) {
            return cachedToken
        }

        val data = "$ticks100ns$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val sha256Bytes = sha256Digest(data)
        val cs64Bytes = cs64DigestBytes(data, sha256Bytes)
        val token = "${toUpperHex(cs64Bytes)}-${toUpperHex(sha256Bytes)}"
        cachedTicks = ticks100ns
        cachedToken = token
        return token
    }

    private fun rol16(value: Int): Int = Integer.rotateLeft(value, 16)

    private fun sigma0(value: Int): Int {
        return Integer.rotateRight(value, 7) xor
            Integer.rotateRight(value, 18) xor
            (value ushr 3)
    }

    private fun sigma1(value: Int): Int {
        return Integer.rotateRight(value, 17) xor
            Integer.rotateRight(value, 19) xor
            (value ushr 10)
    }

    private fun expandSha256Schedule(words: IntArray, firstWord: Int = 16) {
        for (index in firstWord until 64) {
            words[index] = sigma1(words[index - 2]) +
                words[index - 7] +
                sigma0(words[index - 15]) +
                words[index - 16]
        }
    }

    private fun sha256Rounds(state: IntArray, words: IntArray, firstRound: Int = 0): IntArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS

        for (index in firstRound until 64) {
            val s1 = Integer.rotateRight(e, 6) xor
                Integer.rotateRight(e, 11) xor
                Integer.rotateRight(e, 25)
            val ch = g xor (e and (f xor g))
            val t1 = h + s1 + ch + constants[index] + words[index]
            val s0 = Integer.rotateRight(a, 2) xor
                Integer.rotateRight(a, 13) xor
                Integer.rotateRight(a, 22)
            val maj = (a and b) or (c and (a or b))
            val t2 = s0 + maj

            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun sha256Init(data: ByteArray): IntArray {
        val words = IntArray(64)
        words[14] = ((data[0].toInt() and 0xFF) shl 16) or
            ((data[1].toInt() and 0xFF) shl 8) or
            (data[2].toInt() and 0xFF)
        words[15] = ((data[3].toInt() and 0xFF) shl 24) or
            ((data[4].toInt() and 0xFF) shl 16) or
            ((data[5].toInt() and 0xFF) shl 8) or
            (data[6].toInt() and 0xFF)

        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) {
                    sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                } else {
                    sigma1(words[index - 2])
                }
            }
            if (index - 7 >= 14) {
                value += words[index - 7]
            }
            if (index - 15 >= 14) {
                value += if (index == 29) {
                    sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                } else {
                    sigma0(words[index - 15])
                }
            }
            words[index] = value
        }

        words[30] = sigma1(words[28]) +
            words[23] +
            sigma0(words[15]) +
            words[14] +
            SHA256_ADJUSTMENT -
            SHA256_ROUND_CONSTANTS[14]

        expandSha256Schedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words, 14)
        return IntArray(8) { i -> SHA256_INITIAL[i] + working[i] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        val length = 57 + data.size
        val zeroPadLen = ((55 - length) % 64 + 64) % 64
        val bitLen = length.toLong() * 8L

        val out = ByteArrayOutputStream(data.size + 72)
        out.write(data, 7, data.size - 7)
        out.write(0x80)
        repeat(zeroPadLen) { out.write(0) }
        for (shift in 56 downTo 0 step 8) {
            out.write(((bitLen ushr shift) and 0xFFL).toInt())
        }
        val padded = out.toByteArray()

        var state = sha256Init(data)
        val words = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val p = offset + i * 4
                words[i] = ((padded[p].toInt() and 0xFF) shl 24) or
                    ((padded[p + 1].toInt() and 0xFF) shl 16) or
                    ((padded[p + 2].toInt() and 0xFF) shl 8) or
                    (padded[p + 3].toInt() and 0xFF)
            }
            expandSha256Schedule(words, 16)
            val working = sha256Rounds(state, words, 0)
            state = IntArray(8) { i -> state[i] + working[i] }
            offset += 64
        }

        val digest = ByteArray(32)
        for (i in 0 until 8) {
            val w = state[i]
            digest[i * 4] = ((w ushr 24) and 0xFF).toByte()
            digest[i * 4 + 1] = ((w ushr 16) and 0xFF).toByte()
            digest[i * 4 + 2] = ((w ushr 8) and 0xFF).toByte()
            digest[i * 4 + 3] = (w and 0xFF).toByte()
        }
        return digest
    }

    private fun wordsLe4Even(data: ByteArray): IntArray {
        if (data.isEmpty()) return IntArray(0)
        val wordCount = (data.size + 3) / 4
        val evenCount = if (wordCount % 2 == 1) wordCount + 1 else wordCount
        val words = IntArray(evenCount)
        for (i in 0 until wordCount) {
            val base = i * 4
            val b0 = if (base < data.size) (data[base].toInt() and 0xFF) else 0
            val b1 = if (base + 1 < data.size) (data[base + 1].toInt() and 0xFF) else 0
            val b2 = if (base + 2 < data.size) (data[base + 2].toInt() and 0xFF) else 0
            val b3 = if (base + 3 < data.size) (data[base + 3].toInt() and 0xFF) else 0
            words[i] = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
        return words
    }

    private fun cs64DigestBytes(data: ByteArray, digest: ByteArray): ByteArray {
        val keyA = ((digest[0].toInt() and 0xFF) or
            ((digest[1].toInt() and 0xFF) shl 8) or
            ((digest[2].toInt() and 0xFF) shl 16) or
            ((digest[3].toInt() and 0xFF) shl 24)) or 1
        val keyB = ((digest[4].toInt() and 0xFF) or
            ((digest[5].toInt() and 0xFF) shl 8) or
            ((digest[6].toInt() and 0xFF) shl 16) or
            ((digest[7].toInt() and 0xFF) shl 24)) or 1

        val factor0 = keyA * CS64_FACTOR
        val factor1 = keyB * CS64_FACTOR

        val prefixed = ByteArray(data.size + 1)
        prefixed[0] = 0
        System.arraycopy(data, 0, prefixed, 1, data.size)
        val dataWords = wordsLe4Even(prefixed)

        val baseLen = CS64_COEFF_A.size
        val terms = IntArray(baseLen + dataWords.size)
        val offsets = IntArray(baseLen + dataWords.size)

        for (i in 0 until baseLen) {
            terms[i] = CS64_COEFF_A[i] * if (i % 2 == 0) factor0 else factor1
            offsets[i] = CS64_COEFF_B[i]
        }

        for (i in dataWords.indices) {
            val word = dataWords[i]
            var term = word * if (i % 2 == 0) keyA else keyB
            var offset = rol16(word) * if (i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt()
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * factor0
                offset += CS64_ADJUSTMENT1
            }
            terms[baseLen + i] = term
            offsets[baseLen + i] = offset
        }

        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0

        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]

            var t = rol16(offsets[index] + x)
            var q = t * 0x76F50F34
            swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q

            t = rol16(offsets[index + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rol16(q) * 0x35BD1EC9 + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111.toInt()
            state = rol16(state) * 0x87085B9F.toInt()
            state = rol16(state) * 0x12CEB96D
            state = rol16(state) * 0x257E1D83
            acc += state

            t = state * keyB + y
            t = rol16(t) * 0xA27416F5.toInt()
            t = rol16(t) * 0xD38396FF.toInt()
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state

            index += 2
        }

        val w0 = swapLast xor state
        val w1 = swapAcc xor acc
        return byteArrayOf(
            (w0 and 0xFF).toByte(),
            ((w0 ushr 8) and 0xFF).toByte(),
            ((w0 ushr 16) and 0xFF).toByte(),
            ((w0 ushr 24) and 0xFF).toByte(),
            (w1 and 0xFF).toByte(),
            ((w1 ushr 8) and 0xFF).toByte(),
            ((w1 ushr 16) and 0xFF).toByte(),
            ((w1 ushr 24) and 0xFF).toByte()
        )
    }

    private fun toUpperHex(bytes: ByteArray): String {
        val chars = "0123456789ABCDEF"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(chars[v ushr 4])
            sb.append(chars[v and 0x0F])
        }
        return sb.toString()
    }
}

class BookTtsEngine(
    private val context: Context,
    private val onSegmentActive: (domIndex: Int, segIndex: Int, totalSegments: Int) -> Unit,
    private val onStateChanged: (isPlaying: Boolean, status: String) -> Unit,
    private val onChapterFinished: () -> Unit,
    private val onFallbackNotice: (msg: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val waitExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val tls12Spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(tls12Spec, ConnectionSpec.MODERN_TLS))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val ttsCacheDir = File(context.cacheDir, "tts_audio").apply { mkdirs() }
    private val sessionCounter = AtomicInteger(0)
    private val offlineConsecutiveErrors = AtomicInteger(0)
    private val activeWebSocket = AtomicReference<WebSocket?>(null)
    private val playerLock = Any()
    private val prefetchMap = ConcurrentHashMap<Int, Future<File?>>()

    private var mediaPlayer: MediaPlayer? = null
    private var offlineTts: TextToSpeech? = null
    private var offlineReady = false
    private var offlineLocalVoicePicked = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var segments: List<TtsSegment> = emptyList()
    private var currentSegIndex = 0
    private var fallbackNoticeShown = false

    @Volatile
    private var useNewEndpoint: Boolean = true

    @Volatile
    private var lastRequestAtMs: Long = 0L

    @Volatile
    private var onlineCooldownUntilMs: Long = 0L

    @Volatile
    private var wasOfflineDueToNoNet: Boolean = false

    @Volatile
    private var lastErrorReason: String = ""

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

    private fun hasActiveNetwork(): Boolean {
        return runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching true
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return@runCatching true
                    }
                }
            }
            false
        }.getOrDefault(true)
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

    private fun cancelPendingFetches() {
        activeWebSocket.getAndSet(null)?.cancel()
        prefetchMap.values.forEach { it.cancel(true) }
        prefetchMap.clear()
    }

    private fun buildSegments(paragraphs: List<String>): List<TtsSegment> {
        val out = mutableListOf<TtsSegment>()
        for ((domIdx, raw) in paragraphs.withIndex()) {
            val cleaned = raw.replace(Regex("\\s+"), " ").trim()
            if (cleaned.length < 2) continue
            if (cleaned.length <= 520) {
                out.add(TtsSegment(domIdx, cleaned))
                continue
            }
            var start = 0
            while (start < cleaned.length) {
                val maxEnd = (start + 500).coerceAtMost(cleaned.length)
                if (maxEnd >= cleaned.length) {
                    out.add(TtsSegment(domIdx, cleaned.substring(start).trim()))
                    break
                }
                val slice = cleaned.substring(start, maxEnd)
                val cutSentence = listOf(
                    slice.lastIndexOf(". "),
                    slice.lastIndexOf("! "),
                    slice.lastIndexOf("? "),
                    slice.lastIndexOf("; ")
                ).maxOrNull() ?: -1
                val cutComma = slice.lastIndexOf(", ")
                val cutSpace = slice.lastIndexOf(' ')
                val cut = when {
                    cutSentence > 150 -> start + cutSentence + 1
                    cutComma > 220 -> start + cutComma + 1
                    cutSpace > 100 -> start + cutSpace
                    else -> maxEnd
                }
                val part = cleaned.substring(start, cut).trim()
                if (part.isNotEmpty()) out.add(TtsSegment(domIdx, part))
                start = cut
                while (start < cleaned.length && cleaned[start] == ' ') start++
            }
        }
        return out
    }

    private fun initOfflineTts() {
        offlineTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                offlineReady = true
                offlineTts?.let { tts ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        tts.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                    }
                    selectBestOfflineVietnameseVoice(tts)
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            offlineConsecutiveErrors.set(0)
                        }

                        override fun onDone(utteranceId: String?) {
                            offlineConsecutiveErrors.set(0)
                            advanceFromUtterance(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            handleOfflineError(utteranceId)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            handleOfflineError(utteranceId)
                        }
                    })
                }
            }
        }
    }

    private fun selectBestOfflineVietnameseVoice(tts: TextToSpeech) {
        val viLocale = Locale("vi", "VN")
        runCatching { tts.setLanguage(viLocale) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                val localVi = tts.voices.orEmpty().firstOrNull { v ->
                    v.locale?.language.equals("vi", ignoreCase = true) && !v.isNetworkConnectionRequired
                }
                if (localVi != null) {
                    tts.voice = localVi
                    offlineLocalVoicePicked = true
                }
            }
        }
    }

    private fun advanceFromUtterance(utteranceId: String?) {
        val parts = utteranceId?.split("_") ?: return
        val sess = parts.getOrNull(0)?.toIntOrNull() ?: return
        val idx = parts.getOrNull(1)?.toIntOrNull() ?: return
        mainHandler.post {
            if (sess == sessionCounter.get() && isPlaying) {
                playSegmentInternal(sess, idx + 1)
            }
        }
    }

    private fun handleOfflineError(utteranceId: String?) {
        val parts = utteranceId?.split("_") ?: return
        val sess = parts.getOrNull(0)?.toIntOrNull() ?: return
        val idx = parts.getOrNull(1)?.toIntOrNull() ?: return
        mainHandler.post {
            if (sess != sessionCounter.get() || !isPlaying) return@post
            val errs = offlineConsecutiveErrors.incrementAndGet()
            if (errs >= 2) {
                isPlaying = false
                releaseWakeLock()
                onStateChanged(false, "Thiếu gói giọng Tiếng Việt Offline")
                onFallbackNotice("Chưa tải gói giọng Tiếng Việt Offline trong Cài đặt TTS của máy")
            } else {
                playSegmentInternal(sess, idx + 1)
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
        val found = segments.indexOfFirst { it.domIndex >= startDomIndex }
        currentSegIndex = if (found >= 0) found else 0
        isActive = true
        isPlaying = true
        fallbackNoticeShown = false
        wasOfflineDueToNoNet = false
        onlineCooldownUntilMs = 0L
        offlineConsecutiveErrors.set(0)
        lastErrorReason = ""
        acquireWakeLock()

        val session = sessionCounter.incrementAndGet()
        cleanCacheFiles()
        onStateChanged(true, "Đang chuẩn bị giọng ${voice.shortLabel}...")
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
            var resumed = false
            synchronized(playerLock) {
                val mp = mediaPlayer
                if (mp != null && voice.isOnline) {
                    resumed = runCatching {
                        mp.start()
                        true
                    }.getOrDefault(false)
                }
            }
            if (resumed) {
                onStateChanged(true, "Đang đọc (${voice.shortLabel})")
            } else {
                val session = sessionCounter.incrementAndGet()
                playSegmentInternal(session, currentSegIndex)
            }
        }
    }

    fun nextSegment() {
        if (!isActive || segments.isEmpty()) return
        jumpToSegment((currentSegIndex + 1).coerceAtMost(segments.lastIndex))
    }

    fun prevSegment() {
        if (!isActive || segments.isEmpty()) return
        jumpToSegment((currentSegIndex - 1).coerceAtLeast(0))
    }

    fun updateVoiceOrSpeed(newVoice: VoiceOption, newSpeed: Float) {
        voice = newVoice
        speed = newSpeed
        fallbackNoticeShown = false
        wasOfflineDueToNoNet = false
        onlineCooldownUntilMs = 0L
        offlineConsecutiveErrors.set(0)
        lastErrorReason = ""
        if (isActive && isPlaying) {
            jumpToSegment(currentSegIndex)
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepMinutesLeft = minutes
        if (minutes <= 0) return

        val task = Runnable {
            sleepMinutesLeft = 0
            if (isPlaying) togglePlayPause()
            onFallbackNotice("Đã hết giờ hẹn ngủ, tạm dừng đọc sách.")
        }
        sleepRunnable = task
        mainHandler.postDelayed(task, minutes * 60_000L)
    }

    fun stop() {
        stopInternal(clearState = true)
    }

    fun release() {
        stopInternal(clearState = true)
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        fetchExecutor.shutdownNow()
        waitExecutor.shutdownNow()
        runCatching { offlineTts?.shutdown() }
        offlineTts = null
        cleanCacheFiles()
    }

    private fun jumpToSegment(idx: Int) {
        currentSegIndex = idx
        stopMediaOnly()
        cancelPendingFetches()
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
        cancelPendingFetches()
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

        // Nếu đoạn này đã tải xong từ trước khi tắt Wi-Fi thì phát luôn file MP3 có sẵn
        val existingFuture = prefetchMap[index]
        if (existingFuture != null && existingFuture.isDone && !existingFuture.isCancelled) {
            val cachedFile = runCatching { existingFuture.get() }.getOrNull()
            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 100) {
                playMp3File(session, index, cachedFile)
                if (hasActiveNetwork() && System.currentTimeMillis() >= onlineCooldownUntilMs && index + 1 < segments.size) {
                    ensurePrefetch(session, index + 1)
                }
                return
            }
        }

        val now = System.currentTimeMillis()
        val netUp = hasActiveNetwork()
        if (!netUp || now < onlineCooldownUntilMs) {
            cancelPendingFetches()
            if (!netUp) wasOfflineDueToNoNet = true
            if (!fallbackNoticeShown) {
                fallbackNoticeShown = true
                val reason = if (!netUp) "Mất kết nối mạng" else lastErrorReason.ifEmpty { "Giọng AI gián đoạn" }
                onFallbackNotice("$reason, tự chuyển sang Offline")
            }
            playOffline(session, index, seg.text)
            return
        }

        // Mạng vừa có trở lại sau khi tắt Wi-Fi -> tự chuyển lại sang giọng AI
        if (wasOfflineDueToNoNet) {
            wasOfflineDueToNoNet = false
            fallbackNoticeShown = false
            lastErrorReason = ""
        }

        val currentFuture = ensurePrefetch(session, index)
        if (index + 1 < segments.size) ensurePrefetch(session, index + 1)

        waitExecutor.execute {
            val file = runCatching { currentFuture.get(16, TimeUnit.SECONDS) }.getOrNull()
            mainHandler.post {
                if (session != sessionCounter.get() || !isPlaying) return@post
                if (file != null && file.exists() && file.length() > 100) {
                    playMp3File(session, index, file)
                } else {
                    cancelPendingFetches()
                    if (!hasActiveNetwork()) {
                        wasOfflineDueToNoNet = true
                    } else {
                        onlineCooldownUntilMs = System.currentTimeMillis() + 45_000L
                    }
                    if (!fallbackNoticeShown) {
                        fallbackNoticeShown = true
                        val detail = if (lastErrorReason.isNotEmpty()) " ($lastErrorReason)" else ""
                        onFallbackNotice("Giọng AI gián đoạn$detail, chuyển sang Offline")
                    }
                    playOffline(session, index, seg.text)
                }
            }
        }
    }

    private fun ensurePrefetch(session: Int, index: Int): Future<File?> {
        prefetchMap[index]?.let { return it }
        val future = fetchExecutor.submit(Callable {
            if (session != sessionCounter.get() || !hasActiveNetwork()) return@Callable null
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
        if (!offlineLocalVoicePicked) {
            selectBestOfflineVietnameseVoice(tts)
        }
        tts.setSpeechRate(speed)
        onStateChanged(true, "Đang đọc (Offline) • ${index + 1}/${segments.size}")
        val uid = "${session}_${index}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
        }
        val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
        if (res == TextToSpeech.ERROR) {
            isPlaying = false
            releaseWakeLock()
            onStateChanged(false, "Lỗi gọi giọng Offline trên máy")
            onFallbackNotice("Không phát được giọng Offline (kiểm tra Cài đặt TTS trên máy)")
        }
    }

    private fun buildWsUrl(useNew: Boolean, gec: String, connId: String): String {
        val token = EdgeTtsDrm.TRUSTED_CLIENT_TOKEN
        val version = EdgeTtsDrm.SEC_MS_GEC_VERSION
        return if (useNew) {
            "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1" +
                "?Ocp-Apim-Subscription-Key=$token" +
                "&Sec-MS-GEC=$gec" +
                "&Sec-MS-GEC-Version=$version" +
                "&ConnectionId=$connId"
        } else {
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$token" +
                "&Sec-MS-GEC=$gec" +
                "&Sec-MS-GEC-Version=$version" +
                "&ConnectionId=$connId"
        }
    }

    private fun synthesizeEdgeTts(
        session: Int,
        index: Int,
        text: String,
        voiceName: String,
        rateMultiplier: Float
    ): File? {
        if (session != sessionCounter.get() || Thread.currentThread().isInterrupted) return null
        if (!hasActiveNetwork()) {
            lastErrorReason = "Mất mạng"
            return null
        }

        val cleanText = escapeXml(text)
        if (cleanText.isBlank()) return null

        val pct = ((rateMultiplier - 1.0f) * 100f).toInt().coerceIn(-50, 100)
        val rateStr = if (pct >= 0) "+$pct%" else "$pct%"

        val metaJson = JSONObject()
            .put("sentenceBoundaryEnabled", "false")
            .put("wordBoundaryEnabled", "false")
        val audioJson = JSONObject()
            .put("metadataoptions", metaJson)
            .put("outputFormat", "audio-24khz-48kbitrate-mono-mp3")
        val cfgBody = JSONObject()
            .put("context", JSONObject().put("synthesis", JSONObject().put("audio", audioJson)))
            .toString()

        var switchedEndpoint = false
        var retriedNetwork = false

        for (attempt in 0 until 4) {
            if (session != sessionCounter.get() || Thread.currentThread().isInterrupted) return null
            if (!hasActiveNetwork()) {
                lastErrorReason = "Mất mạng"
                return null
            }

            val waitGap = 350L - (System.currentTimeMillis() - lastRequestAtMs)
            if (waitGap > 0L) {
                runCatching { Thread.sleep(waitGap) }
                if (session != sessionCounter.get() || Thread.currentThread().isInterrupted) return null
            }
            lastRequestAtMs = System.currentTimeMillis()

            val gec = EdgeTtsDrm.generateSecMsGecV2()
            val connId = UUID.randomUUID().toString().replace("-", "")
            val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
            val url = buildWsUrl(useNewEndpoint, gec, connId)

            val request = Request.Builder()
                .url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Cookie", "muid=$muid;")
                .header("User-Agent", EdgeTtsDrm.USER_AGENT)
                .build()

            val audioOut = ByteArrayOutputStream(32768)
            val latch = CountDownLatch(1)
            val completed = AtomicBoolean(false)
            val failedHttpCode = AtomicInteger(0)
            val failedServerDate = AtomicReference<String?>(null)
            val failedRetryAfter = AtomicReference<String?>(null)
            val failedNetwork = AtomicBoolean(false)

            val ts = utcTimestamp()
            val reqId = UUID.randomUUID().toString().replace("-", "")

            val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val configMsg = "X-Timestamp:$ts\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        cfgBody
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
                    if (response != null) {
                        failedHttpCode.set(response.code)
                        failedServerDate.set(response.header("Date"))
                        failedRetryAfter.set(response.header("Retry-After"))
                        lastErrorReason = "HTTP ${response.code}"
                    } else {
                        failedNetwork.set(true)
                        val root = t.cause ?: t
                        val detail = root.message?.replace("\n", " ")?.take(52).orEmpty()
                        lastErrorReason = if (detail.isNotEmpty()) {
                            "${root.javaClass.simpleName}: $detail"
                        } else {
                            root.javaClass.simpleName
                        }
                    }
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            })

            activeWebSocket.set(ws)
            val finishedInTime = try {
                latch.await(12, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                ws.cancel()
                return null
            } finally {
                activeWebSocket.compareAndSet(ws, null)
            }

            if (!finishedInTime) {
                lastErrorReason = "Timeout"
                ws.cancel()
            }

            if (completed.get() && audioOut.size() >= 100 && session == sessionCounter.get()) {
                val outFile = File(ttsCacheDir, "tts_${session}_${index}.mp3")
                return runCatching {
                    outFile.writeBytes(audioOut.toByteArray())
                    outFile
                }.getOrNull()
            }

            if (!hasActiveNetwork()) {
                lastErrorReason = "Mất mạng"
                return null
            }

            val code = failedHttpCode.get()
            if (code == 403) {
                val serverDate = failedServerDate.get()
                if (serverDate != null && EdgeTtsDrm.adjustClockSkewIfNeeded(serverDate)) {
                    continue
                }
                if (!switchedEndpoint) {
                    useNewEndpoint = !useNewEndpoint
                    switchedEndpoint = true
                    continue
                }
            } else if (code == 429 || code in 500..599) {
                val retryAfterSec = failedRetryAfter.get()?.trim()?.toLongOrNull()
                val delayMs = retryAfterSec?.let { (it * 1000L).coerceIn(600L, 3000L) }
                    ?: (650L * (attempt + 1)).coerceAtMost(2500L)
                if (attempt >= 1 && !switchedEndpoint) {
                    useNewEndpoint = !useNewEndpoint
                    switchedEndpoint = true
                }
                runCatching { Thread.sleep(delayMs) }
                continue
            } else if (failedNetwork.get()) {
                if (!switchedEndpoint) {
                    useNewEndpoint = !useNewEndpoint
                    switchedEndpoint = true
                    runCatching { Thread.sleep(250) }
                    continue
                }
                if (!retriedNetwork) {
                    retriedNetwork = true
                    runCatching { Thread.sleep(350) }
                    continue
                }
            }
            break
        }
        return null
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
        val filtered = input.filter { it.code >= 32 || it == '\n' || it == '\r' || it == '\t' }
        return TextUtils.htmlEncode(filtered).trim()
    }
}
// END BookTtsEngine
