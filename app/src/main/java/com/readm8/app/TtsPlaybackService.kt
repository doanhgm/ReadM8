package com.readm8.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object TtsServiceBridge {
    @Volatile var onTogglePlayPause: (() -> Unit)? = null
    @Volatile var onStopPlayback: (() -> Unit)? = null
    @Volatile var onPrevSegment: (() -> Unit)? = null
}

class TtsPlaybackService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wifiLock: WifiManager.WifiLock? = null

    private var currentTitle: String = "ReadM8"
    private var currentStatus: String = "Đang đọc sách..."
    private var currentPlaying: Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                mainHandler.post { TtsServiceBridge.onTogglePlayPause?.invoke() }
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                mainHandler.post { TtsServiceBridge.onPrevSegment?.invoke() }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                mainHandler.post { TtsServiceBridge.onStopPlayback?.invoke() }
                releaseWifiLock()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: currentTitle
                currentStatus = intent?.getStringExtra(EXTRA_STATUS) ?: currentStatus
                currentPlaying = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true

                if (currentPlaying) acquireWifiLock() else releaseWifiLock()
                startForeground(NOTIF_ID, buildNotification())
                return START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mainHandler.post { TtsServiceBridge.onStopPlayback?.invoke() }
        releaseWifiLock()
        stopForegroundCompat()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWifiLock()
        super.onDestroy()
    }

    private fun acquireWifiLock() {
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ReadM8:TtsWifiLock")
                wifiLock?.setReferenceCounted(false)
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Đọc sách nền",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Giữ ReadM8 tiếp tục đọc sách khi tắt màn hình"
                    setShowBadge(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openIntent = Intent(this, ReaderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(this, 100, openIntent, piFlags)

        val prevPi = PendingIntent.getService(
            this,
            101,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_PREV),
            piFlags
        )
        val togglePi = PendingIntent.getService(
            this,
            102,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_TOGGLE),
            piFlags
        )
        val stopPi = PendingIntent.getService(
            this,
            103,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_STOP),
            piFlags
        )

        val toggleLabel = if (currentPlaying) "Tạm dừng" else "Tiếp tục"
        val toggleIcon = if (currentPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(currentTitle)
            .setContentText(currentStatus)
            .setContentIntent(openPi)
            .setOngoing(currentPlaying)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Trước", prevPi)
            .addAction(toggleIcon, toggleLabel, togglePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", stopPi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "readm8_tts_channel"
        private const val NOTIF_ID = 8081

        private const val ACTION_SYNC = "com.readm8.app.TTS_SYNC"
        private const val ACTION_TOGGLE = "com.readm8.app.TTS_TOGGLE"
        private const val ACTION_PREV = "com.readm8.app.TTS_PREV"
        private const val ACTION_STOP = "com.readm8.app.TTS_STOP"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_PLAYING = "playing"

        fun sync(context: Context, bookTitle: String, statusText: String, isPlaying: Boolean) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_TITLE, bookTitle)
                putExtra(EXTRA_STATUS, statusText)
                putExtra(EXTRA_PLAYING, isPlaying)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, TtsPlaybackService::class.java))
            }
        }
    }
}
// END TtsPlaybackService
