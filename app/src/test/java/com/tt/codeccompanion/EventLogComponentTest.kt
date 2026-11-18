package com.tt.codeccompanion

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.codeccompanion.TestSupport.ADDRESS
import com.tt.codeccompanion.TestSupport.context
import com.tt.codeccompanion.TestSupport.flushLog
import com.tt.codeccompanion.TestSupport.setUserUnlocked
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch

/** K7 of the test concept: the log's file, its structure checks, its repairs and its background loading. */
@RunWith(AndroidJUnit4::class)
class EventLogComponentTest {
    private val file get() = File(File(context.filesDir, EventLog.DIRECTORY), EventLog.FILE_NAME)
    private val log get() = EventLog.get(context)

    @Before
    fun setUp() {
        // The structure checks only throw in debuggable builds
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        EventLog.resetForTest(context)
    }

    private fun lines(): List<String> {
        flushLog()
        return file.readLines().filter { it.isNotBlank() }
    }

    private fun writeFile(vararg events: LogEvent, partial: String? = null) {
        file.parentFile!!.mkdirs()
        file.writeText(events.joinToString("") { EventLog.json.encodeToString(it) + "\n" } + (partial ?: ""))
    }

    private fun event(seq: Long, type: EventType, opUID: String? = null) =
        LogEvent(LogEvent.VERSION, seq, 1000 * seq, type, opUID = opUID, device = LogDevice(ADDRESS))

    /** Holds the log's background thread until the returned latch is counted down. */
    private fun holdBackgroundThread(): CountDownLatch {
        val release = CountDownLatch(1)
        log.flush { release.await() }
        return release
    }

    /**
     * Starts the process again with [events] in the file, holding the background thread before the
     * load. The next use of the log queues the load behind it.
     */
    private fun restartWithHeldLoad(vararg events: LogEvent): CountDownLatch {
        writeFile(*events)
        EventLog.restartForTest()
        // Locked, so the new log doesn't queue the load before the hold
        setUserUnlocked(false)
        val release = holdBackgroundThread()
        setUserUnlocked(true)
        return release
    }

    @Test
    fun structureViolationsThrow() {
        assertThrows(IllegalStateException::class.java) { log.log(EventType.A2DP_CONNECTED) }
        assertThrows(IllegalStateException::class.java) { log.begin(EventType.CODEC_VERIFIED, null) }
        val operation = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        assertThrows(IllegalStateException::class.java) { operation.log(EventType.CODEC_VERIFIED) }
        operation.resolve(EventType.CODEC_VERIFIED)
        assertThrows(IllegalStateException::class.java) { operation.resolve(EventType.ERROR) }
        assertThrows(IllegalStateException::class.java) { operation.log(EventType.SET_CODEC_CONFIG) }
    }

    @Test
    fun appendsUntilTwiceTheLimitThenRewrites() {
        repeat(2 * EventLog.MAX_EVENTS - 1) { log.log(EventType.TEST_LABEL, message = "$it") }
        assertEquals(2 * EventLog.MAX_EVENTS - 1, lines().size)
        assertEquals(EventLog.MAX_EVENTS, TestSupport.events.size)
        log.log(EventType.TEST_LABEL, message = "last")
        val lines = lines()
        assertEquals(EventLog.MAX_EVENTS, lines.size)
        assertEquals(TestSupport.events, EventLog.parse(lines))
        assertEquals(2L * EventLog.MAX_EVENTS - 1, TestSupport.events.last().seq)
    }

    @Test
    fun loadingResolvesInterruptedOperations() {
        writeFile(
            event(0, EventType.A2DP_CONNECTED, "done"),
            event(1, EventType.CODEC_VERIFIED, "done"),
            event(2, EventType.USER_SET_CODEC, "open"),
            event(3, EventType.SET_CODEC_CONFIG, "open"),
        )
        val last = TestSupport.events.last()
        assertEquals(EventType.OPERATION_INTERRUPTED, last.type)
        assertEquals("open", last.opUID)
        assertEquals(4L, last.seq)
        assertEquals(LogDevice(ADDRESS), last.device)
        assertEquals(5, lines().size)
        // The next event continues the sequence
        log.log(EventType.TEST_LABEL, message = "next")
        assertEquals(5L, TestSupport.events.last().seq)
    }

    @Test
    fun partialLinesAreDroppedAndTheFileRewritten() {
        writeFile(event(0, EventType.DEVICE_DISAPPEARED), event(1, EventType.DEVICE_DISAPPEARED), partial = """{"v":1,"seq":2,"ti""")
        assertEquals(2, TestSupport.events.size)
        assertEquals(2, lines().size)
        assertEquals(TestSupport.events, EventLog.parse(lines()))
    }

    @Test
    fun clearKeepsOpenOperations() {
        val open = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        val done = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        done.resolve(EventType.CODEC_VERIFIED)
        log.log(EventType.TEST_LABEL, message = "x")
        log.clear()
        assertEquals(listOf(open.uid), TestSupport.events.map { it.opUID })
        open.resolve(EventType.CODEC_VERIFIED)
        assertEquals(2, EventLog.parse(lines()).size)
    }

    @Test
    fun disablingDeletesTheLogAndStopsLogging() {
        val open = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        log.log(EventType.TEST_LABEL, message = "x")
        log.setEnabled(false)
        assertEquals(emptyList<LogEvent>(), TestSupport.events)
        open.resolve(EventType.CODEC_VERIFIED)
        log.log(EventType.TEST_LABEL, message = "y")
        val whileDisabled = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        assertEquals(emptyList<LogEvent>(), TestSupport.events)
        flushLog()
        assertFalse(file.exists())
        // The events of an operation begun while disabled stay out of the log after enabling it
        log.setEnabled(true)
        log.log(EventType.TEST_LABEL, message = "z")
        whileDisabled.resolve(EventType.CODEC_VERIFIED)
        assertEquals(listOf(EventType.TEST_LABEL), TestSupport.events.map { it.type })
        assertEquals(1, lines().size)
    }

    @Test
    fun operationsSpanningADisabledPeriodStayOutOfTheLog() {
        val before = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        log.setEnabled(false)
        val during = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        log.setEnabled(true)
        before.log(EventType.SET_CODEC_CONFIG)
        during.log(EventType.SET_CODEC_CONFIG)
        before.resolve(EventType.CODEC_VERIFIED)
        during.resolve(EventType.CODEC_VERIFIED)
        assertEquals(emptyList<LogEvent>(), TestSupport.events)
        // An operation begun after enabling is logged again
        val after = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        after.log(EventType.SET_CODEC_CONFIG)
        after.resolve(EventType.CODEC_VERIFIED)
        assertEquals(listOf(after.uid, after.uid, after.uid), TestSupport.events.map { it.opUID })
        assertEquals(3, lines().size)
    }

    @Test
    fun loggingStaysDisabledAfterARestart() {
        log.setEnabled(false)
        EventLog.restartForTest()
        assertFalse(log.enabled.value)
        log.log(EventType.TEST_LABEL, message = "x")
        assertEquals(emptyList<LogEvent>(), TestSupport.events)
        flushLog()
        assertFalse(file.exists())
        log.setEnabled(true)
        EventLog.restartForTest()
        assertTrue(log.enabled.value)
    }

    @Test
    fun aFileLeftWhileDisabledIsDeletedOnLoad() {
        log.setEnabled(false)
        EventLog.restartForTest()
        // The process died before the background thread deleted it
        writeFile(event(0, EventType.TEST_LABEL))
        assertEquals(emptyList<LogEvent>(), TestSupport.events)
        assertFalse(file.exists())
    }

    @Test
    fun flushRunsAfterTheWritesBeforeIt() {
        repeat(10) { log.log(EventType.TEST_LABEL, message = "$it") }
        var seen = 0
        val read = java.util.concurrent.CountDownLatch(1)
        log.flush {
            seen = file.readLines().count { it.isNotBlank() }
            read.countDown()
        }
        read.await()
        assertEquals(10, seen)
    }

    @Test
    fun eventsLoggedWhileLoadingFollowTheLoadedOnes() {
        val release = restartWithHeldLoad(event(0, EventType.TEST_LABEL), event(1, EventType.USER_SET_CODEC, "old"))
        val flow = log.events
        log.log(EventType.TEST_LABEL, message = "a")
        val operation = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        assertNull(flow.value)
        release.countDown()
        val events = TestSupport.events
        // The operation the old process left open is interrupted, the one begun while loading isn't
        assertEquals(
            listOf(EventType.TEST_LABEL, EventType.USER_SET_CODEC, EventType.OPERATION_INTERRUPTED,
                EventType.TEST_LABEL, EventType.USER_SET_CODEC),
            events.map { it.type })
        assertEquals(listOf(null, "old", "old", null, operation.uid), events.map { it.opUID })
        assertEquals((0L..4L).toList(), events.map { it.seq })
        assertEquals(events, EventLog.parse(lines()))
        assertTrue(operation.isOpen)
    }

    @Test
    fun clearingWhileLoadingClearsTheLoadedEvents() {
        val release = restartWithHeldLoad(event(0, EventType.TEST_LABEL), event(1, EventType.TEST_LABEL))
        val operation = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        log.log(EventType.TEST_LABEL, message = "a")
        log.clear()
        log.log(EventType.TEST_LABEL, message = "b")
        release.countDown()
        val events = TestSupport.events
        assertEquals(listOf(operation.uid, null), events.map { it.opUID })
        assertEquals(listOf(null, "b"), events.map { it.message })
        assertEquals(events, EventLog.parse(lines()))
    }

    @Test
    fun disablingWhileLoadingDeletesTheLoadedEvents() {
        val release = restartWithHeldLoad(event(0, EventType.TEST_LABEL))
        log.log(EventType.TEST_LABEL, message = "a")
        log.setEnabled(false)
        release.countDown()
        assertEquals(emptyList<LogEvent>(), TestSupport.events)
        assertFalse(file.exists())
    }

    @Test
    fun awaitWrittenWaitsForTheBackgroundThread() {
        EventLog.restartForTest()
        // Nothing to wait for without a log in the process
        assertTrue(EventLog.awaitWritten(0))
        log.log(EventType.TEST_LABEL, message = "a")
        val release = holdBackgroundThread()
        assertFalse(EventLog.awaitWritten(100))
        release.countDown()
        assertTrue(EventLog.awaitWritten(5000))
        assertEquals(1, lines().size)
    }
}
