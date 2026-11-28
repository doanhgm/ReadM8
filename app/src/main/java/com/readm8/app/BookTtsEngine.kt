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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    private enum class PlayMode { NONE, AI_MP3, SYSTEM_TTS }

    private companion object {
        const val MAX_CACHE_BYTES = 150L * 1024 * 1024 // 150 MB (~7+ tiếng đọc AI)
        const val PRUNE_TARGET_BYTES = 120L * 1024 * 1024
        const val MIN_VALID_MP3_BYTES = 128L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
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

    // Lưu bền vững trong filesDir để tắt app / tắt Wi-Fi mở lại vẫn còn nguyên giọng AI
    private val ttsCacheDir = File(context.filesDir, "tts_offline_cache").apply { mkdirs() }

    private val sessionCounter = AtomicInteger(0)
    private val chapterJobId = AtomicInteger(0)
    private val offlineConsecutiveErrors = AtomicInteger(0)
    private val consecutiveBgErrors = AtomicInteger(0)
    private val downloadingIndex = AtomicInteger(-1)
    private val released = AtomicBoolean(false)
    private val activeWebSocket = AtomicReference<WebSocket?>(null)

    private val playerLock = Any()
    private val cacheLock = ReentrantLock()
    private val cacheCondition = cacheLock.newCondition()

    private val cachedIndices = ConcurrentHashMap.newKeySet<Int>()
    private val failedIndices = ConcurrentHashMap.newKeySet<Int>()

    private var mediaPlayer: MediaPlayer? = null
    private var offlineTts: TextToSpeech? = null
    private var offlineReady = false
    private var offlineLocalVoicePicked = false
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var segments: List<TtsSegment> = emptyList()

    @Volatile
    private var currentSegIndex = 0

    @Volatile
    private var currentPlayMode = PlayMode.NONE

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

    @Volatile
    var voice: VoiceOption = VoiceOption.HOAI_MY

    @Volatile
    var speed: Float = 1.0f

    private var sleepRunnable: Runnable? = null
    var sleepMinutesLeft: Int = 0
        private set

    private val cacheWorkerThread = Thread({ runCacheLoop() }, "ReadM8-ChapterCache").apply {
        isDaemon = true
    }

    init {
        initOfflineTts()
        cacheWorkerThread.start()
        cleanupLegacyAndTrimAsync()
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

    private fun rateString(rateMultiplier: Float): String {
        val pct = ((rateMultiplier - 1.0f) * 100f).toInt().coerceIn(-50, 100)
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    private fun getCacheFile(v: VoiceOption, spd: Float, text: String): File {
        val key = "${v.edgeName}|${rateString(spd)}|$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(24)
        for (i in 0 until 12) {
            val b = digest[i].toInt() and 0xFF
            hex.append("0123456789abcdef"[b ushr 4])
            hex.append("0123456789abcdef"[b and 0x0F])
        }
        return File(ttsCacheDir, "ai_$hex.mp3")
    }

    private fun isValidAudioFile(file: File?): Boolean {
        return file != null && file.exists() && file.length() >= MIN_VALID_MP3_BYTES
    }

    private fun scanCachedSegmentsForCurrentChapter() {
        cachedIndices.clear()
        failedIndices.clear()
        val v = voice
        if (!v.isOnline) return
        val spd = speed
        val segs = segments
        for (i in segs.indices) {
            if (isValidAudioFile(getCacheFile(v, spd, segs[i].text))) {
                cachedIndices.add(i)
            }
        }
    }

    private fun isRemainingFullyCached(): Boolean {
        val segs = segments
        if (segs.isEmpty()) return false
        val start = currentSegIndex.coerceIn(0, segs.lastIndex)
        for (i in start..segs.lastIndex) {
            if (i !in cachedIndices) return false
        }
        return true
    }

    private fun buildStatusText(): String {
        val total = segments.size
        if (total == 0) return ""
        val idxDisplay = (currentSegIndex + 1).coerceIn(1, total)

        if (!isPlaying) {
            if (!isActive) return ""
            return if (voice.isOnline) {
                val tag = if (isRemainingFullyCached()) {
                    "Đã lưu offline ✓"
                } else if (hasActiveNetwork()) {
                    "Đang lưu offline ${cachedIndices.size}/$total"
                } else {
                    "Đã lưu ${cachedIndices.size}/$total"
                }
                "Tạm dừng • $tag"
            } else {
                "Đã tạm dừng"
            }
        }

        return when {
            !voice.isOnline -> "Đang đọc (Offline) • $idxDisplay/$total"
            currentPlayMode == PlayMode.SYSTEM_TTS -> "Đang đọc (Giọng máy) • $idxDisplay/$total"
            else -> {
                val netUp = hasActiveNetwork()
                val label = if (netUp) voice.shortLabel else "${voice.shortLabel} (Offline)"
                val tag = if (isRemainingFullyCached()) {
                    "Offline ✓"
                } else {
                    "Lưu ${cachedIndices.size}/$total"
                }
                "Đang đọc ($label) • $idxDisplay/$total • $tag"
            }
        }
    }

    private fun refreshStatusText() {
        mainHandler.post {
            if (!isActive) return@post
            onStateChanged(isPlaying, buildStatusText())
        }
    }

    private fun signalCacheWaiters() {
        cacheLock.withLock {
            cacheCondition.signalAll()
        }
    }

    private fun awaitCacheSignal(timeoutMs: Long) {
        cacheLock.withLock {
            runCatching {
                cacheCondition.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun cleanupLegacyAndTrimAsync() {
        Thread {
            runCatching {
                // Dọn thư mục tạm cũ nếu còn tồn tại
                File(context.cacheDir, "tts_audio").listFiles()?.forEach { it.delete() }
            }
            trimCacheIfNeeded()
        }.start()
    }

    private fun trimCacheIfNeeded() {
        runCatching {
            val allFiles = ttsCacheDir.listFiles() ?: return
            val now = System.currentTimeMillis()
            val mp3Files = mutableListOf<File>()
            var totalBytes = 0L

            for (f in allFiles) {
                if (f.name.endsWith(".tmp")) {
                    if (now - f.lastModified() > 5 * 60_000L) f.delete()
                } else if (f.isFile) {
                    mp3Files.add(f)
                    totalBytes += f.length()
                }
            }

            if (totalBytes <= MAX_CACHE_BYTES) return
            mp3Files.sortBy { it.lastModified() }
            for (f in mp3Files) {
                val len = f.length()
                if (f.delete()) {
                    totalBytes -= len
                }
                if (totalBytes <= PRUNE_TARGET_BYTES) break
            }
        }
    }

    private fun runCacheLoop() {
        while (!released.get()) {
            val jobId = chapterJobId.get()
            val segs = segments
            val v = voice
            val spd = speed

            if (!isActive || !v.isOnline || segs.isEmpty()) {
                awaitCacheSignal(1500L)
                continue
            }

            val now = System.currentTimeMillis()
            if (!hasActiveNetwork() || now < onlineCooldownUntilMs) {
                awaitCacheSignal(1500L)
                continue
            }

            val curIdx = currentSegIndex.coerceIn(0, segs.lastIndex)
            var targetIdx = -1
            for (i in curIdx..segs.lastIndex) {
                if (i !in cachedIndices && i !in failedIndices) {
                    targetIdx = i
                    break
                }
            }

            if (targetIdx == -1) {
                awaitCacheSignal(1500L)
                continue
            }

            // Giãn nhịp nhẹ cho các đoạn tải ngầm phía sau để tránh HTTP 429
            if (targetIdx > currentSegIndex + 1) {
                awaitCacheSignal(550L)
                if (released.get() || jobId != chapterJobId.get() || !isActive) continue
                val latestCur = currentSegIndex.coerceIn(0, segs.lastIndex)
                if (latestCur < targetIdx && latestCur !in cachedIndices && latestCur !in failedIndices) {
                    continue
                }
            }

            val seg = segs.getOrNull(targetIdx) ?: continue
            val outFile = getCacheFile(v, spd, seg.text)
            if (isValidAudioFile(outFile)) {
                cachedIndices.add(targetIdx)
                signalCacheWaiters()
                refreshStatusText()
                continue
            }

            downloadingIndex.set(targetIdx)
            val ok = try {
                synthesizeEdgeTtsToFile(jobId, targetIdx, seg.text, v.edgeName, spd, outFile)
            } finally {
                downloadingIndex.set(-1)
            }

            if (released.get() || jobId != chapterJobId.get()) {
                signalCacheWaiters()
                continue
            }

            if (ok) {
                cachedIndices.add(targetIdx)
                consecutiveBgErrors.set(0)
                if (wasOfflineDueToNoNet) {
                    wasOfflineDueToNoNet = false
                    fallbackNoticeShown = false
                    lastErrorReason = ""
                }
                signalCacheWaiters()
                refreshStatusText()
            } else {
                if (!hasActiveNetwork()) {
                    wasOfflineDueToNoNet = true
                    signalCacheWaiters()
                } else if (targetIdx == currentSegIndex) {
                    failedIndices.add(targetIdx)
                    onlineCooldownUntilMs = System.currentTimeMillis() + 30_000L
                    signalCacheWaiters()
                } else {
                    // Nếu đang tải ngầm mà bị ngắt do người dùng nhảy sang đoạn khác thì bỏ qua không tính lỗi
                    val urgentMoved = currentSegIndex != targetIdx &&
                        currentSegIndex !in cachedIndices &&
                        currentSegIndex !in failedIndices
                    if (!urgentMoved) {
                        val errs = consecutiveBgErrors.incrementAndGet()
                        if (errs >= 2) {
                            onlineCooldownUntilMs = System.currentTimeMillis() + 30_000L
                            consecutiveBgErrors.set(0)
                        }
                    }
                    signalCacheWaiters()
                }
            }
        }
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

    private fun cancelActiveWebSocket() {
        activeWebSocket.getAndSet(null)?.cancel()
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
                onFallbackNotice("Chưa có giọng Tiếng Việt Offline trên máy")
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
        currentPlayMode = PlayMode.NONE
        fallbackNoticeShown = false
        wasOfflineDueToNoNet = false
        onlineCooldownUntilMs = 0L
        offlineConsecutiveErrors.set(0)
        consecutiveBgErrors.set(0)
        lastErrorReason = ""
        acquireWakeLock()

        scanCachedSegmentsForCurrentChapter()
        chapterJobId.incrementAndGet()
        val session = sessionCounter.incrementAndGet()
        signalCacheWaiters()

        onStateChanged(true, "Đang chuẩn bị giọng ${voice.shortLabel}...")
        playSegmentInternal(session, currentSegIndex)
        Thread { trimCacheIfNeeded() }.start()
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
            onStateChanged(false, buildStatusText())
        } else {
            isPlaying = true
            acquireWakeLock()
            var resumed = false
            synchronized(playerLock) {
                val mp = mediaPlayer
                if (mp != null && voice.isOnline && currentPlayMode == PlayMode.AI_MP3) {
                    resumed = runCatching {
                        mp.start()
                        true
                    }.getOrDefault(false)
                }
            }
            if (resumed) {
                onStateChanged(true, buildStatusText())
                signalCacheWaiters()
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
        consecutiveBgErrors.set(0)
        lastErrorReason = ""
        cancelActiveWebSocket()
        scanCachedSegmentsForCurrentChapter()
        chapterJobId.incrementAndGet()
        signalCacheWaiters()
        if (isActive && isPlaying) {
            jumpToSegment(currentSegIndex)
        } else if (isActive) {
            refreshStatusText()
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
        released.set(true)
        stopInternal(clearState = true)
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        signalCacheWaiters()
        cacheWorkerThread.interrupt()
        waitExecutor.shutdownNow()
        runCatching { offlineTts?.shutdown() }
        offlineTts = null
    }

    private fun jumpToSegment(idx: Int) {
        currentSegIndex = idx
        stopMediaOnly()
        isPlaying = true
        acquireWakeLock()
        val session = sessionCounter.incrementAndGet()
        playSegmentInternal(session, idx)
    }

    private fun stopInternal(clearState: Boolean) {
        sessionCounter.incrementAndGet()
        chapterJobId.incrementAndGet()
        isPlaying = false
        if (clearState) isActive = false
        currentPlayMode = PlayMode.NONE
        stopMediaOnly()
        cancelActiveWebSocket()
        signalCacheWaiters()
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

        // 1. Ưu tiên phát ngay file giọng AI đã lưu sẵn trên máy (kể cả khi tắt Wi-Fi)
        val cacheFile = getCacheFile(voice, speed, seg.text)
        if (isValidAudioFile(cacheFile)) {
            cachedIndices.add(index)
            runCatching { cacheFile.setLastModified(System.currentTimeMillis()) }
            playMp3File(session, index, cacheFile)
            signalCacheWaiters()
            return
        }

        // 2. Chưa có trong bộ nhớ máy -> kiểm tra mạng
        val now = System.currentTimeMillis()
        val netUp = hasActiveNetwork()
        if (!netUp || now < onlineCooldownUntilMs) {
            if (!netUp) wasOfflineDueToNoNet = true
            if (!fallbackNoticeShown) {
                fallbackNoticeShown = true
                val notice = when {
                    !netUp && cachedIndices.isNotEmpty() -> "Hết đoạn AI đã lưu, chuyển sang giọng máy"
                    !netUp -> "Mất kết nối mạng, chuyển sang giọng máy"
                    lastErrorReason.isNotEmpty() -> "Giọng AI gián đoạn ($lastErrorReason), chuyển sang giọng máy"
                    else -> "Giọng AI gián đoạn, chuyển sang giọng máy"
                }
                onFallbackNotice(notice)
            }
            playOffline(session, index, seg.text)
            return
        }

        if (wasOfflineDueToNoNet) {
            wasOfflineDueToNoNet = false
            fallbackNoticeShown = false
            lastErrorReason = ""
        }

        // 3. Có mạng: đẩy đoạn hiện tại lên ưu tiên cao nhất và chờ tải xong
        failedIndices.remove(index)
        val currentlyDownloading = downloadingIndex.get()
        if (currentlyDownloading != -1 && currentlyDownloading != index) {
            cancelActiveWebSocket()
        }
        signalCacheWaiters()
        onStateChanged(true, "Đang tải (${voice.shortLabel}) • ${index + 1}/${segments.size}")

        waitExecutor.execute {
            val deadline = System.currentTimeMillis() + 16_000L
            while (System.currentTimeMillis() < deadline) {
                if (session != sessionCounter.get() || !isPlaying || released.get()) return@execute
                if (isValidAudioFile(cacheFile)) break
                if (index in failedIndices) break
                if (!hasActiveNetwork() || System.currentTimeMillis() < onlineCooldownUntilMs) break
                awaitCacheSignal(300L)
            }

            mainHandler.post {
                if (session != sessionCounter.get() || !isPlaying) return@post
                if (isValidAudioFile(cacheFile)) {
                    cachedIndices.add(index)
                    playMp3File(session, index, cacheFile)
                } else {
                    if (!hasActiveNetwork()) {
                        wasOfflineDueToNoNet = true
                    } else if (System.currentTimeMillis() >= onlineCooldownUntilMs) {
                        onlineCooldownUntilMs = System.currentTimeMillis() + 30_000L
                    }
                    if (!fallbackNoticeShown) {
                        fallbackNoticeShown = true
                        val detail = if (lastErrorReason.isNotEmpty()) " ($lastErrorReason)" else ""
                        onFallbackNotice("Giọng AI gián đoạn$detail, chuyển sang giọng máy")
                    }
                    playOffline(session, index, seg.text)
                }
            }
        }
    }

    private fun playMp3File(session: Int, index: Int, file: File) {
        stopMediaOnly()
        currentPlayMode = PlayMode.AI_MP3
        onStateChanged(true, buildStatusText())
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
                    mainHandler.post {
                        if (session == sessionCounter.get() && isPlaying) {
                            playSegmentInternal(session, index + 1)
                        }
                    }
                }
                setOnErrorListener { _, _, _ ->
                    // File lỗi -> xóa để lần sau tải lại
                    runCatching { file.delete() }
                    cachedIndices.remove(index)
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
            runCatching { file.delete() }
            cachedIndices.remove(index)
            playOffline(session, index, segments[index].text)
        }
    }

    private fun playOffline(session: Int, index: Int, text: String) {
        stopMediaOnly()
        currentPlayMode = PlayMode.SYSTEM_TTS
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
        onStateChanged(true, buildStatusText())
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

    private fun synthesizeEdgeTtsToFile(
        jobId: Int,
        index: Int,
        text: String,
        voiceName: String,
        rateMultiplier: Float,
        outFile: File
    ): Boolean {
        if (released.get() || jobId != chapterJobId.get() || Thread.currentThread().isInterrupted) return false
        if (!hasActiveNetwork()) {
            lastErrorReason = "Mất mạng"
            return false
        }

        val cleanText = escapeXml(text)
        if (cleanText.isBlank()) return false

        val rateStr = rateString(rateMultiplier)
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
            if (released.get() || jobId != chapterJobId.get() || Thread.currentThread().isInterrupted) return false
            if (index != currentSegIndex && isPlaying &&
                currentSegIndex !in cachedIndices && currentSegIndex !in failedIndices
            ) {
                return false
            }
            if (!hasActiveNetwork()) {
                lastErrorReason = "Mất mạng"
                return false
            }

            val waitGap = 350L - (System.currentTimeMillis() - lastRequestAtMs)
            if (waitGap > 0L) {
                awaitCacheSignal(waitGap)
                if (released.get() || jobId != chapterJobId.get() || Thread.currentThread().isInterrupted) return false
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
                return false
            } finally {
                activeWebSocket.compareAndSet(ws, null)
            }

            if (!finishedInTime) {
                lastErrorReason = "Timeout"
                ws.cancel()
            }

            if (completed.get() && audioOut.size() >= MIN_VALID_MP3_BYTES) {
                val saved = runCatching {
                    val tmpFile = File(ttsCacheDir, "${outFile.name}.${UUID.randomUUID().toString().take(6)}.tmp")
                    tmpFile.writeBytes(audioOut.toByteArray())
                    if (outFile.exists()) outFile.delete()
                    if (!tmpFile.renameTo(outFile)) {
                        outFile.writeBytes(tmpFile.readBytes())
                        tmpFile.delete()
                    }
                    isValidAudioFile(outFile)
                }.getOrDefault(false)
                if (saved) return true
            }

            if (!hasActiveNetwork()) {
                lastErrorReason = "Mất mạng"
                return false
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
                awaitCacheSignal(delayMs)
                continue
            } else if (failedNetwork.get()) {
                if (!switchedEndpoint) {
                    useNewEndpoint = !useNewEndpoint
                    switchedEndpoint = true
                    awaitCacheSignal(250L)
                    continue
                }
                if (!retriedNetwork) {
                    retriedNetwork = true
                    awaitCacheSignal(350L)
                    continue
                }
            }
            break
        }
        return false
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
