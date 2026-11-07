package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.companion.CompanionDeviceManager
import android.companion.DevicePresenceEvent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.OTHER_ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.events
import com.tt.autobluetoothcodec.TestSupport.grantPermission
import com.tt.autobluetoothcodec.TestSupport.resetApp
import com.tt.autobluetoothcodec.TestSupport.resolution
import com.tt.autobluetoothcodec.TestSupport.runFor
import com.tt.autobluetoothcodec.TestSupport.runPending
import com.tt.autobluetoothcodec.TestSupport.VERIFY_MILLIS
import com.tt.autobluetoothcodec.TestSupport.sbc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** K5 of the test concept: the service the system binds while an observed device is present. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CodecCompanionServiceTest {
    private val a2dp = FakeA2dp()
    private lateinit var controller: ServiceController<CodecCompanionService>
    private val service get() = controller.get()

    @Before
    fun setUp() {
        grantPermission()
        resetApp(a2dp)
        a2dp.connected.add(ADDRESS)
        a2dp.connected.add(OTHER_ADDRESS)
        controller = Robolectric.buildService(CodecCompanionService::class.java).create()
    }

    private val connectionIntent get() = Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)

    private fun receiverCount() = shadowOf(context).registeredReceivers.count {
        it.intentFilter.hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
    }

    private fun a2dpConnected(address: String) {
        context.sendBroadcast(Intent(connectionIntent)
            .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device(address)))
        runPending()
    }

    /** The operations the service started, by trigger. */
    private fun operations(type: EventType) = events.filter { it.type == type }

    /** Links [ADDRESS] like the companion device manager since API 33, and returns the association's id. */
    private fun link(): Int {
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        Shadow.extract<ObservingCompanionDeviceManager>(companionManager).addAssociation(ADDRESS.lowercase())
        return companionManager.myAssociations.single().id
    }

    private fun presence(associationId: Int, event: Int) {
        service.onDevicePresenceEvent(DevicePresenceEvent(associationId, event, null))
        runFor(VERIFY_MILLIS)
    }

    @Test
    fun noPreferredCodecRegistersNoReceiver() {
        val before = receiverCount()
        service.onDeviceAppeared(ADDRESS)
        assertEquals(ADDRESS, operations(EventType.DEVICE_APPEARED).single().device!!.address)
        assertEquals(before, receiverCount())
        assertEquals(0, a2dp.connectCount)
    }

    @Test
    fun appearingRegistersTheReceiverOnceWithoutApplying() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        val before = receiverCount()
        // A2DP is connected already, still only A2DP connecting applies the preferred codec
        service.onDeviceAppeared(ADDRESS)
        runFor(VERIFY_MILLIS)
        assertEquals(before + 1, receiverCount())
        service.onDeviceAppeared(ADDRESS)
        runFor(VERIFY_MILLIS)
        assertEquals(before + 1, receiverCount())
        assertEquals(2, operations(EventType.DEVICE_APPEARED).size)
        assertTrue(events.all { it.opUID == null })
        assertTrue(a2dp.setCalls.isEmpty())
    }

    @Test
    fun a2dpConnectingAppliesOnlyForPresentDevices() {
        SavedDeviceStore.setPreferred(context, device(ADDRESS), sbc())
        SavedDeviceStore.setPreferred(context, device(OTHER_ADDRESS), sbc())
        service.onDeviceAppeared(ADDRESS)
        runFor(VERIFY_MILLIS)
        a2dpConnected(ADDRESS)
        a2dpConnected(OTHER_ADDRESS)
        runFor(VERIFY_MILLIS)
        val connected = operations(EventType.A2DP_CONNECTED).filter { it.message == "companion service" }
        assertEquals(listOf(ADDRESS), connected.map { it.device!!.address })
        assertEquals(EventType.CODEC_VERIFIED, resolution(connected.single().opUID!!)!!.type)
    }

    @Test
    fun disappearingCancelsThePendingRequest() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        a2dp.onSet = { _, _ -> }
        service.onDeviceAppeared(ADDRESS)
        a2dpConnected(ADDRESS)
        service.onDeviceDisappeared(ADDRESS.lowercase())
        val uid = operations(EventType.A2DP_CONNECTED).single { it.message == "companion service" }.opUID!!
        assertEquals(EventType.CANCELLED, resolution(uid)!!.type)
        assertEquals("device disappeared", resolution(uid)!!.message)
        assertEquals(ADDRESS, operations(EventType.DEVICE_DISAPPEARED).single().device!!.address)
        // No longer present, so A2DP connecting again doesn't apply
        a2dpConnected(ADDRESS)
        assertEquals(1, operations(EventType.A2DP_CONNECTED).count { it.message == "companion service" })
    }

    @Test
    fun destroyingUnregistersTheReceiver() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        val before = receiverCount()
        service.onDeviceAppeared(ADDRESS)
        runFor(VERIFY_MILLIS)
        controller.destroy()
        assertEquals(before, receiverCount())
    }

    @Test
    fun aRestartedServiceKnowsNoPresentDevices() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        service.onDeviceAppeared(ADDRESS)
        runFor(VERIFY_MILLIS)
        // Like the system restarting the process: a new instance, without onDeviceAppeared
        controller.destroy()
        controller = Robolectric.buildService(CodecCompanionService::class.java).create()
        a2dpConnected(ADDRESS)
        runFor(VERIFY_MILLIS)
        assertTrue(operations(EventType.A2DP_CONNECTED).none { it.message == "companion service" })
    }

    @Test
    @Config(sdk = [36], shadows = [ObservingCompanionDeviceManager::class])
    fun twoSourcesAreOneAppearance() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        val id = link()
        presence(id, DevicePresenceEvent.EVENT_BT_CONNECTED)
        presence(id, DevicePresenceEvent.EVENT_BLE_APPEARED)
        assertEquals("Bluetooth", operations(EventType.DEVICE_APPEARED).single().message)
    }

    @Test
    @Config(sdk = [36], shadows = [ObservingCompanionDeviceManager::class])
    fun theDeviceStaysPresentWhileOneSourceSeesIt() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        val id = link()
        presence(id, DevicePresenceEvent.EVENT_BLE_APPEARED)
        presence(id, DevicePresenceEvent.EVENT_BT_CONNECTED)
        // A BLE gap, like the S24 reports every minute or two
        presence(id, DevicePresenceEvent.EVENT_BLE_DISAPPEARED)
        assertTrue(operations(EventType.DEVICE_DISAPPEARED).isEmpty())
        a2dpConnected(ADDRESS)
        runFor(VERIFY_MILLIS)
        val connected = operations(EventType.A2DP_CONNECTED).single { it.message == "companion service" }
        assertEquals(EventType.CODEC_VERIFIED, resolution(connected.opUID!!)!!.type)
        presence(id, DevicePresenceEvent.EVENT_BT_DISCONNECTED)
        assertEquals("Bluetooth", operations(EventType.DEVICE_DISAPPEARED).single().message)
        // Gone from both, it appears again with the next source
        presence(id, DevicePresenceEvent.EVENT_BLE_APPEARED)
        assertEquals(listOf("BLE", "BLE"), operations(EventType.DEVICE_APPEARED).map { it.message })
    }

    @Test
    @Config(sdk = [36], shadows = [ObservingCompanionDeviceManager::class])
    fun theStringCallbacksAreIgnoredSince36() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        service.onDeviceAppeared(ADDRESS)
        service.onDeviceDisappeared(ADDRESS)
        assertTrue(operations(EventType.DEVICE_APPEARED).isEmpty())
        assertTrue(operations(EventType.DEVICE_DISAPPEARED).isEmpty())
    }

    @Test
    @Config(sdk = [36], shadows = [ObservingCompanionDeviceManager::class])
    fun eventsOfUnknownAssociationsAreIgnored() {
        SavedDeviceStore.setPreferred(context, device(), sbc())
        presence(42, DevicePresenceEvent.EVENT_BT_CONNECTED)
        assertNull(events.firstOrNull { it.type == EventType.DEVICE_APPEARED })
    }
}
