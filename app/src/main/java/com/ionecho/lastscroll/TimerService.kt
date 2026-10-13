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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil

class TimerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { onTick() }
    private val timer = Countdown()
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPost = 0L
    private var lastMin = -1
    private var started = false
    private var done = false
    private val paused get() = timer.paused

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            // Start paused unless a selected app is in front (never pause without accessibility).
            val fg = ActivityService.instance?.foregroundPackage
            if (fg != null && fg !in MainActivity.selected(this)) {
                timer.pause(now())
                Log.d(TAG, "start paused: $fg")
            }
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
        handler.removeCallbacks(tick)
        wakeLock?.let { if (it.isHeld) it.release() }
        instance = null
        running = false
        super.onDestroy()
    }

    private fun now() = SystemClock.elapsedRealtime()

    // Every event re-checks the deadline, in case the tick ran late. True once the timer is over.
    private fun checkExpired(): Boolean {
        if (done) return true
        if (!started || !timer.expired(now())) return false
        onExpire()
        return true
    }

    fun reset() {
        if (checkExpired()) return
        val total = MainActivity.minutes(this)
        // Toast only when the reset changes the minutes shown (not on start or while paused).
        if (started && !paused && timer.minutes(now()) < total) {
            handler.post { Toast.makeText(this, R.string.toast_reset, Toast.LENGTH_SHORT).show() }
        }
        timer.set(now(), total * 60_000L)
        schedule()
        post(false)
    }

    fun pause(pkg: String) {
        if (checkExpired() || !timer.pause(now())) return
        Log.d(TAG, "pause: $pkg")
        schedule()
        post(true)
    }

    fun resume(pkg: String) {
        if (checkExpired() || !timer.resume(now())) return
        Log.d(TAG, "resume: $pkg")
        schedule()
        post(true)
    }

    // The only scheduled callback: one tick at each whole-minute change, the last one at the deadline.
    // A partial wake lock keeps it on time if the screen goes off; none is held while paused.
    private fun schedule() {
        handler.removeCallbacks(tick)
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LastScroll:timer")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (paused) {
            if (wl.isHeld) wl.release()
            return
        }
        val now = now()
        handler.postDelayed(tick, timer.nextTick(now))
        wl.acquire(timer.left(now) + 10_000L)
    }

    private fun onTick() {
        if (checkExpired()) return
        // Toast once per whole minute within the final window.
        val min = timer.minutes(now())
        val window = ceil(MainActivity.minutes(this) * 0.2).toInt().coerceIn(1, 5)
        if (min in 1..window) Toast.makeText(this, getString(R.string.toast, min), Toast.LENGTH_SHORT).show()
        schedule()
        post(true)
    }

    // Status bar display setting changed.
    fun refresh() {
        if (!checkExpired()) post(true)
    }

    // Repost the notification, at most every few seconds unless forced.
    private fun post(force: Boolean) {
        if (done) return
        val now = now()
        val min = timer.minutes(now)
        if (!force && now - lastPost < 5_000L && min == lastMin) return
        lastPost = now
        lastMin = min
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val cancel = PendingIntent.getBroadcast(
            this, 0, Intent(this, CancelReceiver::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val min = timer.minutes(now())
        // Paused shows "II" before the minutes, like a pause sign.
        val shown = if (paused) "II$min" else min.toString()
        val mode = MainActivity.display(this)
        val promote = mode == MainActivity.DISPLAY_BOTH && Build.VERSION.SDK_INT >= 36 &&
            getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(
                if (mode == MainActivity.DISPLAY_ICON || promote) Icon.createWithResource(this, R.drawable.ic_notification)
                else Icon.createWithBitmap(minutesBitmap(shown))
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
            b.setShortCriticalText(shown)
        }
        // Whole minutes only: no ticking seconds anywhere.
        b.setContentText(getString(if (paused) R.string.notif_paused else R.string.notif_text, min))
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setChronometerCountDown(false)
        return b.build()
    }

    // White minutes text on a transparent square, as large as fits.
    private fun minutesBitmap(text: String): Bitmap {
        val size = 96
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

    // Fully over: nothing may repost the notification after this.
    private fun finish() {
        done = true
        running = false
        handler.removeCallbacks(tick)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        stopSelf()
    }

    private fun onExpire() {
        finish()
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
    }

    companion object {
        const val TAG = "LastScroll"
        private const val CHANNEL = "timer"
        private const val NOTIF_ID = 1
        // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (not public in the API 36 SDK).
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        var instance: TimerService? = null
            private set

        // True while a timer runs; the config screen observes it.
        var running by mutableStateOf(false)
            private set

        // Same as the notification's Cancel action. Always clears the notification,
        // whether or not the service is still alive.
        fun stop(c: Context) {
            c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            instance?.finish() ?: run { running = false }
        }
    }
}
