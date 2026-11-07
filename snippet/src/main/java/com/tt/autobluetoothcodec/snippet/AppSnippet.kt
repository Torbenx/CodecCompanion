package com.tt.autobluetoothcodec.snippet

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import com.tt.autobluetoothcodec.A2dpProfileUtil
import com.tt.autobluetoothcodec.AndroidCodecConfig
import com.tt.autobluetoothcodec.CodecApplier
import com.tt.autobluetoothcodec.CodecConfig
import com.tt.autobluetoothcodec.CompanionPresence
import com.tt.autobluetoothcodec.EventLog
import com.tt.autobluetoothcodec.EventType
import com.tt.autobluetoothcodec.LogCodecConfig
import com.tt.autobluetoothcodec.LogDevice
import com.tt.autobluetoothcodec.MainActivity
import com.tt.autobluetoothcodec.SavedDeviceStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's internals for the Bluetooth logic tests. It runs in the app's process, so it uses the
 * app's own singletons. Codecs pass as the app's AndroidCodecConfig JSON, like
 * {"codecType": 4, "sampleRate": 8, "bitsPerSample": 2, "codecSpecific1": 13}.
 */
class AppSnippet : Snippet {
    companion object {
        // Longer than a request can take: 5 attempts, 500 ms apart, after the profile connects
        private const val APPLY_TIMEOUT_SECONDS = 20L
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    @Volatile
    private var a2dp: BluetoothA2dp? = null

    /** Runs [block] on the main thread, where the app uses its singletons. */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** The snippet's own A2DP proxy. Must not be called on the main thread, which delivers it. */
    private fun profile(): BluetoothA2dp {
        a2dp?.let { return it }
        val connected = CountDownLatch(1)
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                connected.countDown()
            }

            override fun onServiceDisconnected(profile: Int) {
                a2dp = null
            }
        }, BluetoothProfile.A2DP)
        check(connected.await(5, TimeUnit.SECONDS)) { "the A2DP profile proxy didn't connect" }
        return a2dp!!
    }

    private fun device(address: String): BluetoothDevice = adapter.getRemoteDevice(address.uppercase())

    private fun toJson(config: AndroidCodecConfig) =
        JSONObject(EventLog.json.encodeToString(AndroidCodecConfig.serializer(), config))

    private fun fromJson(config: JSONObject) =
        EventLog.json.decodeFromString(AndroidCodecConfig.serializer(), config.toString())

    private fun appConfig(config: JSONObject): CodecConfig =
        requireNotNull(CodecConfig.fromAndroid(fromJson(config))) { "the app doesn't know codec $config" }

    @Rpc(description = "The process's id and whether the app's classes are the app's own, for the spike.")
    fun getProcessInfo(): JSONObject = JSONObject()
        .put("pid", Process.myPid())
        .put("sameClassLoader", CodecApplier::class.java.classLoader === context.classLoader)

    @Rpc(description = "The addresses the applier is changing the codec of.")
    fun getPending(): JSONArray = onMain { JSONArray(CodecApplier.get(context).pending.value.keys) }

    @Rpc(description = "Applies a codec through the CodecApplier, like choosing it on the main screen, " +
            "and returns the operation's uid and resolution once it's resolved.")
    fun applyCodec(address: String, config: JSONObject): JSONObject {
        val target = appConfig(config)
        val done = CountDownLatch(1)
        var resolution: EventType? = null
        val uid = onMain {
            val operation = EventLog.get(context).begin(EventType.USER_SET_CODEC, LogDevice.of(device(address)),
                LogCodecConfig.of(target), message = "snippet")
            CodecApplier.get(context).apply(device(address), operation, target, onDone = {
                resolution = it
                done.countDown()
            })
            operation.uid
        }
        done.await(APPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return JSONObject().put("operation", uid).put("resolution", resolution?.name ?: JSONObject.NULL)
    }

    @Rpc(description = "Starts applying a codec through the CodecApplier and returns the operation's uid right away.")
    fun startApplyCodec(address: String, config: JSONObject): String {
        val target = appConfig(config)
        return onMain {
            val operation = EventLog.get(context).begin(EventType.USER_SET_CODEC, LogDevice.of(device(address)),
                LogCodecConfig.of(target), message = "snippet")
            CodecApplier.get(context).apply(device(address), operation, target)
            operation.uid
        }
    }

    @Rpc(description = "Sets a codec bypassing the CodecApplier, like another app would.")
    fun setCodecDirectly(address: String, config: JSONObject) {
        A2dpProfileUtil.setCodecConfig(profile(), device(address), fromJson(config))
    }

    @Rpc(description = "The device's current codec as the app reads it, or null.")
    fun getCurrentCodec(address: String): JSONObject? {
        val config = A2dpProfileUtil.getCurrentCodecConfig(profile(), device(address)) ?: return null
        return toJson(config)
    }

    @Rpc(description = "The codecs the device offers, as masks of sample rates and sizes.")
    fun getCapabilities(address: String): JSONArray {
        val masks = A2dpProfileUtil.getSelectableCodecConfigs(profile(), device(address))
        return JSONArray(masks.map {
            JSONObject()
                .put("codecType", it.codecType)
                .put("sampleRates", it.sampleRateMask)
                .put("bitsPerSample", it.bitsPerSampleMask)
        })
    }

    @Rpc(description = "The device's preferred codec, or null.")
    fun getPreferredCodec(address: String): JSONObject? = onMain {
        SavedDeviceStore.getPreferred(context, device(address))?.let { toJson(it.toAndroid()) }
    }

    @Rpc(description = "Saves a preferred codec without asking for a link, and observes the device like the app does.")
    fun setPreferredCodec(address: String, config: JSONObject) = onMain {
        SavedDeviceStore.setPreferred(context, device(address), appConfig(config))
        CompanionPresence.update(context, device(address))
    }

    @Rpc(description = "Deletes the device's preferred codec and stops observing it, like the app does.")
    fun deletePreferredCodec(address: String) = onMain {
        SavedDeviceStore.remove(context, device(address))
        CompanionPresence.update(context, device(address))
    }

    @Rpc(description = "Brings observing in line with the preferred codecs, like the app does on every start.")
    fun syncPresence() = onMain { CompanionPresence.syncAll(context) }

    @Rpc(description = "Resets the log, the preferred codecs and the singletons to their defaults.")
    fun resetForTest() = onMain {
        CodecApplier.resetForTest(context)
        EventLog.resetForTest(context)
        SavedDeviceStore.clearForTest(context)
        MainActivity.resetForTest()
    }

    override fun shutdown() {
        a2dp?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
    }
}
