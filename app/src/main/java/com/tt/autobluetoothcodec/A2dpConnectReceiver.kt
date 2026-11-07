package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Applies the preferred codec when A2DP connects to a device, and notes when saved devices connect and disconnect for
 * the Saved devices list. Up to API 30 the Bluetooth stack
 * doesn't put the receiving app on the temporary allowlist, so starting a service from here would
 * be refused in the background. The work is done within the broadcast via [goAsync] instead.
 *
 * It's enabled on every API level, and up to API 32 it's the only component that applies the
 * preferred codec automatically. Since API 33 [CodecCompanionService] applies it too, but it doesn't
 * always run when A2DP connects: the system doesn't start a killed process again while the device
 * stays present. When both apply it, the second request resolves ALREADY_PENDING, or verifies the
 * codec that is already active.
 */
class A2dpConnectReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "A2dpConnectReceiver"
        // Background broadcasts time out after 60 seconds, which kills the process silently
        private const val DEADLINE_MILLIS: Long = 15_000
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
            Log.i(TAG, "received intent with unexpected action: ${intent.action}")
            return
        }
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED)
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        if (device == null || SavedDeviceStore.getPreferred(context, device) == null)
            return
        // The time of the disconnect is the one the Saved devices list shows. The connect's covers the phone turning
        // off while connected. A connection attempt that failed doesn't count.
        val disconnected = state == BluetoothProfile.STATE_DISCONNECTED && previous != BluetoothProfile.STATE_CONNECTING
        if (state == BluetoothProfile.STATE_CONNECTED || disconnected)
            SavedDeviceStore.updateInfo(context, device, lastConnected = System.currentTimeMillis())
        if (state != BluetoothProfile.STATE_CONNECTED)
            return

        val log = EventLog.get(context)
        val operation = log.begin(EventType.A2DP_CONNECTED, LogDevice.of(device), message = "manifest receiver")
        AsyncApply(context, log, goAsync()).start(device, operation)
    }

    /**
     * Keeps the broadcast pending until the operation is resolved and the log is written. Later
     * background broadcasts wait for it, so it gives up after [DEADLINE_MILLIS]. If another request
     * supersedes it, the applier continues that one without the broadcast.
     */
    private class AsyncApply(context: Context, private val log: EventLog, private val result: BroadcastReceiver.PendingResult) {
        private val handler = Handler(Looper.getMainLooper())
        private val applier = CodecApplier.get(context)
        private var deadline: Runnable? = null
        private var finished = false

        fun start(device: BluetoothDevice, operation: EventLog.Operation) {
            // Resolving the operation finishes the broadcast
            deadline = Runnable { applier.cancel(operation, EventType.TIMEOUT) }.also { handler.postDelayed(it, DEADLINE_MILLIS) }
            try {
                applier.apply(device, operation, onDone = { finish() })
            } catch (e: Throwable) {
                Log.e(TAG, "applying the codec failed", e)
                if (!applier.cancel(operation, EventType.ERROR, e.toString()) && operation.isOpen)
                    operation.resolve(EventType.ERROR, message = e.toString())
                finish()
            }
        }

        private fun finish() {
            if (finished)
                return
            finished = true
            deadline?.let { handler.removeCallbacks(it) }
            // The process may be killed once the broadcast is finished
            log.flush { result.finish() }
        }
    }
}
