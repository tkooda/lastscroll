package com.ionecho.lastscroll

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, TimerService::class.java))
        finish()
    }
}
