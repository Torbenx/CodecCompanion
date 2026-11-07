package com.tt.autobluetoothcodec

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.serialization.Serializable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * getCodecStatus and setCodecConfigPreference are system APIs, so they are always called by
 * reflection. The codec classes exist since API 26 but are only public since API 33, before that
 * their members are accessed by reflection as well.
 */
object A2dpProfileUtil {
    // Looked up by name, a class literal would be flagged by lint since the classes are hidden before API 33
    private val codecConfigClass = Class.forName("android.bluetooth.BluetoothCodecConfig")
    private val codecStatusClass = Class.forName("android.bluetooth.BluetoothCodecStatus")

    // The CodecConfig constants have the values of the BluetoothCodecConfig constants
    @SuppressLint("WrongConstant")
    private fun createCodecConfig(config: AndroidCodecConfig): Any {
        if (Build.VERSION.SDK_INT >= 33) {
            // Deprecated since API 35 in favor of setExtendedCodecType, but apps can only get a
            // BluetoothCodecType from a codec status. The Bluetooth stack maps the type the same way.
            @Suppress("DEPRECATION")
            return BluetoothCodecConfig.Builder()
                .setCodecType(config.codecType)
                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .setSampleRate(config.sampleRate)
                .setBitsPerSample(config.bitsPerSample)
                .setCodecSpecific1(config.codecSpecific1.toLong())
                .setCodecSpecific2(config.codecSpecific2.toLong())
                .setCodecSpecific3(config.codecSpecific3.toLong())
                .setCodecSpecific4(config.codecSpecific4.toLong())
                .build()
        } else {
            val codecConstructor = codecConfigClass.getConstructor(
                Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE,
                java.lang.Long.TYPE, java.lang.Long.TYPE, java.lang.Long.TYPE, java.lang.Long.TYPE)
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/oreo-release/core/java/android/bluetooth/BluetoothCodecConfig.java
            // The priority and channel mode values are the hidden constants of that version
            return codecConstructor.newInstance(
                config.codecType,
                BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST,
                config.sampleRate,
                config.bitsPerSample,
                BluetoothCodecConfig.CHANNEL_MODE_NONE,
                config.codecSpecific1.toLong(),
                config.codecSpecific2.toLong(),
                config.codecSpecific3.toLong(),
                config.codecSpecific4.toLong()
            )
        }
    }

    // Rethrow the exception of the invoked method, so callers can handle a SecurityException
    // from the Bluetooth stack directly.
    private fun invokeUnwrapped(method: Method, receiver: Any, vararg args: Any?): Any? {
        try {
            return method.invoke(receiver, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    // Returns null if the device isn't connected or the Bluetooth service is unavailable
    private fun getCodecStatus(profile: BluetoothA2dp, device: BluetoothDevice): Any? {
        val method = BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java)
        return invokeUnwrapped(method, profile, device)
    }

    fun setCodecConfig(profile: BluetoothA2dp, device: BluetoothDevice, config: AndroidCodecConfig) {
        val codecConfig = createCodecConfig(config)
        val method = BluetoothA2dp::class.java.getMethod("setCodecConfigPreference", BluetoothDevice::class.java, codecConfigClass)
        invokeUnwrapped(method, profile, device, codecConfig)
    }

    fun getCurrentCodecConfig(profile: BluetoothA2dp, device: BluetoothDevice): AndroidCodecConfig? {
        val status = getCodecStatus(profile, device) ?: return null
        if (Build.VERSION.SDK_INT >= 33) {
            return (status as BluetoothCodecStatus).codecConfig?.let { toAndroidCodecConfig(it) }
        }
        val codecConfig = codecStatusClass.getMethod("getCodecConfig").invoke(status) ?: return null
        return reflectAndroidCodecConfig(codecConfig)
    }

    fun getSelectableCodecConfigs(profile: BluetoothA2dp, device: BluetoothDevice): ArrayList<ConfigMask> {
        val status = getCodecStatus(profile, device) ?: return ArrayList()
        if (Build.VERSION.SDK_INT >= 33) {
            return (status as BluetoothCodecStatus).codecsSelectableCapabilities.mapTo(ArrayList()) {
                val config = toAndroidCodecConfig(it)
                ConfigMask(config.codecType, config.sampleRate, config.bitsPerSample)
            }
        }
        val configs = codecStatusClass.getMethod("getCodecsSelectableCapabilities").invoke(status)
            ?: return ArrayList()
        // An array before API 33, the hidden method of some versions may return a list
        val configList = if (configs is List<*>) configs
            else (0 ..< java.lang.reflect.Array.getLength(configs)).map { java.lang.reflect.Array.get(configs, it) }
        return configList.filterNotNull().mapTo(ArrayList()) {
            val config = reflectAndroidCodecConfig(it)
            ConfigMask(config.codecType, config.sampleRate, config.bitsPerSample)
        }
    }

    // getCodecType is deprecated since API 35 in favor of getExtendedCodecType, but it still
    // returns the codec types the app knows, and SOURCE_CODEC_TYPE_INVALID for any other codec.
    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    @RequiresApi(33)
    private fun toAndroidCodecConfig(config: BluetoothCodecConfig): AndroidCodecConfig {
        return AndroidCodecConfig(
            codecType = config.codecType,
            sampleRate = config.sampleRate,
            bitsPerSample = config.bitsPerSample,
            codecSpecific1 = config.codecSpecific1.toInt(),
            codecSpecific2 = config.codecSpecific2.toInt(),
            codecSpecific3 = config.codecSpecific3.toInt(),
            codecSpecific4 = config.codecSpecific4.toInt()
        )
    }

    private fun reflectAndroidCodecConfig(config: Any): AndroidCodecConfig {
        return AndroidCodecConfig(
            codecType = codecConfigClass.getMethod("getCodecType").invoke(config) as Int,
            sampleRate = codecConfigClass.getMethod("getSampleRate").invoke(config) as Int,
            bitsPerSample = codecConfigClass.getMethod("getBitsPerSample").invoke(config) as Int,
            codecSpecific1 = (codecConfigClass.getMethod("getCodecSpecific1").invoke(config) as Long).toInt(),
            codecSpecific2 = (codecConfigClass.getMethod("getCodecSpecific2").invoke(config) as Long).toInt(),
            codecSpecific3 = (codecConfigClass.getMethod("getCodecSpecific3").invoke(config) as Long).toInt(),
            codecSpecific4 = (codecConfigClass.getMethod("getCodecSpecific4").invoke(config) as Long).toInt()
        )
    }

    @Serializable
    data class ConfigMask(
        @CodecConfig.CodecType
        val codecType: Int = CodecConfig.CODEC_TYPE_INVALID,

        val sampleRateMask: Int = CodecConfig.SAMPLE_RATE_NONE,

        val bitsPerSampleMask: Int = CodecConfig.BITS_PER_SAMPLE_NONE
    ) {
        fun isSampleRateSupported(@CodecConfig.SampleRate sampleRate: Int): Boolean {
            return sampleRate and sampleRateMask != 0
        }
        fun isSampleSizeSupported(@CodecConfig.BitsPerSample bitsPerSample: Int): Boolean {
            return bitsPerSample and bitsPerSampleMask != 0
        }
    }
}
