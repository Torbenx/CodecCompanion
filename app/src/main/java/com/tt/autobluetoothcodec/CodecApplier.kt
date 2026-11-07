package com.tt.autobluetoothcodec

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Applies codecs to connected devices and checks that they took effect, trying again a few times
 * if they didn't. A codec counts as applied once two reads in a row, [VERIFY_DELAY_MILLIS] apart,
 * report it: right after A2DP connects the S24 sometimes changes the codec back to its own choice
 * while the codec it reports still lags behind. There is one applier per process, so the requests of the UI, [CodecCompanionService]
 * and [A2dpConnectReceiver] are coordinated: each device has at most one pending request. A request
 * for a different codec supersedes the pending one, a request for the same codec is dropped.
 *
 * Must be used on the main thread. Each [apply] continues an operation of the [EventLog] and
 * resolves it, unless the operation is handed back to its onSecurityException callback.
 *
 * The profile proxy is opened by the first request and kept until Bluetooth turns off, keeping it
 * open only holds a binder reference. The applier reaches the profile through [A2dpAccess].
 *
 * It also keeps the codec last known for each device during this process, see [knownCodec], so the
 * main screen can tell changes made outside the app from the applier's own.
 */
class CodecApplier @VisibleForTesting internal constructor(
    private val context: Context,
    private val a2dp: A2dpAccess
) : A2dpAccess.Listener {
    companion object {
        private const val TAG = "CodecApplier"
        private const val VERIFY_DELAY_MILLIS: Long = 500
        private const val MAX_ATTEMPT_COUNT = 5
        private const val VERIFYING_READ_COUNT = 2

        private var instance: CodecApplier? = null

        fun get(context: Context): CodecApplier {
            // A BroadcastReceiver's context isn't allowed to bind services, which the profile proxy does
            val appContext = context.applicationContext
            return instance ?: CodecApplier(appContext, SystemA2dpAccess(appContext)).also { instance = it }
        }

        /**
         * Replaces the process's applier, for tests. From now on [get] returns one that uses [access],
         * or a new one that uses the system's profile if it's null. The pending requests of the
         * current applier are dropped without being resolved.
         */
        @VisibleForTesting
        fun resetForTest(context: Context, access: A2dpAccess? = null) {
            instance?.close()
            instance = access?.let { CodecApplier(context.applicationContext, it) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val processRunnable = Runnable { processRequests() }

    private var profileConnected = false
    private var profileRequested = false
    private val requests = ArrayList<Request>()

    private val mutablePending = MutableStateFlow<Map<String, CodecConfig>>(emptyMap())
    /** The target of each device with a pending request, by upper case address. */
    val pending: StateFlow<Map<String, CodecConfig>> = mutablePending.asStateFlow()

    // By upper case address
    private val knownCodecs = HashMap<String, AndroidCodecConfig>()

    /**
     * Returns the codec most recently read from the device during this process, by the applier or
     * reported by [noteObservedCodec], or null if there was none.
     */
    fun knownCodec(address: String): AndroidCodecConfig? = knownCodecs[address.uppercase()]

    /** Records [codec] as read from the device with [address] outside the applier. */
    fun noteObservedCodec(address: String, codec: AndroidCodecConfig) {
        knownCodecs[address.uppercase()] = codec
    }

    /**
     * Applies [target] to [device], or its preferred codec if [target] is null. The request is
     * dropped if A2DP isn't connected to the device. [onDone] is called with the resolution of
     * [operation]. If Bluetooth refuses the change and [onSecurityException] is set, the operation
     * is handed back to it unresolved instead.
     */
    fun apply(
        device: BluetoothDevice,
        operation: EventLog.Operation,
        target: CodecConfig? = null,
        onDone: ((EventType) -> Unit)? = null,
        onSecurityException: ((EventLog.Operation, SecurityException) -> Unit)? = null
    ) {
        if (!PermissionUtil.testPermission(context)) {
            reject(operation, onDone, EventType.NO_PERMISSION)
            return
        }
        val config = target ?: SavedDeviceStore.getPreferred(context, device)
        if (config == null) {
            reject(operation, onDone, EventType.NO_PREFERRED_CODEC)
            return
        }
        // While Bluetooth is off the profile proxy wouldn't connect until it's turned on
        if (!a2dp.isBluetoothEnabled) {
            reject(operation, onDone, EventType.PROFILE_UNAVAILABLE)
            return
        }
        val superseded = requests.firstOrNull { it.device == device }
        if (superseded != null) {
            if (superseded.target == config) {
                reject(operation, onDone, EventType.ALREADY_PENDING, "pending in operation ${superseded.operation.uid}")
                return
            }
            requests.remove(superseded)
        }
        requests.add(Request(device, config, operation, onDone, onSecurityException))
        // Replaced in one step, so observers don't see the device idle while its codec is changing
        publishPending()
        if (superseded != null) {
            superseded.operation.resolve(EventType.SUPERSEDED, attempt = superseded.attemptCount,
                message = "superseded by operation ${operation.uid}")
            superseded.onDone?.invoke(EventType.SUPERSEDED)
        }
        if (profileConnected)
            processRequests()
        else
            connectProfile()
    }

    /** Resolves the pending request of [device] with [resolution]. */
    fun cancel(device: BluetoothDevice, resolution: EventType, message: String? = null) {
        requests.firstOrNull { it.device == device }?.let { finish(it, resolution, attempt = it.attemptCount, message = message) }
    }

    /** Resolves the pending request of [operation] with [resolution], returns false if there is none. */
    fun cancel(operation: EventLog.Operation, resolution: EventType, message: String? = null): Boolean {
        val request = requests.firstOrNull { it.operation == operation } ?: return false
        finish(request, resolution, attempt = request.attemptCount, message = message)
        return true
    }

    /** Drops all requests and releases the profile. */
    private fun close() {
        handler.removeCallbacks(processRunnable)
        requests.clear()
        publishPending()
        profileConnected = false
        profileRequested = false
        a2dp.close()
    }

    private fun reject(operation: EventLog.Operation, onDone: ((EventType) -> Unit)?, resolution: EventType, message: String? = null) {
        operation.resolve(resolution, message = message)
        onDone?.invoke(resolution)
    }

    /** Removes [request] and resolves its operation with [resolution]. */
    private fun finish(
        request: Request,
        resolution: EventType,
        config: LogCodecConfig? = null,
        actual: LogCodecConfig? = null,
        attempt: Int? = null,
        message: String? = null
    ) {
        requests.remove(request)
        // Resolved first, so observers of pending see the operation ended. They compare the codec with
        // knownCodec, which the request's last read updated already
        request.operation.resolve(resolution, config = config, actual = actual, attempt = attempt, message = message)
        publishPending()
        request.onDone?.invoke(resolution)
    }

    private fun publishPending() {
        mutablePending.value = requests.associate { it.device.address.uppercase() to it.target }
    }

    private fun connectProfile() {
        if (profileRequested)
            return
        profileRequested = a2dp.connect(this)
        if (!profileRequested) {
            for (request in requests.toList())
                if (request in requests)
                    finish(request, EventType.PROFILE_UNAVAILABLE)
        }
    }

    override fun onConnected() {
        profileConnected = true
        processRequests()
    }

    override fun onDisconnected() {
        // Closing the proxy reports the disconnect again
        if (!profileConnected)
            return
        profileConnected = false
        // Requested again by the next request, rather than relying on the framework to reconnect it
        profileRequested = false
        a2dp.close()
        handler.removeCallbacks(processRunnable)
        for (request in requests.toList())
            if (request in requests)
                finish(request, EventType.PROFILE_DISCONNECTED, attempt = request.attemptCount)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processRequests() {
        handler.removeCallbacks(processRunnable)
        if (!profileConnected)
            return
        val now = SystemClock.uptimeMillis()
        for (request in requests.toList()) {
            // The callback of an earlier request may have changed the requests
            if (request !in requests || now - request.lastStep < VERIFY_DELAY_MILLIS)
                continue
            try {
                process(request, now)
            } catch (e: SecurityException) {
                Log.d(TAG, "Bluetooth refused codec access", e)
                val onSecurityException = request.onSecurityException
                if (onSecurityException != null) {
                    requests.remove(request)
                    publishPending()
                    onSecurityException(request.operation, e)
                } else {
                    finish(request, EventType.SECURITY_EXCEPTION, attempt = request.attemptCount, message = e.toString())
                }
            } catch (e: Throwable) {
                Log.d(TAG, "codec change ran into exception", e)
                finish(request, EventType.ERROR, attempt = request.attemptCount, message = e.toString())
            }
        }
        if (profileConnected && requests.isNotEmpty()) {
            val nextStep = requests.minOf { it.lastStep } + VERIFY_DELAY_MILLIS
            handler.postAtTime(processRunnable, nextStep)
        }
    }

    /**
     * Verifies the previous attempt of [request] and makes the next one, reads the codec again after
     * the first matching read, or finishes the request.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun process(request: Request, now: Long) {
        val operation = request.operation
        val target = LogCodecConfig.of(request.target)
        if (!a2dp.isConnected(request.device)) {
            finish(request, EventType.NOT_CONNECTED, attempt = request.attemptCount)
            return
        }

        if (PermissionUtil.isAssociationRequired(context, request.device)) {
            finish(request, EventType.NO_ASSOCIATION)
            return
        }

        var actual: LogCodecConfig? = null
        if (request.attemptCount > 0) {
            val read = a2dp.getCodec(request.device)
            read?.let { knownCodecs[request.device.address.uppercase()] = it }
            actual = read?.let { LogCodecConfig.of(it) }
            operation.log(EventType.VALIDATE_CODEC_CONFIG, config = target, actual = actual, attempt = request.attemptCount)
            if (actual?.own?.let { request.target.isFulfilledBy(it) } == true) {
                request.matchingReads += 1
                if (request.matchingReads >= VERIFYING_READ_COUNT) {
                    finish(request, EventType.CODEC_VERIFIED, actual = actual, attempt = request.attemptCount)
                } else {
                    request.lastStep = now
                }
                return
            }
            // Not the target, or changed back after a matching read: it's set again
            request.matchingReads = 0
        }

        if (request.attemptCount >= MAX_ATTEMPT_COUNT) {
            finish(request, EventType.MAX_ATTEMPTS, config = target, actual = actual, attempt = request.attemptCount)
            return
        }

        request.attemptCount += 1
        operation.log(EventType.SET_CODEC_CONFIG, config = target, attempt = request.attemptCount)
        a2dp.setCodec(request.device, request.target.toAndroid())
        request.lastStep = now
    }

    private class Request(
        val device: BluetoothDevice,
        val target: CodecConfig,
        val operation: EventLog.Operation,
        val onDone: ((EventType) -> Unit)?,
        val onSecurityException: ((EventLog.Operation, SecurityException) -> Unit)?,
        // When the codec was last set or read
        var lastStep: Long = 0,
        var attemptCount: Int = 0,
        // Reads in a row that reported the target since the last attempt
        var matchingReads: Int = 0)
}
