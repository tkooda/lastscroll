package com.ionecho.lastscroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import java.util.Date
import kotlin.math.ceil

class TimerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { onExpire() }
    private val toastTick = Runnable { onToast() }
    private var deadline = 0L
    private var remaining = 0L
    private var paused = false
    private var toastMin = 0
    private var lastPost = 0L
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
            startForeground(NOTIF_ID, buildNotification())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        handler.removeCallbacks(toastTick)
        instance = null
        super.onDestroy()
    }

    fun reset() {
        val full = MainActivity.minutes(this) * 60_000L
        if (paused) remaining = full else schedule(System.currentTimeMillis() + full)
        post(false)
    }

    fun pause(pkg: String) {
        if (paused) return
        paused = true
        remaining = deadline - System.currentTimeMillis()
        handler.removeCallbacks(expire)
        handler.removeCallbacks(toastTick)
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
    }

    private fun onToast() {
        Toast.makeText(this, getString(R.string.toast, toastMin), Toast.LENGTH_SHORT).show()
        toastMin--
        if (toastMin >= 1) handler.postDelayed(toastTick, 60_000L)
    }

    // Repost the notification, at most every few seconds unless forced.
    private fun post(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPost < 5_000L) return
        lastPost = now
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
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
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
        if (paused) {
            val min = ceil(remaining / 60_000.0).toInt()
            b.setContentText(getString(R.string.notif_paused, min)).setShowWhen(false)
        } else {
            val time = DateFormat.getTimeFormat(this).format(Date(deadline))
            b.setContentText(getString(R.string.notif_text, time))
                .setWhen(deadline)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        return b.build()
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

        var instance: TimerService? = null
            private set
    }
}
