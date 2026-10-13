package com.ionecho.lastscroll

import kotlin.math.ceil

// Timer state math. Times are SystemClock.elapsedRealtime() millis, which keeps
// counting in deep sleep. The deadline is the truth while running, remaining while paused.
class Countdown {
    var paused = false
        private set
    private var deadline = 0L
    private var remaining = 0L

    fun left(now: Long): Long = if (paused) remaining else maxOf(0L, deadline - now)

    fun minutes(now: Long): Int = ceil(left(now) / 60_000.0).toInt()

    fun expired(now: Long): Boolean = !paused && now >= deadline

    fun set(now: Long, ms: Long) {
        if (paused) remaining = ms else deadline = now + ms
    }

    // Returns false if already paused.
    fun pause(now: Long): Boolean {
        if (paused) return false
        remaining = left(now)
        paused = true
        return true
    }

    // Returns false if already running.
    fun resume(now: Long): Boolean {
        if (!paused) return false
        deadline = now + remaining
        paused = false
        return true
    }

    // Delay until the minutes shown next drop, or until the deadline.
    fun nextTick(now: Long): Long {
        val left = left(now)
        return if (left <= 0L) 0L else (left - 1) % 60_000L + 1
    }
}
