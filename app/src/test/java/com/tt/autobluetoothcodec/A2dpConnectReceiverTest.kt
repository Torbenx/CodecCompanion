package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.events
import com.tt.autobluetoothcodec.TestSupport.resetApp
import com.tt.autobluetoothcodec.TestSupport.resolution
import com.tt.autobluetoothcodec.TestSupport.runFor
import com.tt.autobluetoothcodec.TestSupport.VERIFY_MILLIS
import com.tt.autobluetoothcodec.TestSupport.sbc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.util.concurrent.TimeUnit

/** K4 of the test concept: the manifest receiver that applies the preferred codec up to API 30. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class A2dpConnectReceiverTest {
    private val a2dp = FakeA2dp()
    private val receiver = A2dpConnectReceiver()

    @Before
    fun setUp() {
        resetApp(a2dp)
        a2dp.connected.add(ADDRESS)
    }

    private fun intent(
        state: Int = BluetoothProfile.STATE_CONNECTED,
        action: String = BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
        previous: Int = BluetoothProfile.STATE_CONNECTING,
    ) =
        Intent(action)
            .putExtra(BluetoothProfile.EXTRA_STATE, state)
            .putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, previous)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device())

    /** Delivers [intent] like the system does, returns the broadcast's result for goAsync. */
    private fun deliver(intent: Intent): BroadcastReceiver.PendingResult {
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.java, String::class.java, Bundle::class.java, Boolean::class.java)
        create.isAccessible = true
        val result = create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
        // Hidden in the SDK, the system sets it before calling onReceive
        BroadcastReceiver::class.java.getMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
            .invoke(receiver, result)
        receiver.onReceive(context, intent)
        return result
    }

    private fun isFinished(result: BroadcastReceiver.PendingResult): Boolean {
        val future = Shadow.extract<ShadowBroadcastPendingResult>(result).future
        return try {
            future.get(5, TimeUnit.SECONDS)
            true
        } catch (e: java.util.concurrent.TimeoutException) {
            false
        }
    }

    private val operationUid get() = events.single { it.type == EventType.A2DP_CONNECTED }.opUID!!

    @Test
    fun ignoresOtherBroadcasts() {
        receiver.onReceive(context, intent(action = BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED))
        receiver.onReceive(context, intent(state = BluetoothProfile.STATE_CONNECTING))
        receiver.onReceive(context, intent(state = BluetoothProfile.STATE_DISCONNECTED))
        // No preferred codec
        receiver.onReceive(context, intent())
        assertTrue(events.isEmpty())
        assertEquals(0, a2dp.connectCount)
    }

    @Test
    fun appliesThePreferredCodecAndFinishesAfterTheLog() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        val result = deliver(intent())
        runFor(VERIFY_MILLIS)
        assertEquals("manifest receiver", events.first().message)
        assertEquals(EventType.CODEC_VERIFIED, resolution(operationUid)!!.type)
        assertTrue(isFinished(result))
        // The deadline was removed
        runFor(20_000)
        assertEquals(EventType.CODEC_VERIFIED, resolution(operationUid)!!.type)
    }

    @Test
    fun givesUpAtTheDeadline() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        a2dp.connectsAutomatically = false
        val result = deliver(intent())
        runFor(14_000)
        assertFalse(isFinished(result))
        runFor(1_500)
        assertEquals(EventType.TIMEOUT, resolution(operationUid)!!.type)
        assertTrue(isFinished(result))
    }

    private fun lastConnected() = SavedDeviceStore.getInfo(context, device())?.lastConnected

    @Test
    fun notesWhenASavedDeviceConnectsAndDisconnects() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        deliver(intent())
        val connected = lastConnected()!!
        runFor(60_000)
        receiver.onReceive(context, intent(BluetoothProfile.STATE_DISCONNECTED, previous = BluetoothProfile.STATE_CONNECTED))
        assertTrue(lastConnected()!! > connected)
    }

    @Test
    fun aFailedConnectionAttemptIsntNoted() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        receiver.onReceive(context, intent(BluetoothProfile.STATE_DISCONNECTED, previous = BluetoothProfile.STATE_CONNECTING))
        assertNull(lastConnected())
    }

    @Test
    fun anExceptionResolvesError() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        a2dp.connectFailure = IllegalStateException("broken")
        val result = deliver(intent())
        assertEquals(EventType.ERROR, resolution(operationUid)!!.type)
        assertEquals("java.lang.IllegalStateException: broken", resolution(operationUid)!!.message)
        assertTrue(isFinished(result))
    }
}
