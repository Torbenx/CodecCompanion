package com.tt.autobluetoothcodec

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Looper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Helpers for the Robolectric component tests. */
object TestSupport {
    const val ADDRESS = "0A:1B:2C:3D:4E:5F"
    const val OTHER_ADDRESS = "AA:BB:CC:DD:EE:01"

    val context: Application get() = ApplicationProvider.getApplicationContext()

    fun device(address: String = ADDRESS): BluetoothDevice =
        context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)

    fun grantPermission() = shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    fun denyPermission() = shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    /** Locks or unlocks the user's credential protected storage, as before and after the first unlock after a reboot. */
    fun setUserUnlocked(unlocked: Boolean) =
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(unlocked)

    /** Resets the process-wide state and returns an applier that uses [a2dp]. */
    fun resetApp(a2dp: FakeA2dp): CodecApplier {
        // Robolectric's uptime starts at 100 ms, but the applier takes a request's first attempt at
        // 0 to be 500 ms in the past, like it is on a phone that has been running for a while
        runFor(60_000)
        setUserUnlocked(true)
        EventLog.resetForTest(context)
        CodecApplier.resetForTest(context, a2dp)
        SavedDeviceStore.clearForTest(context)
        return CodecApplier.get(context)
    }

    /** How long a codec that takes effect at once takes to be verified: two reads, 500 ms apart. */
    const val VERIFY_MILLIS = 1_100L

    /** Runs the main looper's tasks for [millis], advancing the clock. */
    fun runFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    fun runPending() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * The logged events, once the log's background thread has handled everything logged so far.
     * Reading them notices an unlock first, like reading [EventLog.events] does.
     */
    val events: List<LogEvent> get() {
        val flow = EventLog.get(context).events
        flushLog()
        return flow.value!!
    }

    /** The events of the operation [uid], in order. */
    fun operation(uid: String) = events.filter { it.opUID == uid }

    fun resolution(uid: String) = operation(uid).lastOrNull { it.type.flow == OperationFlow.RESOLUTION }

    /** Waits until the log's background writes are done. */
    fun flushLog() {
        val done = CountDownLatch(1)
        EventLog.get(context).flush { done.countDown() }
        check(done.await(5, TimeUnit.SECONDS)) { "the log didn't flush" }
    }

    fun sbc(sampleRate: Int = CodecConfig.SAMPLE_RATE_44100) = SBCCodecConfig().also {
        it.sampleRate = sampleRate
        it.bitsPerSample = CodecConfig.BITS_PER_SAMPLE_16
    }

    fun ldac(bitrate: Int = LDACCodecConfig.BITRATE_SYSTEM_DEFAULT) = LDACCodecConfig().also {
        it.sampleRate = CodecConfig.SAMPLE_RATE_96000
        it.bitsPerSample = CodecConfig.BITS_PER_SAMPLE_24
        it.bitrate = bitrate
    }
}
