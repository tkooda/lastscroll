package com.ionecho.lastscroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil

class TimerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { onExpire() }
    private val toastTick = Runnable { onToast() }
    private val minuteTick = Runnable { onMinute() }
    private var deadline = 0L
    private var remaining = 0L
    private var paused = false
    private var toastMin = 0
    private var lastPost = 0L
    private var lastMin = -1
    private var started = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            // Start paused unless a selected app is in front (never pause without accessibility).
            val fg = ActivityService.instance?.foregroundPackage
            paused = fg != null && fg !in MainActivity.selected(this)
            if (paused) Log.d(TAG, "start paused: $fg")
        }
        lastPost = 0L
        reset()
        if (!started) {
            started = true
            running = true
            startForeground(NOTIF_ID, buildNotification())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        handler.removeCallbacks(toastTick)
        handler.removeCallbacks(minuteTick)
        instance = null
        running = false
        super.onDestroy()
    }

    fun reset() {
        val total = MainActivity.minutes(this)
        val full = total * 60_000L
        // Toast only when the reset changes the minutes shown (not on start or while paused).
        if (started && !paused && minutesLeft() < total) {
            handler.post { Toast.makeText(this, R.string.toast_reset, Toast.LENGTH_SHORT).show() }
        }
        if (paused) remaining = full else schedule(System.currentTimeMillis() + full)
        post(false)
    }

    fun pause(pkg: String) {
        if (paused) return
        paused = true
        remaining = deadline - System.currentTimeMillis()
        handler.removeCallbacks(expire)
        handler.removeCallbacks(toastTick)
        handler.removeCallbacks(minuteTick)
        Log.d(TAG, "pause: $pkg")
        post(true)
    }

    fun resume(pkg: String) {
        if (!paused) return
        paused = false
        schedule(System.currentTimeMillis() + remaining)
        Log.d(TAG, "resume: $pkg")
        post(true)
    }

    private fun schedule(at: Long) {
        deadline = at
        val left = at - System.currentTimeMillis()
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, left)
        // Toast once per whole minute once remaining time is within the final window.
        handler.removeCallbacks(toastTick)
        val window = ceil(MainActivity.minutes(this) * 0.2).toInt().coerceIn(1, 5)
        toastMin = minOf(window.toLong(), left / 60_000L).toInt()
        if (toastMin >= 1) handler.postDelayed(toastTick, left - toastMin * 60_000L)
        // Repost when the whole minutes left change.
        handler.removeCallbacks(minuteTick)
        if (left > 0) handler.postDelayed(minuteTick, (left - 1) % 60_000L + 1)
    }

    private fun onMinute() {
        post(true)
        val left = deadline - System.currentTimeMillis()
        if (left > 0) handler.postDelayed(minuteTick, (left - 1) % 60_000L + 1)
    }

    // Status bar display setting changed.
    fun refresh() = post(true)

    private fun minutesLeft(): Int {
        val left = if (paused) remaining else deadline - System.currentTimeMillis()
        return ceil(left / 60_000.0).toInt()
    }

    private fun onToast() {
        Toast.makeText(this, getString(R.string.toast, toastMin), Toast.LENGTH_SHORT).show()
        toastMin--
        if (toastMin >= 1) handler.postDelayed(toastTick, 60_000L)
    }

    // Repost the notification, at most every few seconds unless forced.
    private fun post(force: Boolean) {
        val now = System.currentTimeMillis()
        val min = minutesLeft()
        if (!force && now - lastPost < 5_000L && min == lastMin) return
        lastPost = now
        lastMin = min
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, TimerService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val min = minutesLeft()
        val mode = MainActivity.display(this)
        val promote = mode == MainActivity.DISPLAY_BOTH && Build.VERSION.SDK_INT >= 36 &&
            getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(
                if (mode == MainActivity.DISPLAY_ICON || promote) Icon.createWithResource(this, R.drawable.ic_notification)
                else Icon.createWithBitmap(minutesBitmap(min))
            )
            .setContentTitle(getString(R.string.notif_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setDeleteIntent(cancel)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    getString(R.string.cancel), cancel
                ).build()
            )
        if (promote) {
            // Android 16 Live Update: the status bar chip shows the icon and the minutes left.
            b.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            b.setShortCriticalText(min.toString())
        }
        // Whole minutes only: no ticking seconds anywhere.
        b.setContentText(getString(if (paused) R.string.notif_paused else R.string.notif_text, min))
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setChronometerCountDown(false)
        return b.build()
    }

    // White minutes number on a transparent square, as large as fits.
    private fun minutesBitmap(min: Int): Bitmap {
        val size = 96
        val text = min.toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = size.toFloat()
        }
        val r = Rect()
        paint.getTextBounds(text, 0, text.length, r)
        paint.textSize *= minOf(size / r.width().toFloat(), size / r.height().toFloat())
        paint.getTextBounds(text, 0, text.length, r)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, size / 2f - r.exactCenterX(), size / 2f - r.exactCenterY(), paint)
        return bmp
    }

    private fun onExpire() {
        val am = getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        val a11y = ActivityService.instance
        if (a11y != null) {
            a11y.sleepNow()
        } else {
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.no_a11y))
                .setStyle(Notification.BigTextStyle().bigText(getString(R.string.no_a11y)))
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
        }
        stopSelf()
    }

    companion object {
        const val TAG = "LastScroll"
        private const val CHANNEL = "timer"
        private const val NOTIF_ID = 1
        private const val ACTION_CANCEL = "com.ionecho.lastscroll.CANCEL"
        // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (not public in the API 36 SDK).
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        var instance: TimerService? = null
            private set

        // True while a timer runs; the config screen observes it.
        var running by mutableStateOf(false)
            private set

        // Same as the notification's Cancel action.
        fun stop(c: Context) {
            c.startService(Intent(c, TimerService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
