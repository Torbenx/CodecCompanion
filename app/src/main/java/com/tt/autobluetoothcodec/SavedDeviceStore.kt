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
import java.io.File

/**
 * The saved devices, keyed by address, in two tiers:
 *
 * - The preferred codec, which makes a device saved. It can be read and written at any time, also before the user
 *   unlocks the phone after a reboot, when [A2dpConnectReceiver] and [CodecCompanionService] apply it. It's stored in
 *   the Android representation, so it round trips through [CodecConfig.toAndroid] and [CodecConfig.fromAndroid].
 * - The device's [Info]: its name, when it was last connected and the values it offers, for the Saved devices list.
 *   It can be written at any time but only read once the user unlocked the phone, it's in credential protected
 *   storage. Before the unlock, the changes are appended to a device protected file, each encrypted by
 *   [LockedStorageCrypto]. The first read or write after the unlock merges them, or [EventLog] when it notices the
 *   unlock, see [mergeLockedInfo].
 *
 * Only saved devices have info, writes for other devices are ignored, and removing a device removes both tiers.
 */
object SavedDeviceStore {
    private const val TAG = "SavedDeviceStore"
    private const val PREFERRED_NAME = "preferred_codecs"
    private const val INFO_NAME = "saved_device_info"
    // Excluded from backups by backup_rules.xml and data_extraction_rules.xml, nobody could decrypt it elsewhere
    const val LOCKED_DIRECTORY = "saved_devices"
    const val LOCKED_FILE_NAME = "locked_info_updates"
    // Binds the device protected file's lines to it, see LockedStorageCrypto.encrypt
    private const val LOCKED_CONTEXT_INFO = "saved_device_info"
    // Keeps at least as many of the changes before the unlock, the newest. Each connect and disconnect writes one
    const val MAX_LOCKED_UPDATES = 1000

    // Writes every field, like the format before kotlinx.serialization was used
    internal val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private var preferredPreferences: SharedPreferences? = null
    private var infoPreferences: SharedPreferences? = null
    private var lockedInfo: LockedFile? = null
    // Whether this process merged the changes before the unlock, there can't be new ones until the next reboot
    private var lockedInfoMerged = false

    /** What the Saved devices list knows of a device besides its preferred codec, null where it's unknown. */
    @Serializable
    data class Info(
        val name: String? = null,
        // Milliseconds since the epoch, when A2DP last connected or disconnected
        val lastConnected: Long? = null,
        // The values it offered when it was last read, empty if they never were
        val capabilities: List<A2dpProfileUtil.ConfigMask> = emptyList(),
    )

    /** A change of a device's info before the unlock, the values that are null stay as they are. */
    @Serializable
    internal data class InfoUpdate(
        val address: String,
        val name: String? = null,
        val lastConnected: Long? = null,
        val capabilities: List<A2dpProfileUtil.ConfigMask>? = null,
    ) {
        fun applyTo(old: Info) = Info(
            name = name ?: old.name,
            lastConnected = lastConnected ?: old.lastConnected,
            capabilities = capabilities ?: old.capabilities,
        )
    }

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

    /**
     * Removes the device with both tiers, returns what was stored to [restore] it, or null if it wasn't saved. Only
     * once the user unlocked the phone.
     */
    fun remove(context: Context, device: BluetoothDevice): Entry? {
        val key = key(device)
        val info = unlockedInfo(context)
        val entry = preferred(context).getString(key, null)?.let { Entry(it, info.getString(key, null)) }
        preferred(context).edit { remove(key) }
        info.edit { remove(key) }
        return entry
    }

    /** Stores [entry] again, as returned by [remove]. Only once the user unlocked the phone. */
    fun restore(context: Context, device: BluetoothDevice, entry: Entry) {
        val key = key(device)
        val info = unlockedInfo(context)
        preferred(context).edit { putString(key, entry.preferred) }
        info.edit { if (entry.info != null) putString(key, entry.info) else remove(key) }
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
        val update = InfoUpdate(key, name, lastConnected, capabilities)
        if (!isUserUnlocked(context)) {
            appendLocked(context, update)
            return
        }
        val info = unlockedInfo(context)
        val old = readInfo(info, key) ?: Info()
        val new = update.applyTo(old)
        if (new != old)
            info.edit { putString(key, json.encodeToString(new)) }
    }

    /** The info of a saved device, null if it isn't saved. Only once the user unlocked the phone. */
    fun getInfo(context: Context, device: BluetoothDevice): Info? = getInfo(context, key(device))

    /** The info of the saved device with [address], null if it isn't saved. Only once the user unlocked the phone. */
    fun getInfo(context: Context, address: String): Info? {
        val info = unlockedInfo(context)
        val key = address.uppercase()
        if (!preferred(context).contains(key))
            return null
        return readInfo(info, key) ?: Info()
    }

    /**
     * Applies the changes written before the user unlocked the phone, in order, then deletes them. Does nothing after
     * the first time in a process. Only once the user unlocked the phone.
     */
    @Synchronized
    fun mergeLockedInfo(context: Context) {
        if (lockedInfoMerged)
            return
        check(isUserUnlocked(context)) { "the saved devices' info can only be read once the user unlocked the phone" }
        val file = lockedInfo(context)
        if (file.exists()) {
            val info = info(context)
            val merged = HashMap<String, Info>()
            for (record in file.read()) {
                val update = try {
                    json.decodeFromString<InfoUpdate>(record.decodeToString())
                } catch (e: SerializationException) {
                    Log.w(TAG, "invalid change of the info before the unlock", e)
                    continue
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "invalid change of the info before the unlock", e)
                    continue
                }
                // Not saved anymore
                if (!preferred(context).contains(update.address))
                    continue
                val old = merged[update.address] ?: readInfo(info, update.address) ?: Info()
                merged[update.address] = update.applyTo(old)
            }
            // Written before the changes are deleted. Applying them again after a crash in between gives the same info,
            // since nothing writes the info before they're merged
            if (merged.isNotEmpty())
                info.edit(commit = true) { merged.forEach { (key, value) -> putString(key, json.encodeToString(value)) } }
            file.delete()
        }
        lockedInfoMerged = true
    }

    @Synchronized
    private fun appendLocked(context: Context, update: InfoUpdate) {
        lockedInfo(context).append(json.encodeToString(update).toByteArray())
    }

    /** The info's preferences once the changes before the unlock are merged. */
    private fun unlockedInfo(context: Context): SharedPreferences {
        check(isUserUnlocked(context)) { "the saved devices' info can only be read once the user unlocked the phone" }
        mergeLockedInfo(context)
        return info(context)
    }

    private fun readInfo(info: SharedPreferences, key: String): Info? {
        val text = info.getString(key, null) ?: return null
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
        lockedInfo = null
        lockedInfoMerged = false
        preferred(context).edit(commit = true) { clear() }
        info(context).edit(commit = true) { clear() }
        lockedInfo(context).delete()
    }

    /** Forgets that the changes before the unlock were merged, as if the process restarted, for tests. */
    @VisibleForTesting
    fun restartForTest() {
        lockedInfo = null
        lockedInfoMerged = false
    }

    private fun isUserUnlocked(context: Context) = context.getSystemService(UserManager::class.java).isUserUnlocked

    private fun preferred(context: Context): SharedPreferences =
        preferredPreferences ?: context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFERRED_NAME, Context.MODE_PRIVATE)
            .also { preferredPreferences = it }

    // In credential protected storage, which can't be opened before the user unlocked the phone
    private fun info(context: Context): SharedPreferences =
        infoPreferences ?: context.applicationContext.getSharedPreferences(INFO_NAME, Context.MODE_PRIVATE)
            .also { infoPreferences = it }

    internal fun lockedFile(context: Context) =
        File(File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, LOCKED_DIRECTORY),
            LOCKED_FILE_NAME)

    private fun lockedInfo(context: Context): LockedFile =
        lockedInfo ?: LockedFile(context.applicationContext, lockedFile(context), LOCKED_CONTEXT_INFO, MAX_LOCKED_UPDATES)
            .also { lockedInfo = it }

    private fun key(device: BluetoothDevice) = device.address.uppercase()
}
