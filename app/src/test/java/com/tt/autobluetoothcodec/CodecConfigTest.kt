package com.tt.autobluetoothcodec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecConfigTest {
    private fun android(
        codecType: Int,
        sampleRate: Int = CodecConfig.SAMPLE_RATE_44100,
        bitsPerSample: Int = CodecConfig.BITS_PER_SAMPLE_16,
        codecSpecific1: Int = 0,
        codecSpecific3: Int = 0
    ) = AndroidCodecConfig(codecType, sampleRate, bitsPerSample, codecSpecific1 = codecSpecific1, codecSpecific3 = codecSpecific3)

    private fun config(android: AndroidCodecConfig) = CodecConfig.fromAndroid(android)!!

    private fun ldac(bitrate: Int, sampleRate: Int = CodecConfig.SAMPLE_RATE_96000) =
        (CodecConfig.makeCodecConfig(CodecConfig.CODEC_TYPE_LDAC) as LDACCodecConfig).also {
            it.sampleRate = sampleRate
            it.bitsPerSample = CodecConfig.BITS_PER_SAMPLE_24
            it.bitrate = bitrate
        }

    @Test
    fun fromAndroid_roundTripsEveryCodecRateAndSize() {
        for (type in CodecConfig.CODEC_TYPES) {
            for (rate in CodecConfig.SAMPLE_RATES + CodecConfig.SAMPLE_RATE_NONE) {
                for (bits in CodecConfig.SAMPLE_SIZES + CodecConfig.BITS_PER_SAMPLE_NONE) {
                    val android = android(type, rate, bits)
                    assertEquals(android, config(android).toAndroid())
                }
            }
        }
    }

    @Test
    fun fromAndroid_returnsNullForCodecsTheAppDoesntKnow() {
        assertNull(CodecConfig.fromAndroid(android(CodecConfig.CODEC_TYPE_INVALID)))
        // Samsung's scalable codec, which the S24 offers
        assertNull(CodecConfig.fromAndroid(android(7)))
    }

    @Test
    fun ldac_writesBitrateAsTenPlusValue() {
        for (bitrate in LDACCodecConfig.BITRATES)
            assertEquals(10 + bitrate, ldac(bitrate).toAndroid().codecSpecific1)
        assertEquals(0, ldac(LDACCodecConfig.BITRATE_SYSTEM_DEFAULT).toAndroid().codecSpecific1)
    }

    @Test
    fun ldac_readsTheStacksOwnValues() {
        fun bitrateOf(codecSpecific1: Int) =
            (config(android(CodecConfig.CODEC_TYPE_LDAC, codecSpecific1 = codecSpecific1)) as LDACCodecConfig).bitrate
        assertEquals(LDACCodecConfig.BITRATE_SYSTEM_DEFAULT, bitrateOf(0))
        // The stack reports 1000 to 1003 for the quality it chose
        assertEquals(LDACCodecConfig.BITRATE_HIGH, bitrateOf(1000))
        assertEquals(LDACCodecConfig.BITRATE_MID, bitrateOf(1001))
        assertEquals(LDACCodecConfig.BITRATE_LOW, bitrateOf(1002))
        assertEquals(LDACCodecConfig.BITRATE_ADAPTIVE, bitrateOf(1003))
        // Values above adaptive aren't a bitrate the app knows
        for (codecSpecific1 in listOf(14, 15, 19, 1004))
            assertEquals("$codecSpecific1", LDACCodecConfig.BITRATE_SYSTEM_DEFAULT, bitrateOf(codecSpecific1))
    }

    @Test
    fun aac_readsVariableBitrateOnlyFrom128To133() {
        fun vbrOf(codecSpecific1: Int) =
            (config(android(CodecConfig.CODEC_TYPE_AAC, codecSpecific1 = codecSpecific1)) as AACCodecConfig).variableBitrate
        for (vbr in AACCodecConfig.VARIABLE_BITRATE_DISABLED..AACCodecConfig.VARIABLE_BITRATE_VERY_HIGH) {
            assertEquals(vbr, vbrOf(128 + vbr))
            assertEquals(128 + vbr, config(android(CodecConfig.CODEC_TYPE_AAC, codecSpecific1 = 128 + vbr)).toAndroid().codecSpecific1)
        }
        for (codecSpecific1 in listOf(0, 5, 127, 134))
            assertEquals("$codecSpecific1", AACCodecConfig.VARIABLE_BITRATE_SYSTEM_DEFAULT, vbrOf(codecSpecific1))
    }

    @Test
    fun opus_readsComplexityModuloTen() {
        fun complexityOf(codecSpecific3: Int) =
            (config(android(CodecConfig.CODEC_TYPE_OPUS, codecSpecific3 = codecSpecific3)) as OpusCodecConfig).encodeComplexity
        assertEquals(OpusCodecConfig.ENCODE_COMPLEXITY_SYSTEM_DEFAULT, complexityOf(0))
        assertEquals(0, complexityOf(10))
        assertEquals(9, complexityOf(19))
        assertEquals(5, complexityOf(1005))
        assertEquals(17, config(android(CodecConfig.CODEC_TYPE_OPUS, codecSpecific3 = 17)).toAndroid().codecSpecific3)
    }

    @Test
    fun isFulfilledBy_acceptsAnyValueForOptionsAtTheSystemDefault() {
        val target = ldac(LDACCodecConfig.BITRATE_SYSTEM_DEFAULT)
        for (codecSpecific1 in listOf(0, 1000, 1001, 1002, 1003)) {
            val actual = config(android(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24, codecSpecific1))
            assertTrue("$codecSpecific1", target.isFulfilledBy(actual))
        }
        val aac = config(android(CodecConfig.CODEC_TYPE_AAC))
        assertTrue(aac.isFulfilledBy(config(android(CodecConfig.CODEC_TYPE_AAC, codecSpecific1 = 128 + AACCodecConfig.VARIABLE_BITRATE_HIGH))))
    }

    @Test
    fun isFulfilledBy_requiresExplicitOptions() {
        val target = ldac(LDACCodecConfig.BITRATE_HIGH)
        fun actual(codecSpecific1: Int) =
            config(android(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24, codecSpecific1))
        assertTrue(target.isFulfilledBy(actual(1000)))
        assertTrue(target.isFulfilledBy(actual(10)))
        assertFalse(target.isFulfilledBy(actual(1003)))
        assertFalse(target.isFulfilledBy(actual(0)))
    }

    @Test
    fun isFulfilledBy_neverAcceptsAnotherCodecRateOrSize() {
        val target = ldac(LDACCodecConfig.BITRATE_SYSTEM_DEFAULT)
        val others = listOf(
            android(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24),
            android(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24),
            android(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_32),
        )
        for (other in others)
            assertFalse(other.toString(), target.isFulfilledBy(config(other)))
    }

    @Test
    fun withSystemChoices_fillsInTheOptionsLeftToTheSystem() {
        val actual = config(android(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_32, 1003))
        val shown = ldac(LDACCodecConfig.BITRATE_SYSTEM_DEFAULT).withSystemChoices(actual) as LDACCodecConfig
        // Only the bitrate is the system's, the rate and size stay the target's
        assertEquals(LDACCodecConfig.BITRATE_ADAPTIVE, shown.bitrate)
        assertEquals(CodecConfig.SAMPLE_RATE_96000, shown.sampleRate)
        assertEquals(CodecConfig.BITS_PER_SAMPLE_24, shown.bitsPerSample)
    }

    @Test
    fun withSystemChoices_keepsExplicitOptionsAndIgnoresOtherCodecs() {
        val actual = config(android(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24, 1003))
        assertEquals(ldac(LDACCodecConfig.BITRATE_HIGH), ldac(LDACCodecConfig.BITRATE_HIGH).withSystemChoices(actual))
        val target = ldac(LDACCodecConfig.BITRATE_SYSTEM_DEFAULT)
        assertEquals(target, target.withSystemChoices(config(android(CodecConfig.CODEC_TYPE_AAC, codecSpecific1 = 128))))
        assertEquals(target, target.withSystemChoices(null))
    }

    @Test
    fun equals_comparesEveryParameter() {
        assertEquals(ldac(LDACCodecConfig.BITRATE_LOW), ldac(LDACCodecConfig.BITRATE_LOW))
        assertEquals(ldac(LDACCodecConfig.BITRATE_LOW).hashCode(), ldac(LDACCodecConfig.BITRATE_LOW).hashCode())
        assertNotEquals(ldac(LDACCodecConfig.BITRATE_LOW), ldac(LDACCodecConfig.BITRATE_MID))
        assertNotEquals(config(android(CodecConfig.CODEC_TYPE_SBC)), config(android(CodecConfig.CODEC_TYPE_APTX)))
        assertNotEquals(config(android(CodecConfig.CODEC_TYPE_SBC)), config(android(CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_48000)))
    }

    @Test
    fun equals_comparesTheOptionsOfEveryCodec() {
        val aac = config(android(CodecConfig.CODEC_TYPE_AAC, codecSpecific1 = 128 + AACCodecConfig.VARIABLE_BITRATE_HIGH))
        val sameAac = AACCodecConfig().also {
            it.sampleRate = CodecConfig.SAMPLE_RATE_44100
            it.bitsPerSample = CodecConfig.BITS_PER_SAMPLE_16
            it.variableBitrate = AACCodecConfig.VARIABLE_BITRATE_HIGH
        }
        assertEquals(aac, sameAac)
        assertEquals(aac.hashCode(), sameAac.hashCode())
        assertNotEquals(aac, config(android(CodecConfig.CODEC_TYPE_AAC)))
        val opus = config(android(CodecConfig.CODEC_TYPE_OPUS, codecSpecific3 = 17))
        assertEquals(opus, config(android(CodecConfig.CODEC_TYPE_OPUS, codecSpecific3 = 17)))
        assertNotEquals(opus, config(android(CodecConfig.CODEC_TYPE_OPUS, codecSpecific3 = 18)))
        // The stack's own LDAC values read as the bitrate they stand for
        assertEquals(ldac(LDACCodecConfig.BITRATE_HIGH),
            config(android(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24, 1000)))
    }

    @Test
    fun clone_isIndependent() {
        val original = ldac(LDACCodecConfig.BITRATE_LOW)
        val clone = original.clone() as LDACCodecConfig
        assertEquals(original, clone)
        clone.bitrate = LDACCodecConfig.BITRATE_HIGH
        clone.sampleRate = CodecConfig.SAMPLE_RATE_44100
        assertEquals(LDACCodecConfig.BITRATE_LOW, original.bitrate)
        assertEquals(CodecConfig.SAMPLE_RATE_96000, original.sampleRate)
    }

    @Test
    fun describe_ldacFixedBitratesFollowTheSampleRateFamily() {
        assertEquals("LDAC, 44100 Hz, 24-bit, 303 kbps", ldac(LDACCodecConfig.BITRATE_LOW, CodecConfig.SAMPLE_RATE_44100).describe())
        assertEquals("LDAC, 88200 Hz, 24-bit, 606 kbps", ldac(LDACCodecConfig.BITRATE_MID, CodecConfig.SAMPLE_RATE_88200).describe())
        assertEquals("LDAC, 48000 Hz, 24-bit, 330 kbps", ldac(LDACCodecConfig.BITRATE_LOW, CodecConfig.SAMPLE_RATE_48000).describe())
        assertEquals("LDAC, 96000 Hz, 24-bit, 990 kbps", ldac(LDACCodecConfig.BITRATE_HIGH).describe())
        assertEquals("LDAC, unknown sample rate, 24-bit, 909/990 kbps", ldac(LDACCodecConfig.BITRATE_HIGH, CodecConfig.SAMPLE_RATE_NONE).describe())
        assertEquals("LDAC, 96000 Hz, 24-bit", ldac(LDACCodecConfig.BITRATE_SYSTEM_DEFAULT).describe())
    }

    @Test
    fun describe_otherOptionsAndUnknownValues() {
        assertEquals("aptX, 48000 Hz, unknown sample size",
            config(android(CodecConfig.CODEC_TYPE_APTX, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_NONE)).describe())
        assertEquals("AAC, 44100 Hz, 16-bit, VBR off",
            config(android(CodecConfig.CODEC_TYPE_AAC, codecSpecific1 = 128 + AACCodecConfig.VARIABLE_BITRATE_DISABLED)).describe())
        assertEquals("Opus, 48000 Hz, 16-bit, complexity 7",
            config(android(CodecConfig.CODEC_TYPE_OPUS, CodecConfig.SAMPLE_RATE_48000, codecSpecific3 = 17)).describe())
    }
}
