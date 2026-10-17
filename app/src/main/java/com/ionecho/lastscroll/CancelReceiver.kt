package com.ionecho.lastscroll

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// The notification's Stop Timer action and swipe-away. A broadcast needs no service start.
class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) = TimerService.stop(c)
}
