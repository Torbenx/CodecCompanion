package com.tt.autobluetoothcodec

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.UserManager
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.flushLog
import com.tt.autobluetoothcodec.TestSupport.runPending
import com.tt.autobluetoothcodec.TestSupport.sbc
import com.tt.autobluetoothcodec.TestSupport.setUserUnlocked
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUserManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * K9 of the test concept: the log and the settings before the user unlocks the phone after a
 * reboot. Robolectric only reports the user as locked, it doesn't keep the app from reading
 * credential protected storage, so these tests check which file is used.
 */
@RunWith(AndroidJUnit4::class)
class DirectBootTest {
    private val deviceContext get() = context.createDeviceProtectedStorageContext()
    private val upsFile get() = File(File(context.filesDir, EventLog.DIRECTORY), EventLog.FILE_NAME)
    private val dpsFile get() = File(File(deviceContext.filesDir, EventLog.DIRECTORY), EventLog.FILE_NAME)
    private val log get() = EventLog.get(context)

    @Before
    fun setUp() {
        // The structure checks only throw in debuggable builds
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        UnlockingUserManager.checksBeforeUnlock = -1
        setUserUnlocked(true)
        EventLog.resetForTest(context)
        SavedDeviceStore.clearForTest(context)
    }

    /** Starts the process again before the user unlocks, as after a reboot. */
    private fun restartLocked() {
        setUserUnlocked(false)
        EventLog.restartForTest()
    }

    private fun lines(file: File): List<String> {
        flushLog()
        return if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    private fun writeUps(vararg events: LogEvent) {
        upsFile.parentFile!!.mkdirs()
        upsFile.writeText(events.joinToString("") { EventLog.json.encodeToString(it) + "\n" })
    }

    private fun event(seq: Long, type: EventType, opUID: String? = null, message: String? = null) =
        LogEvent(LogEvent.VERSION, seq, 1000 * seq, type, opUID = opUID, device = LogDevice(ADDRESS), message = message)

    private fun unlockReceivers() =
        shadowOf(context).registeredReceivers.count { it.intentFilter.hasAction(Intent.ACTION_USER_UNLOCKED) }

    /** Asserts that the events are numbered without gaps, from [first] on. */
    private fun assertSequence(events: List<LogEvent>, first: Long = 0) {
        assertEquals((first until first + events.size).toList(), events.map { it.seq })
    }

    @Test
    fun eventsLoggedWhileLockedGoToTheDeviceProtectedFile() {
        writeUps(event(0, EventType.TEST_LABEL, message = "old"))
        val before = upsFile.readText()
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        log.begin(EventType.A2DP_CONNECTED, LogDevice(ADDRESS))
        val dpsEvents = EventLog.parse(lines(dpsFile))
        assertEquals(listOf(EventType.TEST_LABEL, EventType.A2DP_CONNECTED), dpsEvents.map { it.type })
        assertEquals(before, upsFile.readText())
    }

    @Test
    fun eventsCantBeReadWhileLocked() {
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        assertThrows(IllegalStateException::class.java) { log.events }
        assertThrows(IllegalStateException::class.java) { log.clear() }
        // Clearing didn't drop the locked event
        setUserUnlocked(true)
        assertEquals(listOf("a"), TestSupport.events.map { it.message })
    }

    @Test
    fun unlockingMergesTheLockedEventsAfterTheOldOnes() {
        writeUps(event(0, EventType.A2DP_CONNECTED, "old"), event(1, EventType.CODEC_VERIFIED, "old"))
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        val operation = log.begin(EventType.A2DP_CONNECTED, LogDevice(ADDRESS))
        operation.resolve(EventType.CODEC_VERIFIED)
        assertEquals(3, lines(dpsFile).size)

        setUserUnlocked(true)
        val events = TestSupport.events
        assertEquals(
            listOf(EventType.A2DP_CONNECTED, EventType.CODEC_VERIFIED, EventType.TEST_LABEL,
                EventType.A2DP_CONNECTED, EventType.CODEC_VERIFIED),
            events.map { it.type })
        assertEquals(listOf("old", "old", null, operation.uid, operation.uid), events.map { it.opUID })
        assertSequence(events)
        assertEquals(events, EventLog.parse(lines(upsFile)))
        assertFalse(dpsFile.exists())
    }

    @Test
    fun theEventThatNoticesTheUnlockContinuesTheSequence() {
        writeUps(*Array(5) { event(it.toLong(), EventType.TEST_LABEL, message = "old") })
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        log.log(EventType.TEST_LABEL, message = "b")
        setUserUnlocked(true)
        // Merges the locked events before it takes its own number
        log.log(EventType.TEST_LABEL, message = "c")
        val events = TestSupport.events
        assertEquals(listOf("old", "old", "old", "old", "old", "a", "b", "c"), events.map { it.message })
        assertSequence(events)
        assertEquals(events, EventLog.parse(lines(upsFile)))
    }

    @Test
    fun readingTheEventsNoticesTheUnlock() {
        restartLocked()
        log.log(EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE, LogDevice(ADDRESS), actual = LogCodecConfig.of(sbc()))
        setUserUnlocked(true)
        assertEquals(sbc().toAndroid(), TestSupport.events.single().actual!!.android)
        flushLog()
        assertFalse(dpsFile.exists())
        assertEquals(1, lines(upsFile).size)
    }

    @Test
    fun theUnlockBroadcastMergesTheLockedEvents() {
        writeUps(event(0, EventType.TEST_LABEL, message = "old"))
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        assertEquals(1, unlockReceivers())
        setUserUnlocked(true)
        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        runPending()
        assertEquals(0, unlockReceivers())
        // Merged without another event or read
        assertEquals(listOf("old", "a"), EventLog.parse(lines(upsFile)).map { it.message })
        assertFalse(dpsFile.exists())
    }

    @Test
    @Config(shadows = [UnlockingUserManager::class])
    fun anUnlockBeforeTheReceiverIsRegisteredIsNoticed() {
        writeUps(event(0, EventType.TEST_LABEL, message = "old"))
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        // The next process starts locked, and the user unlocks between the log's first check and the registration of
        // its receiver, which misses the broadcast
        EventLog.restartForTest()
        UnlockingUserManager.checksBeforeUnlock = 1
        val log = EventLog.get(context)
        assertTrue(log.isUserStorageInitialized())
        assertEquals(0, unlockReceivers())
        // Merged without a broadcast, another event or a read
        assertEquals(listOf("old", "a"), EventLog.parse(lines(upsFile)).map { it.message })
        assertFalse(dpsFile.exists())
    }

    @Test
    fun anEventThatNoticesTheUnlockUnregistersTheReceiver() {
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        assertEquals(1, unlockReceivers())
        setUserUnlocked(true)
        log.log(EventType.TEST_LABEL, message = "b")
        assertEquals(0, unlockReceivers())
    }

    @Test
    fun noUnlockReceiverWhileUnlocked() {
        log.log(EventType.TEST_LABEL, message = "a")
        assertEquals(0, unlockReceivers())
    }

    @Test
    fun theLockedEventsStayUntilTheMergedLogIsWritten() {
        dpsFile.parentFile!!.mkdirs()
        dpsFile.writeText(EventLog.json.encodeToString(event(0, EventType.A2DP_CONNECTED, "op")) + "\n")
        var full = true
        val ups = object : AtomicFile(upsFile) {
            override fun startWrite(): FileOutputStream =
                if (full) throw IOException("no space left") else super.startWrite()
        }
        val published = MutableStateFlow<List<LogEvent>?>(null)
        val store = EventStore(ups, dpsFile, published)
        // On the background thread in the app, the store itself doesn't care
        store.load(openOperations = setOf("op"), enabled = true)
        assertEquals(listOf("op"), published.value!!.map { it.opUID })
        assertTrue(dpsFile.exists())
        // The next rewrite writes the merged event, then the device protected file goes
        full = false
        store.clear(keptOperations = setOf("op"))
        assertEquals(listOf("op"), EventLog.parse(upsFile.readLines()).map { it.opUID })
        assertFalse(dpsFile.exists())
    }

    @Test
    fun anOperationBegunWhileLockedContinuesAfterTheUnlock() {
        restartLocked()
        val operation = log.begin(EventType.A2DP_CONNECTED, LogDevice(ADDRESS))
        operation.log(EventType.SET_CODEC_CONFIG)
        setUserUnlocked(true)
        assertTrue(operation.isOpen)
        operation.resolve(EventType.CODEC_VERIFIED)
        val events = TestSupport.events
        assertEquals(
            listOf(EventType.A2DP_CONNECTED, EventType.SET_CODEC_CONFIG, EventType.CODEC_VERIFIED),
            events.map { it.type })
        assertEquals(listOf(operation.uid, operation.uid, operation.uid), events.map { it.opUID })
        assertSequence(events)
        assertEquals(events, EventLog.parse(lines(upsFile)))
    }

    @Test
    fun operationsLeftOpenByAnEarlierLockedProcessAreInterrupted() {
        restartLocked()
        val dead = log.begin(EventType.A2DP_CONNECTED, LogDevice(ADDRESS))
        // The process dies before the user unlocks, the next one starts locked too
        EventLog.restartForTest()
        val alive = log.begin(EventType.A2DP_CONNECTED, LogDevice(ADDRESS))
        setUserUnlocked(true)
        val events = TestSupport.events
        assertEquals(
            listOf(EventType.A2DP_CONNECTED, EventType.A2DP_CONNECTED, EventType.OPERATION_INTERRUPTED),
            events.map { it.type })
        assertEquals(listOf(dead.uid, alive.uid, dead.uid), events.map { it.opUID })
        assertSequence(events)
        // The operation of this process isn't interrupted
        alive.resolve(EventType.CODEC_VERIFIED)
        assertEquals(EventType.CODEC_VERIFIED, TestSupport.events.last().type)
        assertEquals(alive.uid, TestSupport.events.last().opUID)
        assertEquals(TestSupport.events, EventLog.parse(lines(upsFile)))
    }

    @Test
    fun operationsLeftOpenInTheOldLogAreInterruptedAfterTheLockedEvents() {
        writeUps(event(0, EventType.USER_SET_CODEC, "open"), event(1, EventType.SET_CODEC_CONFIG, "open"))
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        setUserUnlocked(true)
        val events = TestSupport.events
        assertEquals(
            listOf(EventType.USER_SET_CODEC, EventType.SET_CODEC_CONFIG, EventType.TEST_LABEL,
                EventType.OPERATION_INTERRUPTED),
            events.map { it.type })
        assertEquals("open", events.last().opUID)
        assertEquals(LogDevice(ADDRESS), events.last().device)
        assertSequence(events)
        assertEquals(events, EventLog.parse(lines(upsFile)))
    }

    @Test
    fun eventsOfSeveralLockedProcessesAreKeptInOrder() {
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        EventLog.restartForTest()
        log.log(EventType.TEST_LABEL, message = "b")
        EventLog.restartForTest()
        log.log(EventType.TEST_LABEL, message = "c")
        setUserUnlocked(true)
        assertEquals(listOf("a", "b", "c"), TestSupport.events.map { it.message })
        assertSequence(TestSupport.events)
    }

    @Test
    fun aProcessStartedAfterTheUnlockMergesTheLockedEvents() {
        writeUps(event(0, EventType.TEST_LABEL, message = "old"))
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        // The process dies before the user unlocks, the next one starts after
        setUserUnlocked(true)
        EventLog.restartForTest()
        assertEquals(listOf("old", "a"), TestSupport.events.map { it.message })
        assertSequence(TestSupport.events)
        flushLog()
        assertFalse(dpsFile.exists())
        assertEquals(TestSupport.events, EventLog.parse(lines(upsFile)))
    }

    @Test
    fun lockedEventsAreTrimmedWithTheOldOnes() {
        writeUps(*Array(EventLog.MAX_EVENTS) { event(it.toLong(), EventType.TEST_LABEL, message = "old") })
        restartLocked()
        repeat(5) { log.log(EventType.TEST_LABEL, message = "$it") }
        setUserUnlocked(true)
        val events = TestSupport.events
        assertEquals(EventLog.MAX_EVENTS, events.size)
        assertEquals(listOf("0", "1", "2", "3", "4"), events.takeLast(5).map { it.message })
        assertSequence(events, first = 5)
        assertEquals(events, EventLog.parse(lines(upsFile)))
    }

    @Test
    fun aPartialLineInTheDeviceProtectedFileIsDropped() {
        restartLocked()
        log.log(EventType.TEST_LABEL, message = "a")
        flushLog()
        // The process died while appending, the next one starts locked
        dpsFile.appendText("""{"v":1,"seq":1,"ti""")
        EventLog.restartForTest()
        log.log(EventType.TEST_LABEL, message = "b")
        setUserUnlocked(true)
        assertEquals(listOf("a", "b"), TestSupport.events.map { it.message })
        assertSequence(TestSupport.events)
        assertEquals(TestSupport.events, EventLog.parse(lines(upsFile)))
    }

    @Test
    fun disabledLoggingIsKnownWhileLocked() {
        log.setEnabled(false)
        restartLocked()
        assertFalse(log.enabled.value)
        log.log(EventType.TEST_LABEL, message = "a")
        flushLog()
        assertFalse(dpsFile.exists())
        setUserUnlocked(true)
        assertEquals(emptyList<LogEvent>(), TestSupport.events)
        assertFalse(upsFile.exists())
    }

    @Test
    fun aDeviceProtectedFileLeftWhileDisabledIsDeleted() {
        log.setEnabled(false)
        restartLocked()
        // The process died before the background thread deleted it
        dpsFile.parentFile!!.mkdirs()
        dpsFile.writeText(EventLog.json.encodeToString(event(0, EventType.TEST_LABEL)) + "\n")
        EventLog.restartForTest()
        log.log(EventType.TEST_LABEL, message = "a")
        flushLog()
        assertFalse(dpsFile.exists())
    }

    @Test
    fun disablingWhileLockedDeletesTheLockedEvents() {
        writeUps(event(0, EventType.TEST_LABEL, message = "old"))
        restartLocked()
        val operation = log.begin(EventType.A2DP_CONNECTED, LogDevice(ADDRESS))
        log.setEnabled(false)
        flushLog()
        assertFalse(dpsFile.exists())
        setUserUnlocked(true)
        operation.resolve(EventType.CODEC_VERIFIED)
        assertEquals(emptyList<LogEvent>(), TestSupport.events)
        flushLog()
        assertFalse(upsFile.exists())
    }

    @Test
    fun clearingAfterTheUnlockKeepsOperationsBegunWhileLocked() {
        restartLocked()
        val open = log.begin(EventType.A2DP_CONNECTED, LogDevice(ADDRESS))
        log.log(EventType.TEST_LABEL, message = "a")
        setUserUnlocked(true)
        log.clear()
        assertEquals(listOf(open.uid), TestSupport.events.map { it.opUID })
        open.resolve(EventType.CODEC_VERIFIED)
        assertEquals(listOf(open.uid, open.uid), EventLog.parse(lines(upsFile)).map { it.opUID })
    }

    @Test
    fun theLoggingSettingIsInDeviceProtectedStorage() {
        log.setEnabled(false)
        assertFalse(deviceContext.getSharedPreferences("event_log", Context.MODE_PRIVATE).getBoolean("enabled", true))
        assertFalse(context.getSharedPreferences("event_log", Context.MODE_PRIVATE).contains("enabled"))
    }

    @Test
    fun preferredCodecsAreInDeviceProtectedStorage() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        assertNotNull(deviceContext.getSharedPreferences("preferred_codecs", Context.MODE_PRIVATE).getString(ADDRESS, null))
        assertNull(context.getSharedPreferences("preferred_codecs", Context.MODE_PRIVATE).getString(ADDRESS, null))
        setUserUnlocked(false)
        assertEquals(sbc(), SavedDeviceStore.getPreferred(context, device()))
    }
}

/**
 * Unlocks the user once [checksBeforeUnlock] checks of whether it's unlocked have reported it locked, without sending
 * the unlock broadcast, like an unlock whose broadcast was sent before a receiver was registered.
 */
@Implements(UserManager::class)
class UnlockingUserManager : ShadowUserManager() {
    companion object {
        // Negative while it doesn't unlock
        var checksBeforeUnlock = -1
    }

    @Implementation
    override fun isUserUnlocked(): Boolean {
        if (checksBeforeUnlock == 0)
            setUserUnlocked(true)
        if (checksBeforeUnlock >= 0)
            checksBeforeUnlock -= 1
        return super.isUserUnlocked()
    }
}
