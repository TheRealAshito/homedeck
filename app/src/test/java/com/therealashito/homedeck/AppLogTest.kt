package com.therealashito.homedeck

import com.therealashito.homedeck.debug.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {

    @Test
    fun ringBufferCapsAtMaxAndKeepsNewest() {
        AppLog.clear()
        for (i in 1..600) AppLog.d("T", "msg $i")
        val entries = AppLog.snapshot()
        assertEquals(500, entries.size)
        assertEquals("msg 101", entries.first().message)
        assertEquals("msg 600", entries.last().message)
        AppLog.clear()
    }

    @Test
    fun tailFormatsLevelTagAndMessage() {
        AppLog.clear()
        AppLog.i("App", "hello")
        AppLog.e("Crash", "boom")
        val tail = AppLog.tail(10)
        assertTrue(tail.contains("I/App: hello"))
        assertTrue(tail.contains("E/Crash: boom"))
        AppLog.clear()
    }

    @Test
    fun clearEmptiesBuffer() {
        AppLog.d("T", "gone")
        AppLog.clear()
        assertEquals(0, AppLog.snapshot().size)
    }
}
