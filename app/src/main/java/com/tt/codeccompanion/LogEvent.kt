package com.tt.codeccompanion

import android.bluetooth.BluetoothDevice
import kotlinx.serialization.Serializable

/**
 * The role of an event in an operation. Each operation starts with a [TRIGGER] event, followed by
 * any number of [ELEMENT] events, and ends with a [RESOLUTION] event. All of them carry the
 * operation's opUID. [STANDALONE] events don't belong to an operation.
 */
enum class OperationFlow { TRIGGER, ELEMENT, RESOLUTION, STANDALONE }

enum class EventType(val flow: OperationFlow) {
    // A2DP connected to a device with a preferred codec, it's applied
    A2DP_CONNECTED(OperationFlow.TRIGGER),
    // The user chose a codec in the UI
    USER_SET_CODEC(OperationFlow.TRIGGER),
    // The user restored the preferred codec, config is the preferred codec when it was chosen
    USER_RESTORE_PREFERRED(OperationFlow.TRIGGER),
    USER_SAVE_PREFERRED(OperationFlow.TRIGGER),
    USER_LINK_DEVICE(OperationFlow.TRIGGER),

    // config is the target configuration
    SET_CODEC_CONFIG(OperationFlow.ELEMENT),
    // Reads the codec back to check whether the target took effect. config is the target
    // configuration, actual what the Bluetooth stack reported
    VALIDATE_CODEC_CONFIG(OperationFlow.ELEMENT),
    ASSOCIATION_REQUESTED(OperationFlow.ELEMENT),

    CODEC_VERIFIED(OperationFlow.RESOLUTION),
    PREFERRED_SAVED(OperationFlow.RESOLUTION),
    ASSOCIATION_CREATED(OperationFlow.RESOLUTION),
    ASSOCIATION_FAILED(OperationFlow.RESOLUTION),
    MAX_ATTEMPTS(OperationFlow.RESOLUTION),
    NOT_CONNECTED(OperationFlow.RESOLUTION),
    NO_ASSOCIATION(OperationFlow.RESOLUTION),
    NO_PREFERRED_CODEC(OperationFlow.RESOLUTION),
    NO_PERMISSION(OperationFlow.RESOLUTION),
    SECURITY_EXCEPTION(OperationFlow.RESOLUTION),
    PROFILE_UNAVAILABLE(OperationFlow.RESOLUTION),
    PROFILE_DISCONNECTED(OperationFlow.RESOLUTION),
    TIMEOUT(OperationFlow.RESOLUTION),
    CANCELLED(OperationFlow.RESOLUTION),
    // The same codec is already pending for the device in another operation, the message names it
    ALREADY_PENDING(OperationFlow.RESOLUTION),
    // Another operation requested a different codec for the device, the message names it
    SUPERSEDED(OperationFlow.RESOLUTION),
    ERROR(OperationFlow.RESOLUTION),
    // Added by EventLog for operations a previous process didn't resolve. That process ended,
    // but why isn't known: it may have crashed, been force-stopped or been killed by the system
    OPERATION_INTERRUPTED(OperationFlow.RESOLUTION),

    DEVICE_APPEARED(OperationFlow.STANDALONE),
    DEVICE_DISAPPEARED(OperationFlow.STANDALONE),
    // MainActivity read a codec that differs from the last one known for the device in this process,
    // while the app wasn't changing it. The first codec read in a process is logged as well, so it
    // may not have changed at all. When it changed isn't known
    ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE(OperationFlow.STANDALONE),
    READ_FAILED(OperationFlow.STANDALONE),
    PREFERRED_DELETED(OperationFlow.STANDALONE),
    DEVICE_UNLINKED(OperationFlow.STANDALONE),
    PERMISSION_CHANGED(OperationFlow.STANDALONE),
    OBSERVING_CHANGED(OperationFlow.STANDALONE),
    OBSERVING_FAILED(OperationFlow.STANDALONE),
    // Sent by tests to mark where a test step starts, the message is the label
    TEST_LABEL(OperationFlow.STANDALONE),
}

/**
 * One line of the event log. Only [EventLog] creates events, it checks that they form operations
 * as described by [OperationFlow].
 */
@Serializable
data class LogEvent(
    // Version of this format
    val v: Int,
    // Increases by one for each event
    val seq: Long,
    // Milliseconds since the epoch
    val time: Long,
    val type: EventType,
    val opUID: String? = null,
    val device: LogDevice? = null,
    val config: LogCodecConfig? = null,
    val actual: LogCodecConfig? = null,
    val attempt: Int? = null,
    val message: String? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class LogDevice(val address: String, val name: String? = null) {
    companion object {
        fun of(device: BluetoothDevice): LogDevice {
            // The name requires the Bluetooth permission, which may have been revoked
            val name = try {
                device.name
            } catch (e: SecurityException) {
                null
            }
            return LogDevice(device.address.uppercase(), name)
        }
    }
}

/** Both models of a codec configuration, [own] is null if the codec is unknown to the app. */
@Serializable
data class LogCodecConfig(val android: AndroidCodecConfig, val own: CodecConfig? = null) {
    companion object {
        fun of(config: AndroidCodecConfig) = LogCodecConfig(config, CodecConfig.fromAndroid(config))

        fun of(config: CodecConfig) = LogCodecConfig(config.toAndroid(), config.clone() as CodecConfig)
    }

    fun describe(): String = own?.describe() ?: "unknown codec (type ${android.codecType})"
}
