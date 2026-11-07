package com.tt.autobluetoothcodec

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Example devices, screen states and log events for the previews and the UI tests. It's only in
 * debug builds, so the unit tests only run for the debug variant.
 */
internal object SampleData {
    const val ADDRESS = "0A:1B:2C:3D:4E:5F"
    const val NAME = "Qudelix-5K"
    const val SONY_ADDRESS = "AC:80:0A:11:22:33"
    const val SONY_NAME = "Sony WH-1000XM5"
    const val LONG_NAME = "A headphone with a much longer name than usual"

    /** LC3, which the phones offer for LE Audio but this app doesn't know. */
    const val CODEC_TYPE_LC3 = 5

    private const val RATES_44_48 = CodecConfig.SAMPLE_RATE_44100 or CodecConfig.SAMPLE_RATE_48000
    private const val RATES_ALL = RATES_44_48 or CodecConfig.SAMPLE_RATE_88200 or CodecConfig.SAMPLE_RATE_96000
    private const val BITS_ALL = CodecConfig.BITS_PER_SAMPLE_16 or CodecConfig.BITS_PER_SAMPLE_24 or CodecConfig.BITS_PER_SAMPLE_32

    /** What the Qudelix offers on the Pixels. */
    val QUDELIX_MASKS = listOf(
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_LDAC, RATES_ALL, BITS_ALL),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_APTX_HD, RATES_44_48, CodecConfig.BITS_PER_SAMPLE_24),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_APTX, RATES_44_48, CodecConfig.BITS_PER_SAMPLE_16),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16),
    )

    /** A headphone without aptX. */
    val SONY_MASKS = listOf(
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_LDAC, RATES_ALL, BITS_ALL),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_AAC, RATES_44_48, CodecConfig.BITS_PER_SAMPLE_16),
        A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_SBC, RATES_44_48, CodecConfig.BITS_PER_SAMPLE_16),
    )

    fun ldac(
        sampleRate: Int = CodecConfig.SAMPLE_RATE_96000,
        bitsPerSample: Int = CodecConfig.BITS_PER_SAMPLE_32,
        bitrate: Int = LDACCodecConfig.BITRATE_SYSTEM_DEFAULT
    ) = LDACCodecConfig().also {
        it.sampleRate = sampleRate
        it.bitsPerSample = bitsPerSample
        it.bitrate = bitrate
    }

    fun codec(codecType: Int, sampleRate: Int, bitsPerSample: Int) = CodecConfig.makeCodecConfig(codecType)!!.also {
        it.sampleRate = sampleRate
        it.bitsPerSample = bitsPerSample
    }

    fun aac() = codec(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16)

    fun sbc() = codec(CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16)

    /** A device as MainActivity reads it, by default the Qudelix with LDAC 96 kHz, 32-bit. */
    fun device(
        codec: CodecConfig? = ldac(),
        android: AndroidCodecConfig? = codec?.toAndroid(),
        masks: List<A2dpProfileUtil.ConfigMask> = QUDELIX_MASKS,
        statusReadable: Boolean = true,
        associationRequired: Boolean = false,
        associated: Boolean = true,
        preferred: CodecConfig? = null,
        name: String = NAME,
        address: String = ADDRESS,
        target: CodecConfig? = null,
    ) = DeviceData(
        address = address,
        name = name,
        androidCodecConfig = android,
        codecConfig = codec,
        configMasks = ArrayList(if (statusReadable) masks else emptyList()),
        statusReadable = statusReadable,
        associationRequired = associationRequired,
        associated = associated,
        preferredConfig = preferred,
        readError = if (statusReadable) null else "java.lang.SecurityException: Need BLUETOOTH_PRIVILEGED permission",
        targetConfig = target,
    )

    /** The Sony with AAC, by default. */
    fun sony(codec: CodecConfig? = aac(), preferred: CodecConfig? = null, target: CodecConfig? = null) =
        device(codec, masks = SONY_MASKS, preferred = preferred, name = SONY_NAME, address = SONY_ADDRESS, target = target)

    fun state(
        vararg devices: DeviceData,
        permissionState: PermissionState = PermissionState.GRANTED,
        canAssociate: Boolean = true,
        linkingAddress: String? = null,
        showDevelopmentOptions: Boolean = false,
        bluetoothEnabled: Boolean = true,
        devicesKnown: Boolean = true,
        hasSavedDevices: Boolean = devices.any { it.preferredConfig != null },
    ) = MainScreenState(permissionState, devices.toList(), canAssociate, linkingAddress, showDevelopmentOptions,
        bluetoothEnabled, devicesKnown, hasSavedDevices)

    val TODAY: LocalDate = LocalDate.of(2026, 10, 27)

    /** Collects log events with increasing sequence numbers. */
    private class LogBuilder {
        val events = ArrayList<LogEvent>()

        fun add(
            type: EventType,
            day: LocalDate,
            time: String,
            opUID: String? = null,
            device: LogDevice? = LogDevice(ADDRESS, NAME),
            config: LogCodecConfig? = null,
            actual: LogCodecConfig? = null,
            attempt: Int? = null,
            message: String? = null,
        ) {
            val millis = day.atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            events.add(LogEvent(LogEvent.VERSION, events.size.toLong(), millis, type, opUID, device, config, actual, attempt, message))
        }
    }

    /**
     * Two days of the log: the preferred codec applied on connecting, an external change, a codec
     * change that didn't take, a preferred codec saved with an association, a read failure and a
     * timeout.
     */
    fun log(today: LocalDate = TODAY): List<LogEvent> {
        val log = LogBuilder()
        val sony = LogDevice(SONY_ADDRESS, SONY_NAME)
        val ldac = LogCodecConfig.of(ldac())
        val aac = LogCodecConfig.of(aac())
        val sbc = LogCodecConfig.of(sbc())

        val yesterday = today.minusDays(1)
        log.add(EventType.A2DP_CONNECTED, yesterday, "18:02:11", "op1", config = ldac)
        log.add(EventType.SET_CODEC_CONFIG, yesterday, "18:02:11", "op1", config = ldac, attempt = 1)
        log.add(EventType.VALIDATE_CODEC_CONFIG, yesterday, "18:02:12", "op1", config = ldac, actual = ldac, attempt = 1)
        log.add(EventType.CODEC_VERIFIED, yesterday, "18:02:12", "op1", actual = ldac, attempt = 1)

        log.add(EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE, today, "08:15:40", actual = sbc)
        log.add(EventType.USER_SET_CODEC, today, "08:16:02", "op2", config = ldac)
        for (attempt in 1..3) {
            log.add(EventType.SET_CODEC_CONFIG, today, "08:16:0${attempt * 2}", "op2", config = ldac, attempt = attempt)
            log.add(EventType.VALIDATE_CODEC_CONFIG, today, "08:16:0${attempt * 2 + 1}", "op2", config = ldac, actual = sbc, attempt = attempt)
        }
        log.add(EventType.MAX_ATTEMPTS, today, "08:16:08", "op2", config = ldac, actual = sbc, attempt = 3)

        log.add(EventType.USER_SAVE_PREFERRED, today, "08:20:31", "op3", sony, config = aac)
        log.add(EventType.ASSOCIATION_REQUESTED, today, "08:20:31", "op3", sony)
        log.add(EventType.PREFERRED_SAVED, today, "08:20:44", "op3", sony)

        log.add(EventType.READ_FAILED, today, "12:40:03", device = sony,
            message = "java.lang.SecurityException: Need BLUETOOTH_PRIVILEGED permission")
        log.add(EventType.DEVICE_APPEARED, today, "12:41:00", "op4", sony, config = aac)
        log.add(EventType.TIMEOUT, today, "12:41:10", "op4", sony)
        return log.events
    }

    /**
     * A log with what's rare: an entry from last week, a device without a name and one with a long
     * name, an unknown codec, a failure with a long message, a slow operation, events without a
     * device and an operation still in progress.
     */
    fun edgeCaseLog(today: LocalDate = TODAY): List<LogEvent> {
        val log = LogBuilder()
        val unnamed = LogDevice("F4:4E:FD:00:12:9A")
        val longName = LogDevice("00:1A:7D:DA:71:13", LONG_NAME)
        val ldac = LogCodecConfig.of(ldac())
        val unknown = LogCodecConfig(AndroidCodecConfig(CODEC_TYPE_LC3, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16))

        val lastWeek = today.minusDays(7)
        log.add(EventType.PERMISSION_CHANGED, lastWeek, "21:13:05", device = null, message = "granted")
        log.add(EventType.OBSERVING_CHANGED, lastWeek, "21:13:20", device = unnamed, message = "started")
        log.add(EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE, lastWeek, "21:14:02", device = unnamed, actual = unknown)

        log.add(EventType.USER_LINK_DEVICE, today, "07:01:00", "op1", longName)
        log.add(EventType.ASSOCIATION_REQUESTED, today, "07:01:00", "op1", longName)
        log.add(EventType.ASSOCIATION_CREATED, today, "07:03:41", "op1", longName)

        log.add(EventType.USER_SET_CODEC, today, "07:05:12", "op2", longName, config = ldac)
        log.add(EventType.SET_CODEC_CONFIG, today, "07:05:12", "op2", longName, config = ldac, attempt = 1)
        log.add(EventType.ERROR, today, "07:05:13", "op2", longName, config = ldac, message =
            "java.lang.IllegalStateException: setCodecConfigPreference failed for 00:1A:7D:DA:71:13, " +
                "the Bluetooth stack returned BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND")

        log.add(EventType.PROFILE_UNAVAILABLE, today, "07:30:00", device = null)
        log.add(EventType.A2DP_CONNECTED, today, "09:12:44", "op3", config = ldac)
        log.add(EventType.SET_CODEC_CONFIG, today, "09:12:44", "op3", config = ldac, attempt = 1)
        return log.events
    }
}
