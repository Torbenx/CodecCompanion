package com.tt.autobluetoothcodec

import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Shows the [EventLog], newest first. An operation is shown as one entry, collapsed to a one line
 * summary. Tapping it expands it to all its events, tapping it again collapses it. Long pressing an
 * event shows its JSON.
 *
 * Started with [EXTRA_LOG_FILE], it shows a log file instead, for example an export of another phone.
 */
class LogActivity : AppActivity() {
    companion object {
        private const val TAG = "LogActivity"

        /** The content URI of a JSON Lines log file to show instead of the app's log. */
        const val EXTRA_LOG_FILE = "com.tt.autobluetoothcodec.extra.LOG_FILE"
    }

    private val log by lazy { EventLog.get(this) }

    // Development options are only shown in debuggable builds
    private val showDevelopmentOptions by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val logFile by lazy { IntentCompat.getParcelableExtra(intent, EXTRA_LOG_FILE, Uri::class.java) }
    private var fileName by mutableStateOf<String?>(null)
    private var fileEvents by mutableStateOf<List<LogEvent>?>(null)

    private val actions = object : LogScreenActions {
        override fun back() = finish()
        override fun share(mode: LogExport.Mode) = shareLog(mode)
        override fun clear() = log.clear()
        override fun setLoggingEnabled(enabled: Boolean) = log.setEnabled(enabled)
        override fun openLogFile() = openFileLauncher.launch(arrayOf("*/*"))
    }

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null)
            return@registerForActivityResult
        // The picker's grant ends with this activity, the clip data grants the new one its own
        val intent = Intent(this, LogActivity::class.java)
            .putExtra(EXTRA_LOG_FILE, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(null, uri)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val file = logFile
        if (file != null)
            loadFile(file)
        setContent {
            AppTheme {
                if (file != null) {
                    LogScreen(fileEvents, LocalDate.now(), actions, fileName = fileName ?: "Log file")
                } else {
                    val events by log.events.collectAsState()
                    val enabled by log.enabled.collectAsState()
                    LogScreen(events, LocalDate.now(), actions, enabled, showDevelopmentOptions)
                }
            }
        }
    }

    private fun loadFile(uri: Uri) {
        lifecycleScope.launch {
            val (name, events) = try {
                withContext(Dispatchers.IO) {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    val lines = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() }
                        ?: throw IOException("no stream for $uri")
                    name to EventLog.parse(lines)
                }
            } catch (e: IOException) {
                failLoading(e)
                return@launch
            } catch (e: SecurityException) {
                // The grant is gone, for example after the process was restarted
                failLoading(e)
                return@launch
            }
            fileName = name
            fileEvents = events
        }
    }

    private fun failLoading(e: Exception) {
        Log.e(TAG, "reading the log file failed", e)
        Toast.makeText(this, "Reading the log file failed", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun shareLog(mode: LogExport.Mode) {
        // Sharing is only offered once the events are loaded
        val events = log.events.value ?: return
        val uri = try {
            LogExport.write(this, events, mode)
        } catch (e: IOException) {
            Log.e(TAG, "exporting the log failed", e)
            Toast.makeText(this, "Exporting the log failed", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share log"))
    }
}

// Shown in the error color
private val FAILURES = setOf(
    EventType.ASSOCIATION_FAILED,
    EventType.MAX_ATTEMPTS,
    EventType.NO_ASSOCIATION,
    EventType.NO_PERMISSION,
    EventType.SECURITY_EXCEPTION,
    EventType.PROFILE_UNAVAILABLE,
    EventType.PROFILE_DISCONNECTED,
    EventType.TIMEOUT,
    EventType.ERROR,
    EventType.OPERATION_INTERRUPTED,
    EventType.READ_FAILED,
    EventType.OBSERVING_FAILED,
)

// Their message is part of the description, so it isn't shown separately
private val MESSAGE_DESCRIBED = setOf(
    EventType.PERMISSION_CHANGED,
    EventType.OBSERVING_CHANGED,
    EventType.TEST_LABEL,
)

@OptIn(ExperimentalSerializationApi::class)
private val prettyJson = Json(from = EventLog.json) {
    prettyPrint = true
    prettyPrintIndent = "  "
}

// Created when used, so they follow a change of the locale
private val timeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
private val dateFormatter get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

/** What the log screen's controls do, implemented by [LogActivity]. */
internal interface LogScreenActions {
    fun back()
    fun share(mode: LogExport.Mode)
    fun clear()
    fun setLoggingEnabled(enabled: Boolean)
    fun openLogFile()
}

/** The events of an operation in order, or a single standalone event. */
private class LogEntry(val events: List<LogEvent>) {
    val first get() = events.first()
    val isOperation get() = first.type.flow == OperationFlow.TRIGGER
    val resolution get() = events.lastOrNull { it.type.flow == OperationFlow.RESOLUTION }
}

private fun buildEntries(events: List<LogEvent>): List<LogEntry> {
    val operations = HashMap<String, MutableList<LogEvent>>()
    val entries = ArrayList<MutableList<LogEvent>>()
    for (event in events) {
        val uid = event.opUID
        val operation = uid?.let { operations[it] }
        if (operation != null) {
            operation.add(event)
        } else {
            val entry = mutableListOf(event)
            if (uid != null)
                operations[uid] = entry
            entries.add(entry)
        }
    }
    return entries.map { LogEntry(it) }
}

private fun describe(event: LogEvent): String {
    val config = event.config?.describe()
    val actual = event.actual?.describe() ?: "no codec"
    return when (event.type) {
        EventType.DEVICE_APPEARED -> "Device connected"
        EventType.A2DP_CONNECTED -> "Audio connected"
        EventType.USER_SET_CODEC -> "Codec chosen: $config"
        EventType.USER_RESTORE_PREFERRED -> "Restoring preferred codec: $config"
        EventType.USER_SAVE_PREFERRED -> "Saving preferred codec: $config"
        EventType.USER_LINK_DEVICE -> "Linking device"
        EventType.SET_CODEC_CONFIG -> "Setting codec" + (event.attempt?.let { ", attempt $it" } ?: "") + ": $config"
        EventType.VALIDATE_CODEC_CONFIG -> when {
            event.actual == null -> "Codec change incomplete, no codec reported"
            event.actual.own?.let { event.config?.own?.isFulfilledBy(it) } != true -> "Codec change incomplete, active: $actual"
            else -> "Codec validated: $actual"
        }
        EventType.ASSOCIATION_REQUESTED -> "Asked to link the device"
        EventType.CODEC_VERIFIED -> "Codec applied: $actual"
        EventType.PREFERRED_SAVED -> "Preferred codec saved"
        EventType.ASSOCIATION_CREATED -> "Device linked"
        EventType.ASSOCIATION_FAILED -> "Linking device failed"
        EventType.MAX_ATTEMPTS -> "Codec not applied after ${event.attempt} attempts" +
                (event.actual?.let { ", still ${it.describe()}" } ?: "")
        EventType.NOT_CONNECTED -> "Audio not connected yet"
        EventType.NO_ASSOCIATION -> "Device isn't linked"
        EventType.NO_PREFERRED_CODEC -> "No preferred codec"
        EventType.NO_PERMISSION -> "Nearby devices permission missing"
        EventType.SECURITY_EXCEPTION -> "Bluetooth refused the codec change"
        EventType.PROFILE_UNAVAILABLE -> "Bluetooth audio unavailable"
        EventType.PROFILE_DISCONNECTED -> "Bluetooth audio service disconnected"
        EventType.TIMEOUT -> "Timed out"
        EventType.CANCELLED -> "Cancelled"
        EventType.ALREADY_PENDING -> "Already in progress"
        EventType.SUPERSEDED -> "Replaced by a newer request"
        EventType.ERROR -> "Failed"
        EventType.OPERATION_INTERRUPTED -> "Interrupted, app was stopped"
        EventType.DEVICE_DISAPPEARED -> "Device disconnected"
        EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE -> "App read the codec: $actual"
        EventType.READ_FAILED -> "Couldn't read the codec"
        EventType.PREFERRED_DELETED -> "Preferred codec deleted"
        EventType.DEVICE_UNLINKED -> "Device unlinked"
        EventType.PERMISSION_CHANGED -> "Nearby devices permission ${event.message}"
        EventType.OBSERVING_CHANGED -> "Presence: ${event.message}"
        EventType.OBSERVING_FAILED -> "Couldn't observe the device's presence"
        EventType.TEST_LABEL -> "Test: ${event.message}"
    }
}

/** The time from the trigger to the resolution, null if it isn't worth showing. */
private fun describeDuration(entry: LogEntry): String? {
    val resolution = entry.resolution ?: return null
    // Resolved when the app started again, which says nothing about the operation
    if (resolution.type == EventType.OPERATION_INTERRUPTED)
        return null
    val millis = resolution.time - entry.first.time
    return when {
        millis < 100 -> null
        millis < 60_000 -> String.format(Locale.getDefault(), "%.1f s", millis / 1000.0)
        millis < 3_600_000 -> "${millis / 60_000} min ${millis / 1000 % 60} s"
        else -> "${millis / 3_600_000} h ${millis / 60_000 % 60} min"
    }
}

private fun describeOperation(entry: LogEntry): String {
    val trigger = describe(entry.first)
    val resolution = entry.resolution ?: return "$trigger → in progress"
    return "$trigger → ${describe(resolution)}" + (describeDuration(entry)?.let { " ($it)" } ?: "")
}

private fun zoned(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())

private fun describeDay(day: LocalDate, today: LocalDate): String {
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dateFormatter.format(day)
    }
}

@Composable
private fun eventColor(type: EventType): Color {
    return if (type in FAILURES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
}

/** The width of the column the times are in, null if each time is above its text instead. */
private val LocalTimeColumn = staticCompositionLocalOf<Dp?> { null }

/** How far the lines below an event's text are indented, so they line up with the text. */
private val timeIndent: Dp
    @Composable @ReadOnlyComposable get() = LocalTimeColumn.current ?: 0.dp

// The widest times of a day, in the 12- and the 24-hour format
private val WIDEST_TIMES = listOf(LocalTime.of(10, 58, 58), LocalTime.of(22, 58, 58))

/**
 * The width of the time column for the current locale and font size: the widest time and a gap. Null if that
 * would take more than a third of [availableWidth], with large fonts or on small screens, so the times go above
 * their texts.
 */
@Composable
private fun timeColumnWidth(availableWidth: Dp): Dp? {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val locale = LocalConfiguration.current.locales[0]
    val width = remember(measurer, style, density, locale) {
        val pixels = WIDEST_TIMES.maxOf { measurer.measure(timeFormatter.format(it), style, softWrap = false).size.width }
        with(density) { pixels.toDp() } + 12.dp
    }
    return if (width <= availableWidth / 3) width else null
}

/** An event's time and text, side by side in the time column or the time above the text, see [LocalTimeColumn]. */
@Composable
private fun TimedText(time: Long, text: String, color: Color) {
    val column = LocalTimeColumn.current
    val timeText = @Composable { modifier: Modifier ->
        Text(
            timeFormatter.format(zoned(time)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.testTag("log_time")
        )
    }
    if (column == null) {
        Column {
            timeText(Modifier)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    } else {
        // The time is smaller than the text, aligning their baselines keeps it on the first line
        Row {
            timeText(Modifier.width(column).alignByBaseline())
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.alignByBaseline())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(event: LogEvent, suffix: String = "", onClickLabel: String? = null, onClick: (() -> Unit)? = null) {
    // Unlike remember, survives the row being scrolled out of view
    var showJson by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // The row takes the taps inside it, so it passes them on to its entry, unless they close the JSON
    val tap = if (showJson) ({ showJson = false }) else onClick
    // Event rows don't ripple, the change they make shows itself
    val gestures = if (tap != null) {
        Modifier.combinedClickable(
            interactionSource = null,
            indication = null,
            onClickLabel = if (showJson) "Hide JSON" else onClickLabel,
            onLongClickLabel = if (showJson) "Hide JSON" else "Show JSON",
            onLongClick = { showJson = !showJson },
            onClick = tap
        )
    } else {
        // Only the long press does something, handling just that means accessibility services offer
        // no tap
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    showJson = true
                })
            }
            .semantics(mergeDescendants = true) {
                onLongClick("Show JSON") {
                    showJson = true
                    true
                }
            }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(gestures)
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        TimedText(event.time, describe(event) + suffix, eventColor(event.type))
        val message = event.message
        if (message != null && event.type !in MESSAGE_DESCRIBED) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = timeIndent)
            )
        }
        if (showJson) {
            // Scrolls instead of wrapping, wrapped JSON is hard to read
            Text(
                prettyJson.encodeToString(event),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier
                    .padding(start = timeIndent, top = 4.dp)
                    .horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun EntryItem(entry: LogEntry) {
    // Unlike remember, survives the entry being scrolled out of view and the screen being rotated
    var expanded by rememberSaveable { mutableStateOf(false) }
    // A tap anywhere on an operation toggles it, its event rows pass their taps on
    val toggleLabel = if (expanded) "Hide events" else "Show events"
    val toggle = if (!entry.isOperation) Modifier else Modifier
        .clickable(onClickLabel = toggleLabel, role = Role.Button) { expanded = !expanded }
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
    Column(Modifier.fillMaxWidth().then(toggle).animateContentSize().testTag("log_entry_${entry.first.seq}")) {
        val device = entry.first.device
        if (device != null)
            DeviceHeader(device, arrow = entry.isOperation, expanded = expanded)
        else
            Spacer(Modifier.height(8.dp))
        if (!entry.isOperation) {
            EventRow(entry.first)
        } else if (!expanded) {
            OperationSummary(entry)
        } else {
            val resolution = entry.resolution
            val duration = describeDuration(entry)?.let { " ($it)" } ?: ""
            // Rows created in a loop need a key to tell their saved state apart
            for (event in entry.events) key(event.seq) {
                EventRow(event, if (event === resolution) duration else "", toggleLabel) { expanded = false }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DeviceHeader(device: LogDevice, arrow: Boolean, expanded: Boolean) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow rotation")
    // The top padding is part of the header, so it's easier to tap
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Doesn't fill the row, so the arrow stays next to the name, but a long name wraps before it
        Text(
            device.name ?: device.address,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (arrow) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).size(20.dp).rotate(rotation)
            )
        }
    }
}

@Composable
private fun OperationSummary(entry: LogEntry) {
    val resolution = entry.resolution
    // Padded like EventRow, so the summary and the trigger's row line up when expanding
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        TimedText(
            entry.first.time,
            describeOperation(entry),
            if (resolution != null) eventColor(resolution.type) else MaterialTheme.colorScheme.onSurface
        )
        // "Failed" alone doesn't say what went wrong
        val message = resolution?.message
        if (resolution?.type == EventType.ERROR && message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = timeIndent)
            )
        }
    }
}

@Composable
private fun ExportOption(selected: Boolean, title: String, description: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExportDialog(onDismiss: () -> Unit, onShare: (LogExport.Mode) -> Unit) {
    var mode by remember { mutableStateOf(LogExport.Mode.ANONYMISED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share log") },
        text = {
            Column(Modifier.selectableGroup()) {
                ExportOption(
                    selected = mode == LogExport.Mode.ANONYMISED,
                    title = "Anonymised (recommended)",
                    description = "Bluetooth addresses are replaced by random ids and device names are removed. Use this when posting the log for diagnosis.",
                    onSelect = { mode = LogExport.Mode.ANONYMISED }
                )
                ExportOption(
                    selected = mode == LogExport.Mode.FULL,
                    title = "Full",
                    description = "Includes the Bluetooth addresses and names of your devices.",
                    onSelect = { mode = LogExport.Mode.FULL }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onShare(mode)
            }) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ClearDialog(onDismiss: () -> Unit, onClear: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear log?") },
        text = { Text("All logged events are deleted.") },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onClear()
            }) { Text("Clear") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DisableLoggingDialog(onDismiss: () -> Unit, onDisable: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disable logging?") },
        text = {
            Text(
                "The log is only kept on this phone and is never shared automatically, only when you share it " +
                        "yourself. It's limited to the last ${EventLog.MAX_EVENTS} events and is primarily meant " +
                        "for diagnosing problems.\n\nDisabling logging deletes all logged events."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onDisable()
            }) { Text("Delete & Disable") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep enabled") }
        }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LogMenu(
    canClear: Boolean,
    loggingEnabled: Boolean,
    showDevelopmentOptions: Boolean,
    onClear: () -> Unit,
    onDisableLogging: () -> Unit,
    onEnableLogging: () -> Unit,
    onOpenFile: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("log_menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        // A menu is a popup, outside the Scaffold that exposes the test tags to UiAutomator
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            DropdownMenuItem(
                text = { Text("Clear log") },
                enabled = canClear,
                modifier = Modifier.testTag("clear_log"),
                onClick = {
                    expanded = false
                    onClear()
                }
            )
            if (loggingEnabled) {
                DropdownMenuItem(
                    text = { Text("Disable logging") },
                    modifier = Modifier.testTag("disable_logging"),
                    onClick = {
                        expanded = false
                        onDisableLogging()
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Enable logging") },
                    modifier = Modifier.testTag("enable_logging"),
                    onClick = {
                        expanded = false
                        onEnableLogging()
                    }
                )
            }
            if (showDevelopmentOptions) {
                DropdownMenuItem(
                    text = { Text("Open Log (Debug)") },
                    modifier = Modifier.testTag("open_log_file"),
                    onClick = {
                        expanded = false
                        onOpenFile()
                    }
                )
            }
        }
    }
}

/**
 * The log screen, [events] newest first, null while they're loading. [today] decides which day is
 * shown as today. With a [fileName] it shows the events of that file instead of the app's log, read
 * only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun LogScreen(
    events: List<LogEvent>?,
    today: LocalDate,
    actions: LogScreenActions,
    loggingEnabled: Boolean = true,
    showDevelopmentOptions: Boolean = false,
    fileName: String? = null,
) {
    var showExportDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    val ownLog = fileName == null
    val listState = rememberLazyListState()
    // The list keeps where it was scrolled to after the log was cleared, which shows no list
    val scrolled = !events.isNullOrEmpty() && listState.canScrollBackward
    Scaffold(
        // Lets UiAutomator find the elements by their test tags
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(
                title = { Text(fileName ?: "Log", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = topBarColors(scrolled),
                navigationIcon = {
                    IconButton(onClick = actions::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // An opened file is only shown, another one is opened from the app's log
                    if (ownLog) {
                        IconButton(onClick = { showExportDialog = true }, enabled = !events.isNullOrEmpty(), modifier = Modifier.testTag("share_log")) {
                            Icon(Icons.Default.Share, contentDescription = "Share log")
                        }
                        LogMenu(
                            canClear = !events.isNullOrEmpty(),
                            loggingEnabled = loggingEnabled,
                            showDevelopmentOptions = showDevelopmentOptions,
                            onClear = { showClearDialog = true },
                            onDisableLogging = { showDisableDialog = true },
                            onEnableLogging = { actions.setLoggingEnabled(true) },
                            onOpenFile = actions::openLogFile
                        )
                    }
                }
            )
        },
        // Also keeps the content clear of display cutouts in landscape
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        // The bottom padding is inside the list, so the entries scroll behind the navigation bar
        CappedContent(WIDE_CONTENT, tintSides = true) { side ->
            Column(
                Modifier.fillMaxSize().padding(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(layoutDirection)
                )
            ) {
                if (events == null)
                    return@Column
                val entries = remember(events) { buildEntries(events).reversed() }
                if (entries.isEmpty()) {
                    val text = when {
                        !ownLog -> "No events in this file"
                        !loggingEnabled -> "Logging is disabled"
                        else -> "No events logged yet"
                    }
                    Box(Modifier.fillMaxSize().padding(horizontal = side).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                    return@Column
                }
                BoxWithConstraints {
                    CompositionLocalProvider(LocalTimeColumn provides timeColumnWidth(maxWidth - side * 2)) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(start = side, end = side, bottom = innerPadding.calculateBottomPadding()),
                            verticalArrangement = Arrangement.Top
                        ) {
                            var day: LocalDate? = null
                            for (entry in entries) {
                                val entryDay = zoned(entry.first.time).toLocalDate()
                                if (entryDay != day) {
                                    day = entryDay
                                    item(key = "day $entryDay") {
                                        Text(
                                            describeDay(entryDay, today),
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                                        )
                                    }
                                } else {
                                    item(key = "divider ${entry.first.seq}") { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
                                }
                                item(key = entry.first.seq) { EntryItem(entry) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showExportDialog)
        ExportDialog(onDismiss = { showExportDialog = false }, onShare = actions::share)
    if (showClearDialog)
        ClearDialog(onDismiss = { showClearDialog = false }, onClear = actions::clear)
    if (showDisableDialog)
        DisableLoggingDialog(onDismiss = { showDisableDialog = false }, onDisable = { actions.setLoggingEnabled(false) })
}
