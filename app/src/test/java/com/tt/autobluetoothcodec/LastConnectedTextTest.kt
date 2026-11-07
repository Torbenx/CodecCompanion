package com.tt.autobluetoothcodec

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/** When a saved device was last connected, in English like the rest of the app. Robolectric's locale is en-US. */
@RunWith(AndroidJUnit4::class)
class LastConnectedTextTest {
    // Far enough ahead that Robolectric's clock, which only moves forward, can be set to it
    private val now = ZonedDateTime.of(2030, 6, 15, 12, 0, 0, 0, ZoneId.systemDefault())

    // ICU puts a narrow no-break space before AM and PM
    private fun text(time: ZonedDateTime) = lastConnectedText(context, time.toInstant().toEpochMilli()).replace('\u202f', ' ')

    @Before
    fun setUp() {
        assertTrue(SystemClock.setCurrentTimeMillis(now.toInstant().toEpochMilli()))
    }

    @Test
    fun theLastDaysAreRelative() {
        assertEquals("Yesterday, 6:42 PM", text(now.minusDays(1).withHour(18).withMinute(42)))
        assertEquals("3 days ago, 9:05 AM", text(now.minusDays(3).withHour(9).withMinute(5)))
    }

    @Test
    fun olderOnesHaveTheirDate() {
        assertEquals("May 10, 6:42 PM", text(now.minusDays(36).withHour(18).withMinute(42)))
        // Another year in numbers
        assertEquals("6/10/2029, 6:42 PM", text(now.minusDays(370).withHour(18).withMinute(42)))
    }
}
