package com.tt.codeccompanion

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.annotation.RequiresPermission

/**
 * The A2DP profile as the [CodecApplier] uses it. [SystemA2dpAccess] uses the system's profile
 * proxy, component tests replace it with a fake.
 */
interface A2dpAccess {
    val isBluetoothEnabled: Boolean

    /**
     * Asks for the profile. [listener] is called on the main thread once it's connected, and when
     * it disconnects. Returns false if the profile can't be asked for.
     */
    fun connect(listener: Listener): Boolean

    /** Releases the profile, which may report the disconnect again. */
    fun close()

    /** Whether A2DP is connected to [device]. Only while the profile is connected, like the following. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isConnected(device: BluetoothDevice): Boolean

    fun getCodec(device: BluetoothDevice): AndroidCodecConfig?

    fun setCodec(device: BluetoothDevice, config: AndroidCodecConfig)

    interface Listener {
        fun onConnected()
        fun onDisconnected()
    }
}

class SystemA2dpAccess(private val context: Context) : A2dpAccess, BluetoothProfile.ServiceListener {
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var profile: BluetoothA2dp? = null
    private var listener: A2dpAccess.Listener? = null

    override val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled ?: false

    override fun connect(listener: A2dpAccess.Listener): Boolean {
        this.listener = listener
        return adapter?.getProfileProxy(context, this, BluetoothProfile.A2DP) ?: false
    }

    override fun close() {
        val proxy = profile ?: return
        profile = null
        adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
        this.profile = proxy as BluetoothA2dp
        listener?.onConnected()
    }

    override fun onServiceDisconnected(profile: Int) {
        listener?.onDisconnected()
    }

    private fun proxy() = checkNotNull(profile) { "the A2DP profile isn't connected" }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun isConnected(device: BluetoothDevice): Boolean =
        proxy().getConnectionState(device) == BluetoothProfile.STATE_CONNECTED

    override fun getCodec(device: BluetoothDevice): AndroidCodecConfig? =
        A2dpProfileUtil.getCurrentCodecConfig(proxy(), device)

    override fun setCodec(device: BluetoothDevice, config: AndroidCodecConfig) =
        A2dpProfileUtil.setCodecConfig(proxy(), device, config)
}
