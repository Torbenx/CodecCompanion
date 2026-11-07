package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Applies the preferred codec of observed devices, see [CompanionPresence]. The system keeps this
 * service bound while such a device is present, which keeps the process out of the background. It's
 * disabled before API 33, where the system only binds it for the callbacks. [A2dpConnectReceiver]
 * applies the preferred codec when A2DP connects too, for when the service doesn't run.
 *
 * A device appears as soon as its Bluetooth link connects, usually before A2DP is connected. So
 * while a device is present, a runtime receiver waits for A2DP to connect. Unlike the manifest
 * receiver it doesn't queue up behind other apps' receivers.
 *
 * Up to API 35 only the String callbacks are overridden: they exist since API 31, and the
 * AssociationInfo variants forward to them. Since API 36 the system calls them for each source a
 * device is seen on, BLE and its Bluetooth connection, and reports one source going while the other
 * stays. So there the service follows [onDevicePresenceEvent] instead, and a device is present while
 * any source reports it.
 */
@RequiresApi(33)
class CodecCompanionService : CompanionDeviceService() {
    companion object {
        private const val TAG = "CodecCompanionService"
        private const val AWAIT_LOG_MILLIS = 1000L
    }

    private val applier by lazy { CodecApplier.get(this) }
    private val presentDevices = HashSet<String>()
    // Since API 36 the sources each present device is seen on, see onDevicePresenceEvent
    private val presenceSources = HashMap<String, MutableSet<String>>()
    private var receiverRegistered = false

    private val a2dpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED) != BluetoothProfile.STATE_CONNECTED)
                return
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device != null && device.address in presentDevices) {
                val operation = EventLog.get(context).begin(EventType.A2DP_CONNECTED, LogDevice.of(device),
                    message = "companion service")
                applyCodec(device, operation)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < 36)
            deviceAppeared(address, source = null)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT < 36)
            deviceDisappeared(address, source = null)
    }

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val (source, appeared) = when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> "BLE" to true
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> "BLE" to false
            DevicePresenceEvent.EVENT_BT_CONNECTED -> "Bluetooth" to true
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> "Bluetooth" to false
            DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED -> "self-managed" to true
            DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED -> "self-managed" to false
            else -> return
        }
        val address = getSystemService(CompanionDeviceManager::class.java).myAssociations
            .firstOrNull { it.id == event.associationId }?.deviceMacAddress?.toString()?.uppercase()
        if (address == null) {
            Log.w(TAG, "presence event $event for an association that doesn't exist")
            return
        }
        val sources = presenceSources.getOrPut(address) { HashSet() }
        if (appeared) {
            val first = sources.isEmpty()
            if (sources.add(source) && first)
                deviceAppeared(address, source)
        } else if (sources.remove(source) && sources.isEmpty()) {
            presenceSources.remove(address)
            deviceDisappeared(address, source)
        }
    }

    /** Since API 36 [source] is the one the device appeared on first. */
    private fun deviceAppeared(address: String, source: String?) {
        val device = getDevice(address)
        EventLog.get(this).log(EventType.DEVICE_APPEARED,
            device?.let { LogDevice.of(it) } ?: LogDevice(address.uppercase()), message = source)
        if (device == null || SavedDeviceStore.getPreferred(this, device) == null)
            return
        presentDevices.add(device.address)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, a2dpReceiver,
                IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
    }

    /** Since API 36 [source] is the last one the device was seen on. */
    private fun deviceDisappeared(address: String, source: String?) {
        val device = getDevice(address)
        EventLog.get(this).log(EventType.DEVICE_DISAPPEARED,
            device?.let { LogDevice.of(it) } ?: LogDevice(address.uppercase()), message = source)
        if (device == null)
            return
        presentDevices.remove(device.address)
        applier.cancel(device, EventType.CANCELLED, "device disappeared")
    }

    // Pending requests are left to the applier, they end within a few seconds
    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            unregisterReceiver(a2dpReceiver)
            receiverRegistered = false
        }
        // The system may have started the process only for the last event, like a device that
        // disappeared, which may still wait for the log to load. Without the bound service, the
        // process could be killed before it's written
        EventLog.awaitWritten(AWAIT_LOG_MILLIS)
    }

    private fun applyCodec(device: BluetoothDevice, operation: EventLog.Operation) {
        try {
            applier.apply(device, operation)
        } catch (e: Throwable) {
            Log.e(TAG, "applying the codec of ${device.address} failed", e)
            if (!applier.cancel(operation, EventType.ERROR, e.toString()) && operation.isOpen)
                operation.resolve(EventType.ERROR, message = e.toString())
        }
    }

    private fun getDevice(address: String): BluetoothDevice? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        // getRemoteDevice only accepts upper case addresses
        return adapter.getRemoteDevice(address.uppercase())
    }
}
