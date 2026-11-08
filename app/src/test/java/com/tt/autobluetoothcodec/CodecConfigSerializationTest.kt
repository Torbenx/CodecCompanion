package com.tt.autobluetoothcodec

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CodecConfigSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun preferredCodecStore_readsFormatWrittenWithOrgJson() {
        // What SavedDeviceStore wrote, as PreferredCodecStore, before it used kotlinx.serialization
        val stored = """{"codecType":4,"sampleRate":8,"bitsPerSample":2,"codecSpecific1":13,""" +
                """"codecSpecific2":0,"codecSpecific3":0,"codecSpecific4":0}"""
        val config = SavedDeviceStore.json.decodeFromString<AndroidCodecConfig>(stored)
        assertEquals(AndroidCodecConfig(4, 8, 2, 13, 0, 0, 0), config)
        assertEquals(stored, SavedDeviceStore.json.encodeToString(config))
    }

    @Test
    fun codecConfig_roundTripsEveryCodec() {
        val configs = listOf(
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16, codecSpecific1 = 128 + AACCodecConfig.VARIABLE_BITRATE_HIGH),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24, codecSpecific1 = 10 + LDACCodecConfig.BITRATE_ADAPTIVE),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_88200, CodecConfig.BITS_PER_SAMPLE_32),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_APTX, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24),
            AndroidCodecConfig(CodecConfig.CODEC_TYPE_OPUS, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16, codecSpecific3 = 10 + 7),
        )
        for (android in configs) {
            val config = CodecConfig.fromAndroid(android)!!
            val text = json.encodeToString<CodecConfig>(config)
            val decoded = json.decodeFromString<CodecConfig>(text)
            assertEquals(text, config, decoded)
            assertEquals(text, android, decoded.toAndroid())
        }
    }

    @Test
    fun codecConfig_isWrittenReadably() {
        val config = CodecConfig.fromAndroid(AndroidCodecConfig(
            CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24,
            codecSpecific1 = 10 + LDACCodecConfig.BITRATE_ADAPTIVE))!!
        val obj = json.parseToJsonElement(json.encodeToString<CodecConfig>(config)).jsonObject
        assertEquals("LDAC", obj["type"]!!.jsonPrimitive.content)
        assertEquals(96000, obj["sampleRate"]!!.jsonPrimitive.int)
        assertEquals(24, obj["bitsPerSample"]!!.jsonPrimitive.int)
        assertEquals("ADAPTIVE", obj["bitrate"]!!.jsonPrimitive.content)
    }

    @Test
    fun describe_isReadable() {
        fun describe(config: AndroidCodecConfig) = CodecConfig.fromAndroid(config)!!.describe()
        assertEquals("LDAC, 96000\u00a0Hz, 24-bit, adaptive bitrate", describe(AndroidCodecConfig(
            CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24,
            codecSpecific1 = 10 + LDACCodecConfig.BITRATE_ADAPTIVE)))
        assertEquals("LDAC, 44100\u00a0Hz, 32-bit, 909\u00a0kbps", describe(AndroidCodecConfig(
            CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_32,
            codecSpecific1 = 10 + LDACCodecConfig.BITRATE_HIGH)))
        assertEquals("AAC, 44100\u00a0Hz, 16-bit, VBR high", describe(AndroidCodecConfig(
            CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16,
            codecSpecific1 = 128 + AACCodecConfig.VARIABLE_BITRATE_HIGH)))
        assertEquals("SBC, unknown sample rate, 16-bit", describe(AndroidCodecConfig(
            CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_NONE, CodecConfig.BITS_PER_SAMPLE_16)))
    }

    @Test
    fun codecConfig_omitsSystemDefaults() {
        val config = CodecConfig.fromAndroid(AndroidCodecConfig(
            CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24))!!
        val obj: JsonObject = json.parseToJsonElement(json.encodeToString<CodecConfig>(config)).jsonObject
        assertFalse(obj.containsKey("bitrate"))
    }

    @Test
    fun codecConfig_readsUnknownRatesAndSizesAsNone() {
        val config = json.decodeFromString<CodecConfig>("""{"type":"SBC","sampleRate":22050,"bitsPerSample":8}""")
        assertEquals(CodecConfig.SAMPLE_RATE_NONE, config.sampleRate)
        assertEquals(CodecConfig.BITS_PER_SAMPLE_NONE, config.bitsPerSample)
    }

    @Test
    fun constants_areWrittenByName() {
        val ldac = LDACCodecConfig().also { it.bitrate = LDACCodecConfig.BITRATE_MID }
        val ldacObj = json.parseToJsonElement(json.encodeToString<CodecConfig>(ldac)).jsonObject
        assertEquals("MID", ldacObj["bitrate"]!!.jsonPrimitive.content)
        val aac = AACCodecConfig().also { it.variableBitrate = AACCodecConfig.VARIABLE_BITRATE_VERY_HIGH }
        val aacObj = json.parseToJsonElement(json.encodeToString<CodecConfig>(aac)).jsonObject
        assertEquals("VERY_HIGH", aacObj["variableBitrate"]!!.jsonPrimitive.content)
    }

    @Test
    fun constants_withoutNameAreWrittenAsNumbers() {
        val ldac = LDACCodecConfig().also { it.bitrate = 7 }
        val text = json.encodeToString<CodecConfig>(ldac)
        assertEquals("7", json.parseToJsonElement(text).jsonObject["bitrate"]!!.jsonPrimitive.content)
        assertEquals(7, (json.decodeFromString<CodecConfig>(text) as LDACCodecConfig).bitrate)
    }

    @Test
    fun constants_withUnknownNamesThrow() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<CodecConfig>("""{"type":"LDAC","sampleRate":96000,"bitsPerSample":24,"bitrate":"FAST"}""")
        }
    }
}
