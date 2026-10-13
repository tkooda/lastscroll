package com.ionecho.lastscroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownTest {
    private val min = 60_000L

    @Test
    fun countsDownFromDeadline() {
        val c = Countdown()
        c.set(1_000L, 2 * min)
        assertEquals(2, c.minutes(1_000L))
        assertEquals(min, c.nextTick(1_000L))
        assertEquals(1, c.minutes(1_000L + min))
        assertEquals(min, c.nextTick(1_000L + min))
        assertFalse(c.expired(1_000L + 2 * min - 1))
        assertTrue(c.expired(1_000L + 2 * min))
        assertEquals(0, c.minutes(1_000L + 3 * min))
    }

    @Test
    fun pauseFreezesAndResumeContinues() {
        val c = Countdown()
        c.set(0L, 5 * min)
        assertTrue(c.pause(90_000L))
        assertFalse(c.pause(500_000L))
        assertEquals(5 * min - 90_000L, c.left(10 * min))
        assertFalse(c.expired(100 * min))
        assertTrue(c.resume(100 * min))
        assertFalse(c.resume(101 * min))
        assertEquals(100 * min + 5 * min - 90_000L, 100 * min + c.left(100 * min))
        assertTrue(c.expired(100 * min + 5 * min - 90_000L))
    }

    @Test
    fun resetWhilePausedSetsRemaining() {
        val c = Countdown()
        c.pause(0L)
        c.set(0L, 3 * min)
        assertEquals(3, c.minutes(99 * min))
        c.resume(10 * min)
        assertTrue(c.expired(13 * min))
    }
}
