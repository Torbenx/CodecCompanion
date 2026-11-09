package com.tt.autobluetoothcodec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.UserManager
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Keeps the last [MAX_EVENTS] events of what the app did, as JSON Lines in [FILE_NAME]. Events are
 * either standalone or part of an operation, see [OperationFlow]: [begin] starts an operation, and
 * the returned [Operation] logs its elements and its resolution. Breaking that structure is a bug,
 * it throws in debuggable builds and is logged to Logcat otherwise.
 *
 * Logging can be disabled with [setEnabled], which deletes the log. While disabled, events only go to
 * Logcat.
 *
 * Must be used on the main thread. The events and the files are kept on a background thread by an
 * [EventStore], so nothing here waits for the file: [events] is null until the file is loaded, and
 * lags behind the events logged until the background thread has handled them. [flush] runs an
 * action once it has.
 *
 * Before the user unlocks the phone after a reboot, events go to a device protected file, each
 * encrypted by [LockedStorageCrypto] so only the app can read them once the user unlocked. The
 * unlock broadcast queues the load that merges them into the log, or the next event or read that
 * notices the unlock does, if the broadcast was missed.
 */
class EventLog private constructor(private val context: Context) {
    companion object {
        private const val TAG = "EventLog"
        const val MAX_EVENTS = 1000
        // Excluded from backups by backup_rules.xml and data_extraction_rules.xml
        const val DIRECTORY = "logs"
        const val FILE_NAME = "event_log.jsonl"
        // Binds the device protected file's lines to it, see LockedStorageCrypto.encrypt
        internal const val LOCKED_CONTEXT_INFO = "event_log"
        private const val PREFERENCES_NAME = "event_log"
        private const val KEY_ENABLED = "enabled"

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private var dpsPreferences: SharedPreferences? = null

        private var instance: EventLog? = null

        fun get(context: Context): EventLog {
            return instance ?: EventLog(context.applicationContext).also { instance = it }
        }

        /**
         * Waits up to [timeoutMillis] until the events logged so far are written, for components
         * whose end may end the process. Returns false if they aren't written by then, and true right
         * away if the log isn't open in this process.
         */
        fun awaitWritten(timeoutMillis: Long): Boolean {
            val log = instance ?: return true
            return try {
                log.executor.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
                true
            } catch (e: TimeoutException) {
                Log.w(TAG, "the log wasn't written within $timeoutMillis ms")
                false
            }
        }

        /**
         * Drops the log as if the process restarted, for tests. [get] loads it again from the file and
         * the settings. Waits for the pending writes first.
         */
        @VisibleForTesting
        fun restartForTest() {
            instance?.let { log ->
                try {
                    log.unregisterUnlockReceiver()
                } catch (e: IllegalArgumentException) {
                    // Robolectric dropped it with the application of an earlier test
                }
                log.executor.submit {}.get()
                log.executor.shutdown()
            }
            instance = null
            // A new process loads the keys again, the public one from device protected storage
            LockedStorageCrypto.restartForTest()
        }

        /**
         * Deletes the log and its settings, for tests. [get] loads it again from now on. Waits for the pending writes
         * first, so they don't recreate the file.
         */
        @VisibleForTesting
        fun resetForTest(context: Context) {
            restartForTest()
            // Each test has its own context and files
            dpsPreferences = null
            AtomicFile(getFile(context.applicationContext)).delete()
            lockedFile(context).delete()
            preferences(context).edit(commit = true) { clear() }
        }

        /** Parses the lines of the log file, skipping lines that can't be parsed. */
        internal fun parse(lines: List<String>): List<LogEvent> {
            return lines.filter { it.isNotBlank() }.mapNotNull {
                try {
                    json.decodeFromString<LogEvent>(it)
                } catch (e: IllegalArgumentException) {
                    // An interrupted append leaves a partial line
                    null
                }
            }
        }

        /**
         * Keeps the last [max] events, without the events of operations whose trigger was dropped,
         * so every operation in the result starts with its trigger.
         */
        internal fun trim(events: List<LogEvent>, max: Int): List<LogEvent> {
            val kept = if (events.size > max) events.takeLast(max) else events
            val triggered = kept.filter { it.type.flow == OperationFlow.TRIGGER }.mapNotNullTo(HashSet()) { it.opUID }
            return kept.filter { it.opUID == null || it.opUID in triggered }
        }

        /** Returns the trigger events of operations without a resolution. */
        internal fun findUnresolved(events: List<LogEvent>): List<LogEvent> {
            val resolved = events.filter { it.type.flow == OperationFlow.RESOLUTION }.mapNotNullTo(HashSet()) { it.opUID }
            return events.filter { it.type.flow == OperationFlow.TRIGGER && it.opUID !in resolved }
        }

        private fun preferences(context: Context): SharedPreferences {
            return dpsPreferences ?:
                context.applicationContext
                    .createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .also { dpsPreferences = it }
        }

        internal fun getFile(context: Context) = File(File(context.filesDir, DIRECTORY), FILE_NAME)

        /**
         * The device protected file, encrypted. It keeps at least the newest [MAX_EVENTS] lines, like the log
         * itself, since the merge keeps no more than those.
         */
        internal fun lockedFile(context: Context) = LockedFile(
            context.applicationContext, getFile(context.createDeviceProtectedStorageContext()), LOCKED_CONTEXT_INFO,
            MAX_EVENTS)

        internal fun toLogcat(event: LogEvent): String {
            return buildString {
                append(event.type)
                event.opUID?.let { append(" op=").append(it.take(8)) }
                event.device?.let { append(" device=").append(it.address) }
                event.attempt?.let { append(" attempt=").append(it) }
                event.config?.let { append(" config=").append(it.android) }
                event.actual?.let { append(" actual=").append(it.android) }
                event.message?.let { append(": ").append(it) }
            }
        }
    }

    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val executor = Executors.newSingleThreadExecutor()
    private val preferences = preferences(context)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val openOperations = HashSet<String>()
    // Open operations whose trigger is in the log, the events of the others aren't logged
    private val loggedOperations = HashSet<String>()

    // Whether the load of the user protected file is queued, the events after it go to that file
    private var userStorageInitialized: Boolean = false
    // Registered while the user is locked, until the load is queued
    private var unlockReceiver: BroadcastReceiver? = null

    private val mutableEvents = MutableStateFlow<List<LogEvent>?>(null)
    private val readEvents: StateFlow<List<LogEvent>?> = mutableEvents.asStateFlow()

    // Only used on the executor's thread
    private val store = EventStore(
        AtomicFile(getFile(context)), lockedFile(context), mutableEvents)

    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    init {
        val enabled = enabled.value
        executor.execute { store.open(enabled) }
        updateUserUnlocked()
        if (!userStorageInitialized)
            registerUnlockReceiver()
    }

    /** Queues the load when the user unlocks, without waiting for the next event or read. */
    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateUserUnlocked()
            }
        }
        // Only sent to registered receivers. System broadcasts reach unexported receivers too
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        unlockReceiver = receiver
        // The user may have unlocked before the receiver was registered
        updateUserUnlocked()
    }

    private fun unregisterUnlockReceiver() {
        val receiver = unlockReceiver ?: return
        unlockReceiver = null
        context.unregisterReceiver(receiver)
    }

    /**
     * Runs once in each process, when it first notices that the user is unlocked. It's where the app moves what it
     * wrote before the unlock to credential protected storage: the log, and the info of [SavedDeviceStore].
     */
    private fun initWithUnlockedUser() {
        userStorageInitialized = true
        unregisterUnlockReceiver()
        // Before the load needs the private key, and so the public key is there before the next reboot
        LockedStorageCrypto.ensureKeys(context)
        SavedDeviceStore.mergeLockedInfo(context)
        val open = HashSet(openOperations)
        val enabled = enabled.value
        executor.execute { store.load(open, enabled) }
    }

    fun isUserStorageInitialized() = userStorageInitialized

    fun updateUserUnlocked() {
        if (!userStorageInitialized && userManager.isUserUnlocked)
            initWithUnlockedUser()
    }

    /** Starts an operation with a [OperationFlow.TRIGGER] event. */
    fun begin(
        type: EventType,
        device: LogDevice?,
        config: LogCodecConfig? = null,
        message: String? = null,
    ): Operation {
        checkFlow(type, OperationFlow.TRIGGER)
        val operation = Operation(UUID.randomUUID().toString(), device)
        openOperations.add(operation.uid)
        if (enabled.value)
            loggedOperations.add(operation.uid)
        append(type = type, opUID = operation.uid, device = device, config = config, message = message)
        return operation
    }

    /** Logs a [OperationFlow.STANDALONE] event. */
    fun log(
        type: EventType,
        device: LogDevice? = null,
        config: LogCodecConfig? = null,
        actual: LogCodecConfig? = null,
        message: String? = null,
    ) {
        checkFlow(type, OperationFlow.STANDALONE)
        append(type = type, device = device, config = config, actual = actual, message = message)
    }

    /** Removes all events, except those of open operations. */
    fun clear() {
        checkEventAccess()
        val open = HashSet(openOperations)
        executor.execute { store.clear(open) }
    }

    /**
     * Enables or disables logging. Disabling deletes all events, including those of open operations,
     * whose later events aren't logged either.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled == this.enabled.value)
            return
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        mutableEnabled.value = enabled
        if (!enabled) {
            loggedOperations.clear()
            executor.execute { store.deleteAll() }
        }
    }

    /** Runs [action] on the background thread once all events logged so far are written. */
    fun flush(action: Runnable) {
        executor.execute(action)
    }

    /** The events, oldest first, or null until the file is loaded. */
    val events: StateFlow<List<LogEvent>?>
        get() {
            checkEventAccess()
            return readEvents
        }

    private fun checkEventAccess() {
        updateUserUnlocked()
        if (!userStorageInitialized)
            violation("events accessed before user protected storage was unlocked")
    }

    private fun checkFlow(type: EventType, expected: OperationFlow) {
        if (type.flow != expected)
            violation("$type is a ${type.flow} event, expected a $expected event")
    }

    private fun violation(message: String) {
        if (debuggable)
            throw IllegalStateException(message)
        Log.w(TAG, "log structure violated: $message")
    }

    /** Logs [event] to Logcat, and to the log if it's [logged] and logging is enabled. */
    private fun append(
        type: EventType,
        opUID: String? = null,
        device: LogDevice? = null,
        config: LogCodecConfig? = null,
        actual: LogCodecConfig? = null,
        attempt: Int? = null,
        message: String? = null,
        logged: Boolean = true
    ) {
        updateUserUnlocked()
        val event = LogEvent(
            v = LogEvent.VERSION,
            // Numbered by the store
            seq = 0,
            time = System.currentTimeMillis(),
            type = type,
            opUID = opUID,
            device = device,
            config = config,
            actual = actual,
            attempt = attempt,
            message = message
        )
        Log.i(TAG, toLogcat(event))
        if (!logged || !enabled.value)
            return
        executor.execute { store.append(event) }
    }

    /**
     * An operation started by [begin]. Its events carry its uid and the device it was started with.
     */
    inner class Operation internal constructor(val uid: String, val device: LogDevice?) {
        val isOpen get() = uid in openOperations

        /** Logs an [OperationFlow.ELEMENT] event. */
        fun log(
            type: EventType,
            config: LogCodecConfig? = null,
            actual: LogCodecConfig? = null,
            attempt: Int? = null,
            message: String? = null,
        ) {
            checkFlow(type, OperationFlow.ELEMENT)
            if (!isOpen)
                violation("$type logged to resolved operation $uid")
            append(
                type = type, opUID = uid, device = device, config = config, actual = actual,
                attempt = attempt, message = message, logged = uid in loggedOperations)
        }

        /** Ends the operation with a [OperationFlow.RESOLUTION] event. */
        fun resolve(
            type: EventType,
            config: LogCodecConfig? = null,
            actual: LogCodecConfig? = null,
            attempt: Int? = null,
            message: String? = null,
        ) {
            checkFlow(type, OperationFlow.RESOLUTION)
            append(
                type = type, opUID = uid, device = device, config = config, actual = actual,
                attempt = attempt, message = message, logged = loggedOperations.remove(uid))
            if (!openOperations.remove(uid))
                violation("$type resolves operation $uid, which is resolved already")
        }
    }
}
