package com.tt.autobluetoothcodec

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/**
 * V8 to V11 of the test concept: the log screen's texts, structure, updates and dialogs. Dates and numbers are
 * formatted for the phone's locale and time zone, so the tests set both.
 */
@RunWith(AndroidJUnit4::class)
class LogScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 27)
    private val noon = Instant.parse("2026-10-27T12:00:00Z").toEpochMilli()
    private val device = LogDevice("0A:1B:2C:3D:4E:5F", "Qudelix-5K")

    private val sbc = LogCodecConfig.of(AndroidCodecConfig(CodecConfig.CODEC_TYPE_SBC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16))
    private val ldac = LogCodecConfig.of(AndroidCodecConfig(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_24))
    private val sbcText = "SBC, 44100 Hz, 16-bit"
    private val ldacText = "LDAC, 96000 Hz, 24-bit"

    private val actions = object : LogScreenActions {
        val calls = ArrayList<String>()
        override fun back() { calls.add("back") }
        override fun share(mode: LogExport.Mode) { calls.add("share $mode") }
        override fun clear() { calls.add("clear") }
        override fun setLoggingEnabled(enabled: Boolean) { calls.add("setLoggingEnabled $enabled") }
        override fun openLogFile() { calls.add("openLogFile") }
    }

    private lateinit var locale: Locale
    private lateinit var timeZone: TimeZone

    @Before
    fun setUp() {
        locale = Locale.getDefault()
        timeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(locale)
        TimeZone.setDefault(timeZone)
    }

    private var seq = 0L

    private fun event(
        type: EventType,
        opUID: String? = null,
        at: Long = 0,
        config: LogCodecConfig? = null,
        actual: LogCodecConfig? = null,
        attempt: Int? = null,
        message: String? = null,
        device: LogDevice? = this.device,
    ) = LogEvent(LogEvent.VERSION, seq++, noon + at, type, opUID, device, config, actual, attempt, message)

    /** An operation from [trigger] to [resolution], [resolveAfter] milliseconds later. */
    private fun operation(
        trigger: EventType,
        resolution: EventType?,
        resolveAfter: Long = 0,
        config: LogCodecConfig? = null,
        actual: LogCodecConfig? = null,
        attempt: Int? = null,
        message: String? = null,
        elements: List<Pair<EventType, LogEvent.() -> LogEvent>> = emptyList(),
    ): List<LogEvent> {
        val uid = "op$seq"
        val events = mutableListOf(event(trigger, uid, config = config))
        for ((type, change) in elements)
            events.add(event(type, uid).change())
        if (resolution != null)
            events.add(event(resolution, uid, at = resolveAfter, actual = actual, attempt = attempt, message = message))
        return events
    }

    private fun show(events: List<LogEvent>) = compose.setContent { LogScreen(events, today, actions) }

    private val list get() = compose.onNode(hasScrollToKeyAction())

    /** Scrolls to the entry with [text] and checks it's there. */
    private fun assertShown(text: String) = assertShown(hasText(text))

    private fun assertShown(matcher: SemanticsMatcher) {
        list.performScrollToNode(matcher)
        compose.onAllNodes(matcher).onFirst().assertExists()
    }

    // V8

    @Test
    fun standaloneEventTexts() {
        show(listOf(
            event(EventType.DEVICE_APPEARED),
            event(EventType.DEVICE_DISAPPEARED),
            event(EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE, actual = sbc),
            event(EventType.READ_FAILED, message = "java.lang.SecurityException: refused"),
            event(EventType.PREFERRED_DELETED, config = sbc),
            event(EventType.DEVICE_UNLINKED),
            event(EventType.PERMISSION_CHANGED, device = null, message = "granted"),
            event(EventType.OBSERVING_CHANGED, message = "observing presence"),
            event(EventType.OBSERVING_FAILED, message = "not associated"),
            event(EventType.TEST_LABEL, device = null, message = "step 1"),
        ))
        for (text in listOf(
            "Device connected",
            "Device disconnected",
            "App read the codec: $sbcText",
            "Couldn't read the codec",
            "java.lang.SecurityException: refused",
            "Preferred codec deleted",
            "Device unlinked",
            "Nearby devices permission granted",
            "Presence: observing presence",
            "Couldn't observe the device's presence",
            "not associated",
            "Test: step 1",
        )) assertShown(text)
        // Messages that are part of the text aren't shown again
        compose.onNodeWithText("granted", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("step 1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun resolutionTexts() {
        val expected = linkedMapOf(
            EventType.CODEC_VERIFIED to "Codec applied: $sbcText",
            EventType.PREFERRED_SAVED to "Preferred codec saved",
            EventType.ASSOCIATION_CREATED to "Device linked",
            EventType.ASSOCIATION_FAILED to "Linking device failed",
            EventType.MAX_ATTEMPTS to "Codec not applied after 5 attempts, still $ldacText",
            EventType.NOT_CONNECTED to "Audio not connected yet",
            EventType.NO_ASSOCIATION to "Device isn't linked",
            EventType.NO_PREFERRED_CODEC to "No preferred codec",
            EventType.NO_PERMISSION to "Nearby devices permission missing",
            EventType.SECURITY_EXCEPTION to "Bluetooth refused the codec change",
            EventType.PROFILE_UNAVAILABLE to "Bluetooth audio unavailable",
            EventType.PROFILE_DISCONNECTED to "Bluetooth audio service disconnected",
            EventType.TIMEOUT to "Timed out",
            EventType.CANCELLED to "Cancelled",
            EventType.ALREADY_PENDING to "Already in progress",
            EventType.SUPERSEDED to "Replaced by a newer request",
            EventType.ERROR to "Failed",
            EventType.OPERATION_INTERRUPTED to "Interrupted, app was stopped",
        )
        show(expected.keys.flatMap { type ->
            when (type) {
                EventType.CODEC_VERIFIED -> operation(EventType.A2DP_CONNECTED, type, actual = sbc)
                EventType.MAX_ATTEMPTS -> operation(EventType.A2DP_CONNECTED, type, actual = ldac, attempt = 5)
                else -> operation(EventType.A2DP_CONNECTED, type)
            }
        })
        for (text in expected.values)
            assertShown("Audio connected → $text")
    }

    @Test
    fun triggerTexts() {
        show(listOf(
            operation(EventType.USER_SET_CODEC, EventType.CODEC_VERIFIED, config = sbc, actual = sbc),
            operation(EventType.USER_RESTORE_PREFERRED, EventType.CODEC_VERIFIED, config = sbc, actual = sbc),
            operation(EventType.USER_SAVE_PREFERRED, EventType.PREFERRED_SAVED, config = sbc),
            operation(EventType.USER_LINK_DEVICE, EventType.ASSOCIATION_CREATED),
            operation(EventType.A2DP_CONNECTED, null),
        ).flatten())
        for (text in listOf(
            "Codec chosen: $sbcText → Codec applied: $sbcText",
            "Restoring preferred codec: $sbcText → Codec applied: $sbcText",
            "Saving preferred codec: $sbcText → Preferred codec saved",
            "Linking device → Device linked",
            "Audio connected → in progress",
        )) assertShown(text)
    }

    @Test
    fun elementTextsWhenExpanded() {
        val events = operation(EventType.USER_SET_CODEC, EventType.CODEC_VERIFIED, config = sbc, actual = sbc, elements = listOf(
            EventType.ASSOCIATION_REQUESTED to { this },
            EventType.SET_CODEC_CONFIG to { copy(config = sbc, attempt = 1) },
            EventType.VALIDATE_CODEC_CONFIG to { copy(config = sbc, attempt = 1) },
            EventType.VALIDATE_CODEC_CONFIG to { copy(config = sbc, actual = ldac, attempt = 2) },
            EventType.VALIDATE_CODEC_CONFIG to { copy(config = sbc, actual = sbc, attempt = 3) },
        ))
        show(events)
        compose.onNodeWithTag("log_entry_${events.first().seq}").performClick()
        for (text in listOf(
            "Codec chosen: $sbcText",
            "Asked to link the device",
            "Setting codec, attempt 1: $sbcText",
            "Codec change incomplete, no codec reported",
            "Codec change incomplete, active: $ldacText",
            "Codec validated: $sbcText",
            "Codec applied: $sbcText",
        )) compose.onNodeWithText(text, useUnmergedTree = true).assertExists()
    }

    @Test
    fun durations() {
        show(listOf(
            operation(EventType.A2DP_CONNECTED, EventType.NOT_CONNECTED, resolveAfter = 99),
            operation(EventType.A2DP_CONNECTED, EventType.NO_PERMISSION, resolveAfter = 1_500),
            operation(EventType.A2DP_CONNECTED, EventType.TIMEOUT, resolveAfter = 125_000),
            operation(EventType.A2DP_CONNECTED, EventType.CANCELLED, resolveAfter = 5_400_000),
            operation(EventType.A2DP_CONNECTED, EventType.OPERATION_INTERRUPTED, resolveAfter = 7_200_000),
        ).flatten())
        for (text in listOf(
            "Audio connected → Audio not connected yet",
            "Audio connected → Nearby devices permission missing (1.5 s)",
            "Audio connected → Timed out (2 min 5 s)",
            "Audio connected → Cancelled (1 h 30 min)",
            // Resolved when the app started again, so the time says nothing
            "Audio connected → Interrupted, app was stopped",
        )) assertShown(text)
    }

    @Test
    fun aFailedOperationShowsItsMessage() {
        show(operation(EventType.A2DP_CONNECTED, EventType.ERROR, message = "java.lang.IllegalStateException: broken"))
        compose.onNodeWithText("java.lang.IllegalStateException: broken", useUnmergedTree = true).assertExists()
    }

    // V9

    private fun sampleLog(): List<LogEvent> {
        val stream = javaClass.classLoader!!.getResourceAsStream("sample_event_log.jsonl")
        return EventLog.parse(stream.bufferedReader().use { it.readLines() })
    }

    @Test
    fun sampleLogIsGroupedByDayNewestFirst() {
        val events = sampleLog()
        show(events)
        // The open operation at the end of the sample is the first entry
        compose.onNodeWithText("Today", useUnmergedTree = true).assertExists()
        compose.onAllNodes(hasText("Audio connected → in progress")).onFirst().assertExists()
        list.performScrollToKey("day 2026-10-26")
        compose.onNodeWithText("Yesterday", useUnmergedTree = true).assertExists()
        list.performScrollToKey("day 2026-10-25")
        compose.onNodeWithText("Sunday, October 25, 2026", useUnmergedTree = true).assertExists()
    }

    @Test
    fun interleavedOperationsAreSeparateEntries() {
        show(sampleLog())
        assertShown(hasText("Restoring preferred codec", substring = true) and hasText("Replaced by a newer request", substring = true))
        assertShown(hasText("Codec chosen: AAC", substring = true) and hasText("Codec applied: AAC", substring = true))
        assertShown("Test: reconnect_test start")
    }

    @Test
    fun emptyLog() {
        show(emptyList())
        compose.onNodeWithText("No events logged yet").assertExists()
        compose.onNodeWithTag("share_log").assertIsNotEnabled()
        compose.onNodeWithTag("log_menu").performClick()
        compose.onNodeWithTag("clear_log").assertIsNotEnabled()
    }

    @Test
    fun tappingAnOperationExpandsAndCollapsesIt() {
        val events = operation(EventType.A2DP_CONNECTED, EventType.CODEC_VERIFIED, actual = sbc,
            elements = listOf(EventType.SET_CODEC_CONFIG to { copy(config = sbc, attempt = 1) }))
        show(events)
        val entry = compose.onNodeWithTag("log_entry_${events.first().seq}")
        val row = "Setting codec, attempt 1: $sbcText"
        compose.onNodeWithText(row, useUnmergedTree = true).assertDoesNotExist()
        entry.performClick()
        compose.onNodeWithText(row, useUnmergedTree = true).assertExists()
        entry.performClick()
        compose.onNodeWithText(row, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Audio connected → Codec applied: $sbcText", useUnmergedTree = true).assertExists()
    }

    @Test
    fun longPressingAnEventShowsItsJson() {
        val label = event(EventType.TEST_LABEL, device = null, message = "step 1")
        show(listOf(label))
        val row = compose.onNodeWithText("Test: step 1")
        row.performTouchInput { longClick() }
        compose.onNodeWithText("\"type\": \"TEST_LABEL\"", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun expandedEntriesStayExpandedWhileScrolling() {
        val events = sampleLog()
        show(events)
        val newest = events.last { it.type.flow == OperationFlow.TRIGGER }
        compose.onNodeWithTag("log_entry_${newest.seq}").performClick()
        list.performScrollToKey("day 2026-10-25")
        list.performScrollToKey(newest.seq)
        compose.onNodeWithText("Setting codec, attempt 1", substring = true, useUnmergedTree = true).assertExists()
    }

    // V10

    @Test
    fun newEventsShowWhileTheScreenIsOpen() {
        var events by mutableStateOf(listOf(event(EventType.TEST_LABEL, device = null, message = "first")))
        compose.setContent { LogScreen(events, today, actions) }
        assertShown("Test: first")
        events = listOf(event(EventType.TEST_LABEL, device = null, message = "second")) + events
        assertShown("Test: second")
        assertShown("Test: first")
        // A cleared log shows the empty state
        events = emptyList()
        compose.onNodeWithText("No events logged yet").assertExists()
    }

    // V11

    @Test
    fun clearingAsksFirst() {
        show(listOf(event(EventType.TEST_LABEL, device = null, message = "step 1")))
        compose.onNodeWithTag("log_menu").performClick()
        compose.onNodeWithTag("clear_log").performClick()
        compose.onNodeWithText("Clear log?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear log?").assertDoesNotExist()
        assertEquals(emptyList<String>(), actions.calls)
        compose.onNodeWithTag("log_menu").performClick()
        compose.onNodeWithTag("clear_log").performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Clear log?").assertDoesNotExist()
        assertEquals(listOf("clear"), actions.calls)
    }

    @Test
    fun sharingExportsTheChosenMode() {
        show(listOf(event(EventType.TEST_LABEL, device = null, message = "step 1")))
        compose.onNodeWithTag("share_log").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithTag("share_log").performClick()
        compose.onNodeWithText("Full").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithTag("share_log").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("share ANONYMISED", "share FULL"), actions.calls)
    }

    @Test
    fun disablingLoggingAsksFirst() {
        show(listOf(event(EventType.TEST_LABEL, device = null, message = "step 1")))
        compose.onNodeWithTag("log_menu").performClick()
        compose.onNodeWithTag("disable_logging").performClick()
        compose.onNodeWithText("Disable logging?").assertExists()
        compose.onNodeWithText("${EventLog.MAX_EVENTS} events", substring = true).assertExists()
        compose.onNodeWithText("Keep enabled").performClick()
        compose.onNodeWithText("Disable logging?").assertDoesNotExist()
        assertEquals(emptyList<String>(), actions.calls)
        compose.onNodeWithTag("log_menu").performClick()
        compose.onNodeWithTag("disable_logging").performClick()
        compose.onNodeWithText("Delete & Disable").performClick()
        compose.onNodeWithText("Disable logging?").assertDoesNotExist()
        assertEquals(listOf("setLoggingEnabled false"), actions.calls)
    }

    @Test
    fun disabledLoggingCanBeEnabled() {
        compose.setContent { LogScreen(emptyList(), today, actions, loggingEnabled = false) }
        compose.onNodeWithText("Logging is disabled").assertExists()
        compose.onNodeWithTag("log_menu").performClick()
        compose.onNodeWithTag("disable_logging").assertDoesNotExist()
        compose.onNodeWithTag("enable_logging").performClick()
        assertEquals(listOf("setLoggingEnabled true"), actions.calls)
    }

    @Test
    fun onlyDebugBuildsOpenLogFiles() {
        show(emptyList())
        compose.onNodeWithTag("log_menu").performClick()
        compose.onNodeWithTag("open_log_file").assertDoesNotExist()
    }

    @Test
    fun debugBuildsOpenLogFiles() {
        compose.setContent { LogScreen(emptyList(), today, actions, showDevelopmentOptions = true) }
        compose.onNodeWithTag("log_menu").performClick()
        compose.onNodeWithTag("open_log_file").performClick()
        assertEquals(listOf("openLogFile"), actions.calls)
    }

    @Test
    fun anOpenedFileIsReadOnly() {
        compose.setContent {
            LogScreen(listOf(event(EventType.TEST_LABEL, device = null, message = "step 1")), today, actions,
                showDevelopmentOptions = true, fileName = "export.jsonl")
        }
        compose.onNodeWithText("export.jsonl").assertExists()
        assertShown("Test: step 1")
        compose.onNodeWithTag("share_log").assertDoesNotExist()
        compose.onNodeWithTag("log_menu").assertDoesNotExist()
    }

    @Test
    fun aLoadingFileShowsNothingYet() {
        compose.setContent { LogScreen(null, today, actions, fileName = "export.jsonl") }
        compose.onNodeWithText("export.jsonl").assertExists()
        compose.onNodeWithText("No events in this file").assertDoesNotExist()
        list.assertDoesNotExist()
    }

    @Test
    fun anEmptyFile() {
        compose.setContent { LogScreen(emptyList(), today, actions, fileName = "export.jsonl") }
        compose.onNodeWithText("No events in this file").assertExists()
    }
}
