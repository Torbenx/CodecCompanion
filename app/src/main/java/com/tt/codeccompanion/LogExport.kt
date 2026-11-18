package com.tt.codeccompanion

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Writes the event log to a JSON Lines file that can be shared. */
object LogExport {
    enum class Mode {
        // Bluetooth addresses are replaced by random ids and device names are removed
        ANONYMISED,
        FULL
    }

    // Shared through the FileProvider, see file_paths.xml
    private const val DIRECTORY = "exports"
    private const val NAME_PLACEHOLDER = "[name removed]"
    private val MAC_ADDRESS = Regex("""\b[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}\b""")
    // No hex digits and no letters that look like digits, so an id never looks like an address
    private const val ID_ALPHABET = "GHJKMNPQRSTUVWXYZ"
    private const val ID_LENGTH = 4

    /** Writes [events] to a new file in the cache and returns its content URI. */
    fun write(context: Context, events: List<LogEvent>, mode: Mode): Uri {
        val directory = File(context.cacheDir, DIRECTORY)
        // Earlier exports were shared already
        directory.deleteRecursively()
        directory.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "codec-companion-log-$timestamp.jsonl")
        file.writeText(toJsonLines(events, mode))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun toJsonLines(events: List<LogEvent>, mode: Mode, random: Random = Random.Default): String {
        val exported = if (mode == Mode.ANONYMISED) anonymise(events, random) else events
        return exported.joinToString("") { EventLog.json.encodeToString(it) + "\n" }
    }

    /**
     * Replaces each address with an id like "device-KQXT", the same address always with the same
     * id. Names are removed from the devices and from the messages, which may contain addresses too.
     */
    internal fun anonymise(events: List<LogEvent>, random: Random): List<LogEvent> {
        val ids = HashMap<String, String>()
        fun idOf(address: String): String = ids.getOrPut(address.uppercase()) {
            generateSequence {
                "device-" + (1..ID_LENGTH).map { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] }.joinToString("")
            }.first { it !in ids.values }
        }
        // Longer names first, so a name containing another one is removed entirely
        val names = events.mapNotNull { it.device?.name }.filter { it.isNotBlank() }.toSet().sortedByDescending { it.length }
        fun scrub(text: String): String {
            var result = MAC_ADDRESS.replace(text) { idOf(it.value) }
            for (name in names)
                result = result.replace(name, NAME_PLACEHOLDER)
            return result
        }
        return events.map { event ->
            event.copy(
                device = event.device?.let { LogDevice(idOf(it.address)) },
                message = event.message?.let { scrub(it) }
            )
        }
    }
}
