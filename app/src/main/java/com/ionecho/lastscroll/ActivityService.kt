package com.ionecho.lastscroll

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

class ActivityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastReset = 0L

    // Last app (or launcher) whose window came to the front, ignoring the shade, keyboards and us.
    var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        instance = null
        TimerService.instance?.resume("accessibility off")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg == packageName || pkg == "com.android.systemui" || isInputMethod(pkg)) return
            val selected = pkg in MainActivity.selected(this)
            // Only a full-screen activity of another app (or the home screen) pauses; ignore
            // overlays, dialogs, toasts and windows of packages we cannot see.
            if (!selected && !isActivity(pkg, event.className)) {
                if (TimerService.instance != null) Log.d(TimerService.TAG, "ignore: $pkg ${event.className}")
                return
            }
            foregroundPackage = pkg
            val timer = TimerService.instance ?: return
            if (selected) timer.resume(pkg) else timer.pause(pkg)
            return
        }
        if (TimerService.instance == null || pkg !in MainActivity.selected(this)) return
        reset(AccessibilityEvent.eventTypeToString(event.eventType), pkg)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if ((event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event.action == KeyEvent.ACTION_DOWN && TimerService.instance != null
        ) {
            reset(KeyEvent.keyCodeToString(event.keyCode), foregroundPackage ?: "")
        }
        return false
    }

    private fun reset(type: String, pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastReset < 500L) return
        lastReset = now
        Log.d(TimerService.TAG, "reset: $type $pkg")
        TimerService.instance?.reset()
    }

    private fun isActivity(pkg: String, cls: CharSequence?): Boolean = cls != null && try {
        packageManager.getActivityInfo(ComponentName(pkg, cls.toString()), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun isInputMethod(pkg: String): Boolean =
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == pkg }

    fun sleepNow() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }, 500L)
    }

    companion object {
        var instance: ActivityService? = null
            private set
    }
}
