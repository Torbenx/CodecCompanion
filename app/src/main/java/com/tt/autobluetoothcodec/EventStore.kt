package com.tt.autobluetoothcodec

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * The events and files of the [EventLog], kept on its background thread: every function must be
 * called on that thread, the [EventLog] queues the calls in order. Until [load] reads the user
 * protected file, events are appended to the device protected file, encrypted, see [LockedFile].
 * [load] merges them into the log, from then on the events are kept in memory as well and
 * published to [published].
 */
internal class EventStore(
    private val upsFile: AtomicFile,
    private val dpsFile: LockedFile,
    private val published: MutableStateFlow<List<LogEvent>?>,
) {
    companion object {
        private const val TAG = "EventLog"
    }

    private var loaded = false
    private var events = emptyList<LogEvent>()
    private var nextSeq = 0L
    // Lines in the file, which may contain more events than are kept until it's rewritten
    private var fileLineCount = 0
    // Whether events merged from the device protected file aren't in the user protected file yet.
    // The device protected file is kept until they are, so they survive the process dying first
    private var dpsMergePending = false

    /** Deletes the device protected file if logging is disabled. */
    fun open(enabled: Boolean) {
        // Left over if the process died before deleting it
        if (!enabled)
            dpsFile.delete()
    }

    /**
     * Reads the user protected file and merges the events of the device protected file into it,
     * publishes the events, then rewrites the file if the merge changed it. The device protected
     * file is only deleted once its events are written. Operations without a resolution are
     * interrupted, except [openOperations]: those were open in this process when the load was
     * queued, their later events follow it.
     */
    fun load(openOperations: Set<String>, enabled: Boolean) {
        loaded = true
        upsFile.baseFile.parentFile?.mkdirs()
        // Left over if the process died before deleting it
        if (!enabled)
            upsFile.delete()

        val upsLines = try {
            upsFile.openRead().bufferedReader().use { it.readLines() }
        } catch (e: FileNotFoundException) {
            emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "reading the user protected log failed", e)
            emptyList()
        }
        val upsEvents = EventLog.parse(upsLines)
        val upsParseDroppedLines = upsEvents.size != upsLines.size
        nextSeq = (upsEvents.maxOfOrNull { it.seq } ?: -1) + 1

        val dpsLines = dpsFile.read().map { it.decodeToString() }
        val dpsEvents = EventLog.parse(dpsLines).map { it.copy(seq = nextSeq++) }
        // Deleted by the rewrite below once the events are written, which they always cause
        if (dpsEvents.isEmpty())
            dpsFile.delete()
        else
            dpsMergePending = true

        val allFileEvents = upsEvents + dpsEvents
        // Add interruption events for stale operations
        val interruptionEvents = EventLog.findUnresolved(allFileEvents)
            .filter { it.opUID !in openOperations }
            .map {
                val event = LogEvent(
                    LogEvent.VERSION, nextSeq++,
                    System.currentTimeMillis(), EventType.OPERATION_INTERRUPTED,
                    opUID = it.opUID, device = it.device)
                Log.i(TAG, EventLog.toLogcat(event))
                event
            }

        val allEvents = allFileEvents + interruptionEvents
        events = EventLog.trim(allEvents, EventLog.MAX_EVENTS)
        // Before the rewrite, which takes about a third of the load
        published.value = events
        fileLineCount = allEvents.size
        if (
            events.size != allEvents.size ||
            interruptionEvents.isNotEmpty() || dpsEvents.isNotEmpty() ||
            upsParseDroppedLines
        ) {
            rewrite()
        }
    }

    /** Numbers [event] and appends it to the log. */
    fun append(event: LogEvent) {
        val numbered = event.copy(seq = nextSeq++)
        val json = EventLog.json.encodeToString(numbered)
        if (!loaded) {
            dpsFile.append(json.toByteArray())
            return
        }

        events = EventLog.trim(events + numbered, EventLog.MAX_EVENTS)
        published.value = events
        if (fileLineCount + 1 >= 2 * EventLog.MAX_EVENTS) {
            rewrite()
            return
        }
        fileLineCount += 1
        try {
            FileOutputStream(upsFile.baseFile, true).use { it.write((json + "\n").toByteArray()) }
        } catch (e: IOException) {
            Log.w(TAG, "appending to the user protected log file failed", e)
        }
    }

    /** Removes all events, except those of [keptOperations]. Does nothing before the load. */
    fun clear(keptOperations: Set<String>) {
        if (!loaded)
            return
        events = events.filter { it.opUID in keptOperations }
        published.value = events
        rewrite()
    }

    /** Deletes all events and both files. */
    fun deleteAll() {
        events = emptyList()
        fileLineCount = 0
        dpsMergePending = false
        if (loaded)
            published.value = events
        dpsFile.delete()
        upsFile.delete()
    }

    private fun rewrite() {
        fileLineCount = events.size
        val stream = try {
            upsFile.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "rewriting the log failed", e)
            return
        }
        try {
            val writer = stream.bufferedWriter()
            for (event in events) {
                writer.write(EventLog.json.encodeToString(event))
                writer.write("\n")
            }
            writer.flush()
            upsFile.finishWrite(stream)
        } catch (e: IOException) {
            Log.w(TAG, "rewriting the log failed", e)
            upsFile.failWrite(stream)
            // A later rewrite writes the merged events, the device protected file stays until then
            return
        }
        if (dpsMergePending) {
            dpsFile.delete()
            dpsMergePending = false
        }
    }
}
