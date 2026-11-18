package com.tt.codeccompanion

import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper

/**
 * An A2DP profile for component tests. By default the profile connects on the main looper like the
 * system's proxy, and every codec that is set takes effect at once.
 */
class FakeA2dp : A2dpAccess {
    override var isBluetoothEnabled = true

    /** What [connect] returns, false like a phone without Bluetooth. */
    var canConnect = true
    /** Whether [connect] connects the profile on its own, otherwise [connectProfile] does. */
    var connectsAutomatically = true

    var listener: A2dpAccess.Listener? = null
        private set
    var connectCount = 0
        private set
    var closeCount = 0
        private set
    var isProfileConnected = false
        private set

    /** Addresses A2DP is connected to. */
    val connected = HashSet<String>()
    /** The codec of each device, by address. */
    val codecs = HashMap<String, AndroidCodecConfig>()
    /** Every codec set, in order. */
    val setCalls = ArrayList<Pair<String, AndroidCodecConfig>>()

    /** What the stack does with a codec that is set, it takes effect by default. */
    var onSet: (address: String, config: AndroidCodecConfig) -> Unit = { address, config -> codecs[address] = config }
    /** Thrown by [setCodec] if set. */
    var setFailure: Throwable? = null
    /** Thrown by [connect] if set. */
    var connectFailure: Throwable? = null

    override fun connect(listener: A2dpAccess.Listener): Boolean {
        this.listener = listener
        connectCount += 1
        connectFailure?.let { throw it }
        if (!canConnect)
            return false
        if (connectsAutomatically)
            Handler(Looper.getMainLooper()).post { connectProfile() }
        return true
    }

    fun connectProfile() {
        isProfileConnected = true
        listener?.onConnected()
    }

    fun disconnectProfile() {
        isProfileConnected = false
        listener?.onDisconnected()
    }

    override fun close() {
        closeCount += 1
        // Like the system's proxy, closing it reports the disconnect again
        if (isProfileConnected)
            disconnectProfile()
    }

    override fun isConnected(device: BluetoothDevice): Boolean {
        check(isProfileConnected) { "the profile isn't connected" }
        return device.address in connected
    }

    override fun getCodec(device: BluetoothDevice): AndroidCodecConfig? {
        check(isProfileConnected) { "the profile isn't connected" }
        return codecs[device.address]
    }

    override fun setCodec(device: BluetoothDevice, config: AndroidCodecConfig) {
        check(isProfileConnected) { "the profile isn't connected" }
        setFailure?.let { throw it }
        setCalls.add(device.address to config)
        onSet(device.address, config)
    }
}
