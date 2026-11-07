package com.tt.autobluetoothcodec

import android.content.res.Configuration
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.tt.autobluetoothcodec.SampleData.ADDRESS
import com.tt.autobluetoothcodec.SampleData.CODEC_TYPE_LC3
import com.tt.autobluetoothcodec.SampleData.LONG_NAME
import com.tt.autobluetoothcodec.SampleData.aac
import com.tt.autobluetoothcodec.SampleData.codec
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.ldac
import com.tt.autobluetoothcodec.SampleData.sbc
import com.tt.autobluetoothcodec.SampleData.sony
import com.tt.autobluetoothcodec.SampleData.state

private const val PHONE = "spec:width=411dp,height=891dp"
private const val SMALL_PHONE = "spec:width=360dp,height=640dp"

/**
 * A screen on a phone, in dark mode, with large fonts, in landscape, on a small phone, a foldable and a tablet.
 * PreviewScreenshots renders the same configurations on the JVM.
 */
@Preview(name = "Phone", device = PHONE, showSystemUi = true)
@Preview(name = "Phone, dark", device = PHONE, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Phone, font 200%", device = PHONE, showSystemUi = true, fontScale = 2f)
@Preview(name = "Phone, landscape", device = "$PHONE,orientation=landscape", showSystemUi = true)
@Preview(name = "Small phone", device = SMALL_PHONE, showSystemUi = true)
@Preview(name = "Foldable", device = Devices.FOLDABLE, showSystemUi = true)
@Preview(name = "Tablet", device = Devices.TABLET, showSystemUi = true)
internal annotation class ScreenPreviews

/** A screen in light and dark mode, for the states that don't need every configuration. */
@Preview(name = "Light", device = PHONE, showSystemUi = true)
@Preview(name = "Dark", device = PHONE, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
internal annotation class StatePreviews

/** A named state of a screen, for the previews and PreviewScreenshots. */
internal class Scene(val name: String, val content: @Composable () -> Unit) {
    override fun toString() = name
}

internal object NoMainActions : MainScreenActions {
    override fun requestPermission() {}
    override fun openAppSettings() {}
    override fun enableBluetooth() {}
    override fun openBluetoothSettings() {}
    override fun openLog() {}
    override fun openAbout() {}
    override fun setCodec(address: String, config: CodecConfig) {}
    override fun savePreferred(address: String, config: CodecConfig) {}
    override fun restorePreferred(address: String, config: CodecConfig) {}
    override fun deletePreferred(address: String) {}
    override fun link(address: String) {}
    override fun unlink(address: String) {}
}

internal object NoLogActions : LogScreenActions {
    override fun back() {}
    override fun share(mode: LogExport.Mode) {}
    override fun clear() {}
    override fun setLoggingEnabled(enabled: Boolean) {}
    override fun openLogFile() {}
}

internal object NoAboutActions : AboutScreenActions {
    override fun back() {}
    override fun openUrl(url: String) {}
    override fun openLicenses() {}
}

private fun mainScene(name: String, state: MainScreenState, expanded: Set<String> = emptySet()) =
    Scene(name) { AppTheme { MainScreen(state, NoMainActions, expanded) } }

private fun logScene(
    name: String,
    events: List<LogEvent>?,
    loggingEnabled: Boolean = true,
    showDevelopmentOptions: Boolean = false,
    fileName: String? = null,
) = Scene(name) {
    AppTheme { LogScreen(events, SampleData.TODAY, NoLogActions, loggingEnabled, showDevelopmentOptions, fileName) }
}

/** The screens as they usually look, shown in every configuration of [ScreenPreviews]. */
internal object Screens {
    /** The Qudelix with its preferred codec and the Sony. */
    val main = mainScene("Main", state(device(preferred = ldac()), sony()))
    val log = logScene("Log", SampleData.log())
    val about = Scene("About") { AppTheme { AboutScreen("1.0-9057ca4", NoAboutActions) } }
    val licenses = Scene("Licenses") { AppTheme { LicensesScreen(onBack = {}) } }

    val all = listOf(main, log, about, licenses)
}

/** The main screen with nothing to show. The Saved list's empty state needs a tap, see PreviewScreenshots. */
internal val emptyStates = listOf(
    mainScene("Bluetooth off", state(bluetoothEnabled = false)),
    mainScene("No devices", state()),
    mainScene("Permission denied", state(permissionState = PermissionState.DENIED)),
    mainScene("Permission permanently denied", state(permissionState = PermissionState.PERMANENTLY_DENIED)),
)

/**
 * The sniffing dog walking left, sniffing with all puffs showing, pausing, mid-turn and walking right. The states
 * above only show its start.
 */
internal val sniffingFrames = listOf(0.88f, 3.15f, 5.1f, 5.325f, 6.53f).map { seconds ->
    Scene("Sniffing at $seconds s") {
        AppTheme {
            Surface(Modifier.fillMaxSize()) {
                DogArt(DogScene.SNIFFING, Modifier.fillMaxWidth().aspectRatio(DOG_ART_RATIO), frozenAt = seconds)
            }
        }
    }
}

/** The main screen's other states, edge cases included. */
internal val mainScreenStates = emptyStates + listOf(
    // For a moment after Bluetooth turned on
    mainScene("Bluetooth profile connecting", state(devicesKnown = false)),
    // Since API 36 the codec status can only be read once the device is linked
    mainScene("Codec unreadable until linked",
        state(device(statusReadable = false, associationRequired = true, associated = false))),
    mainScene("Linking in progress",
        state(device(statusReadable = false, associationRequired = true, associated = false), linkingAddress = ADDRESS)),
    // Before Android 16 the codec can be read without a link, choosing one asks to link
    mainScene("Codec readable, not linked",
        state(device(associationRequired = true, associated = false))),
    // Only if the link was removed after saving, saving asks to link
    mainScene("Preferred codec, not linked",
        state(device(preferred = ldac(), associationRequired = true, associated = false))),
    mainScene("Phone can't link",
        state(device(statusReadable = false, associationRequired = true, associated = false), canAssociate = false)),
    mainScene("No codec info, long name, debug options", state(
        device(codec = null, android = null),
        device(name = LONG_NAME, address = "00:1A:7D:DA:71:13"),
        showDevelopmentOptions = true,
    )),
    mainScene("Unsupported codec (LC3)", state(
        device(codec = null, android = AndroidCodecConfig(CODEC_TYPE_LC3, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16)),
    )),
    mainScene("LDAC bitrates and other codecs", state(
        device(ldac(bitrate = LDACCodecConfig.BITRATE_ADAPTIVE), name = "LDAC adaptive", address = "00:00:00:00:00:01"),
        // Four chips and the preferred one
        ldac(CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_24, LDACCodecConfig.BITRATE_HIGH).let {
            device(it, preferred = it, name = "LDAC 909 kbps", address = "00:00:00:00:00:02")
        },
        device(codec(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24),
            name = "aptX HD", address = "00:00:00:00:00:03"),
        device(sbc(), name = "SBC", address = "00:00:00:00:00:04"),
    )),
    mainScene("Expanded, nothing saved", state(device(), sony()), setOf(ADDRESS)),
    mainScene("Expanded, preferred codec active", state(device(preferred = ldac()), sony()), setOf(ADDRESS)),
    mainScene("Expanded, preferred codec differs",
        state(device(ldac(CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24), preferred = ldac()), sony(preferred = aac())),
        setOf(ADDRESS)),
    mainScene("Expanded, codec readable, not linked",
        state(device(associationRequired = true, associated = false)), setOf(ADDRESS)),
    // Only restoring, a codec this app doesn't know can't be saved
    mainScene("Expanded, unsupported codec, preferred saved", state(
        device(codec = null, android = AndroidCodecConfig(CODEC_TYPE_LC3, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16),
            preferred = ldac()),
    ), setOf(ADDRESS)),
    mainScene("Expanded, long name", state(
        device(name = LONG_NAME, preferred = ldac(CodecConfig.SAMPLE_RATE_48000)),
    ), setOf(ADDRESS)),
    // The chips show the codec being applied, the rows the device doesn't report yet in the secondary color
    mainScene("Codec change pending", state(
        device(target = aac(), preferred = aac()),
        sony(target = ldac(CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_16)),
    )),
    mainScene("Expanded, codec change pending", state(device(target = aac())), setOf(ADDRESS)),
    mainScene("Expanded, sample rate change pending",
        state(device(target = ldac(CodecConfig.SAMPLE_RATE_48000))), setOf(ADDRESS)),
    mainScene("Many devices", state(
        device(preferred = ldac()),
        sony(preferred = aac()),
        device(aac(), masks = SampleData.SONY_MASKS, name = "Pixel Buds Pro 2", address = "00:00:00:00:00:05"),
        device(sbc(), masks = SampleData.SONY_MASKS, name = "Car", address = "00:00:00:00:00:06"),
        device(statusReadable = false, associationRequired = true, associated = false,
            name = "Kitchen speaker", address = "00:00:00:00:00:07"),
    )),
)

/** The log screen's other states, edge cases included. */
internal val logScreenStates = listOf(
    logScene("Loading", null),
    logScene("No events", emptyList()),
    logScene("Logging disabled", emptyList(), loggingEnabled = false),
    logScene("Edge cases", SampleData.edgeCaseLog()),
    logScene("Opened file", SampleData.log(), fileName = "event_log_2026-10-27.jsonl"),
    logScene("Opened file, long name, empty", emptyList(),
        fileName = "auto_bluetooth_codec_event_log_exported_from_pixel_9_pro_2026-10-27T18-02-11.jsonl"),
)

private class MainScreenStates : PreviewParameterProvider<Scene> {
    override val values = mainScreenStates.asSequence()
}

private class LogScreenStates : PreviewParameterProvider<Scene> {
    override val values = logScreenStates.asSequence()
}

@ScreenPreviews
@Composable
private fun MainScreenPreview() = Screens.main.content()

@StatePreviews
@Composable
private fun MainScreenStatesPreview(@PreviewParameter(MainScreenStates::class) scene: Scene) = scene.content()

@ScreenPreviews
@Composable
private fun LogScreenPreview() = Screens.log.content()

@StatePreviews
@Composable
private fun LogScreenStatesPreview(@PreviewParameter(LogScreenStates::class) scene: Scene) = scene.content()

@ScreenPreviews
@Composable
private fun AboutScreenPreview() = Screens.about.content()

@ScreenPreviews
@Composable
private fun LicensesScreenPreview() = Screens.licenses.content()
