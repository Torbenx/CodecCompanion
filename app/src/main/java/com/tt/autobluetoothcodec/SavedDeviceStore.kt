package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The saved devices, keyed by address, in two tiers:
 *
 * - The preferred codec, which makes a device saved. It can be read and written at any time, also before the user
 *   unlocks the phone after a reboot, when [A2dpConnectReceiver] and [CodecCompanionService] apply it. It's stored in
 *   the Android representation, so it round trips through [CodecConfig.toAndroid] and [CodecConfig.fromAndroid].
 * - The device's [Info]: its name, when it was last connected and the values it offers, for the Saved devices list.
 *   It can be written at any time but only read once the user unlocked the phone, so it can move to encrypted storage
 *   later. For now it's in device protected storage as well.
 *
 * Only saved devices have info, writes for other devices are ignored, and removing a device removes both tiers.
 */
object SavedDeviceStore {
    private const val TAG = "SavedDeviceStore"
    private const val PREFERRED_NAME = "preferred_codecs"
    private const val INFO_NAME = "saved_device_info"

    // Writes every field, like the format before kotlinx.serialization was used
    internal val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private var preferredPreferences: SharedPreferences? = null
    private var infoPreferences: SharedPreferences? = null

    /** What the Saved devices list knows of a device besides its preferred codec, null where it's unknown. */
    @Serializable
    data class Info(
        val name: String? = null,
        // Milliseconds since the epoch, when A2DP last connected or disconnected
        val lastConnected: Long? = null,
        // The values it offered when it was last read, empty if they never were
        val capabilities: List<A2dpProfileUtil.ConfigMask> = emptyList(),
    )

    /** Everything stored for a device, to put it back after it was removed. */
    class Entry internal constructor(internal val preferred: String, internal val info: String?)

    // The preferred codec

    fun getPreferred(context: Context, device: BluetoothDevice): CodecConfig? = getPreferred(context, device.address)

    fun getPreferred(context: Context, address: String): CodecConfig? {
        val key = address.uppercase()
        val text = preferred(context).getString(key, null) ?: return null
        return try {
            CodecConfig.fromAndroid(json.decodeFromString<AndroidCodecConfig>(text))
        } catch (e: SerializationException) {
            Log.w(TAG, "invalid preferred codec for $key", e)
            null
        }
    }

    fun setPreferred(context: Context, device: BluetoothDevice, config: CodecConfig) {
        preferred(context).edit { putString(key(device), json.encodeToString(config.toAndroid())) }
    }

    /** Whether any device is saved. */
    fun hasAny(context: Context): Boolean = preferred(context).all.isNotEmpty()

    /** The upper case addresses of the saved devices. */
    fun addresses(context: Context): Set<String> = preferred(context).all.keys

    /** Removes the device with both tiers, returns what was stored to [restore] it, or null if it wasn't saved. */
    fun remove(context: Context, device: BluetoothDevice): Entry? {
        val key = key(device)
        val entry = preferred(context).getString(key, null)?.let { Entry(it, info(context).getString(key, null)) }
        preferred(context).edit { remove(key) }
        info(context).edit { remove(key) }
        return entry
    }

    /** Stores [entry] again, as returned by [remove]. */
    fun restore(context: Context, device: BluetoothDevice, entry: Entry) {
        val key = key(device)
        preferred(context).edit { putString(key, entry.preferred) }
        info(context).edit { if (entry.info != null) putString(key, entry.info) else remove(key) }
    }

    // The info

    /**
     * Changes the info of a saved device, the values that are null stay as they are. Works before the user unlocked the
     * phone as well.
     */
    fun updateInfo(
        context: Context,
        device: BluetoothDevice,
        name: String? = null,
        lastConnected: Long? = null,
        capabilities: List<A2dpProfileUtil.ConfigMask>? = null,
    ) {
        val key = key(device)
        if (!preferred(context).contains(key))
            return
        val old = readInfo(context, key) ?: Info()
        val new = old.copy(
            name = name ?: old.name,
            lastConnected = lastConnected ?: old.lastConnected,
            capabilities = capabilities ?: old.capabilities,
        )
        if (new != old)
            info(context).edit { putString(key, json.encodeToString(new)) }
    }

    /** The info of a saved device, null if it isn't saved. Only once the user unlocked the phone. */
    fun getInfo(context: Context, device: BluetoothDevice): Info? = getInfo(context, key(device))

    /** The info of the saved device with [address], null if it isn't saved. Only once the user unlocked the phone. */
    fun getInfo(context: Context, address: String): Info? {
        check(context.getSystemService(UserManager::class.java).isUserUnlocked) {
            "the saved devices' info can only be read once the user unlocked the phone"
        }
        val key = address.uppercase()
        if (!preferred(context).contains(key))
            return null
        return readInfo(context, key) ?: Info()
    }

    private fun readInfo(context: Context, key: String): Info? {
        val text = info(context).getString(key, null) ?: return null
        return try {
            json.decodeFromString<Info>(text)
        } catch (e: SerializationException) {
            Log.w(TAG, "invalid info for $key", e)
            null
        }
    }

    /** Removes all saved devices, for tests. */
    @VisibleForTesting
    fun clearForTest(context: Context) {
        // Each test has its own context and files
        preferredPreferences = null
        infoPreferences = null
        preferred(context).edit(commit = true) { clear() }
        info(context).edit(commit = true) { clear() }
    }

    private fun preferred(context: Context): SharedPreferences =
        preferredPreferences ?: open(context, PREFERRED_NAME).also { preferredPreferences = it }

    private fun info(context: Context): SharedPreferences =
        infoPreferences ?: open(context, INFO_NAME).also { infoPreferences = it }

    private fun open(context: Context, name: String): SharedPreferences =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun key(device: BluetoothDevice) = device.address.uppercase()
}
