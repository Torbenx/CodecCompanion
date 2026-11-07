package com.tt.autobluetoothcodec

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.SampleData.ADDRESS
import com.tt.autobluetoothcodec.SampleData.NAME
import com.tt.autobluetoothcodec.SampleData.codec
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.ldac
import com.tt.autobluetoothcodec.SampleData.state
import com.tt.autobluetoothcodec.UiTestData.RecordingActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V1 to V6 and V14 of the test concept: the permission screens, what the main screen shows for a device, what it asks
 * for, and its empty states.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = RecordingActions()

    private fun show(state: MainScreenState) = compose.setContent { MainScreen(state, actions) }

    private fun show(device: DeviceData, showDevelopmentOptions: Boolean = false, canAssociate: Boolean = true,
                     linkingAddress: String? = null) =
        show(state(device, showDevelopmentOptions = showDevelopmentOptions, canAssociate = canAssociate,
            linkingAddress = linkingAddress))

    private fun text(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    /** Taps the device's name, the card's center may be covered by its status chip. */
    private fun expand() {
        text(NAME).performClick()
        compose.waitForIdle()
    }

    /** A chip of the expanded card, scrolled into view since the card may not fit on the screen. */
    private fun chip(text: String) = compose.onNodeWithText(text).performScrollTo()

    // V1

    @Test
    fun withoutThePermissionItIsRequested() {
        show(state(device(), permissionState = PermissionState.DENIED))
        text(NAME).assertDoesNotExist()
        text("Allow it in the app settings under Permissions.").assertDoesNotExist()
        text("Grant permission").performClick()
        assertEquals(listOf("requestPermission"), actions.calls)
    }

    @Test
    fun afterAPermanentDenialTheSettingsAreOffered() {
        show(state(device(), permissionState = PermissionState.PERMANENTLY_DENIED))
        text(NAME).assertDoesNotExist()
        text("Grant permission").assertDoesNotExist()
        text("Allow it in the app settings under Permissions.").assertExists()
        text("Open settings").performClick()
        assertEquals(listOf("openAppSettings"), actions.calls)
    }

    // V14

    @Test
    fun withBluetoothOffItCanBeTurnedOn() {
        show(state(bluetoothEnabled = false))
        text("Codec is asleep").assertExists()
        text("Bluetooth is off").assertExists()
        text("Turn on Bluetooth").performClick()
        assertEquals(listOf("enableBluetooth"), actions.calls)
    }

    @Test
    fun theMissingPermissionComesBeforeBluetoothBeingOff() {
        show(state(permissionState = PermissionState.DENIED, bluetoothEnabled = false))
        text("Codec needs your permission").assertExists()
        text("Codec is asleep").assertDoesNotExist()
        text("Bluetooth is off").assertDoesNotExist()
    }

    @Test
    fun withoutDevicesTheBluetoothSettingsAreOffered() {
        show(state())
        text("Codec hasn't found any audio devices").assertExists()
        text("Open Bluetooth settings").performClick()
        assertEquals(listOf("openBluetoothSettings"), actions.calls)
    }

    @Test
    fun whileTheProfileConnectsNoEmptyStateShows() {
        show(state(devicesKnown = false))
        text("Codec hasn't found any audio devices").assertDoesNotExist()
        text("Bluetooth is off").assertDoesNotExist()
    }

    @Test
    fun devicesShowInsteadOfAnEmptyState() {
        show(device())
        text(NAME).assertExists()
        text("Codec hasn't found any audio devices").assertDoesNotExist()
    }

    @Test
    fun withNothingSavedTheSavedListLeadsBackToTheConnectedDevices() {
        show(device())
        compose.onNodeWithTag("list_toggle").performClick()
        compose.onNodeWithTag("list_saved").performClick()
        text("Codec has no saved devices yet").assertExists()
        text("Show connected devices").performClick()
        text("Connected devices").assertExists()
        text(NAME).assertExists()
    }

    @Test
    fun withSavedDevicesTheSavedListHasNoEmptyState() {
        show(state(device(preferred = ldac())))
        compose.onNodeWithTag("list_toggle").performClick()
        compose.onNodeWithTag("list_saved").performClick()
        text("Saved devices").assertExists()
        text("Codec has no saved devices yet").assertDoesNotExist()
    }

    // The top bar

    @Test
    fun theTitleNamesTheListAndOpensTheSwitch() {
        show(device())
        text("Connected devices").assertExists()
        compose.onNodeWithTag("list_saved").assertDoesNotExist()
        compose.onNodeWithTag("list_toggle").performClick()
        compose.onNodeWithTag("list_connected").assertExists()
        compose.onNodeWithTag("list_saved").assertExists()
        compose.onNodeWithTag("list_toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("list_saved").assertDoesNotExist()
    }

    @Test
    fun theSavedListIsEmptyForNow() {
        show(device())
        compose.onNodeWithTag("list_toggle").performClick()
        compose.onNodeWithTag("list_saved").performClick()
        text("Saved devices").assertExists()
        text(NAME).assertDoesNotExist()
        compose.onNodeWithTag("list_connected").performClick()
        text(NAME).assertExists()
    }

    @Test
    fun aLoneDeviceStartsCollapsed() {
        show(device())
        text("LDAC").assertExists()
        text("Codec").assertDoesNotExist()
    }

    @Test
    fun anExpandedCardStaysExpandedWhileTheOtherListShows() {
        show(device())
        expand()
        text("Codec").assertExists()
        compose.onNodeWithTag("list_toggle").performClick()
        compose.onNodeWithTag("list_saved").performClick()
        text("Codec").assertDoesNotExist()
        compose.onNodeWithTag("list_connected").performClick()
        compose.waitForIdle()
        text("Codec").assertExists()
    }

    // V2

    @Test
    fun collapsedCardShowsTheCodec() {
        show(device())
        text(NAME).assertExists()
        text("LDAC").assertExists()
        text("96000 Hz").assertExists()
        text("32-bit").assertExists()
        // The system default bitrate has no chip
        text("Adaptive").assertDoesNotExist()
    }

    @Test
    fun collapsedCardShowsTheLdacBitrate() {
        show(device(ldac(bitrate = LDACCodecConfig.BITRATE_ADAPTIVE)))
        text("Adaptive").assertExists()
    }

    @Test
    fun collapsedCardShowsAFixedLdacBitrateForTheSampleRate() {
        show(device(ldac(sampleRate = CodecConfig.SAMPLE_RATE_44100, bitrate = LDACCodecConfig.BITRATE_HIGH)))
        text("909 kbps").assertExists()
    }

    @Test
    fun unsupportedCodec() {
        show(device(codec = null, android = AndroidCodecConfig(7, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16)))
        text("Unsupported codec").assertExists()
    }

    @Test
    fun noCodecInformation() {
        show(device(codec = null, android = null))
        text("No codec information").assertExists()
    }

    // V3

    @Test
    fun theActivePreferredCodecShowsAChipAfterTheCodec() {
        show(device(preferred = ldac()))
        compose.onNodeWithTag("preferred_chip", useUnmergedTree = true).assertExists()
        text("Preferred Codec").assertExists()
    }

    @Test
    fun noChipWithoutTheActivePreferredCodec() {
        show(state(
            device(address = ADDRESS),
            device(preferred = codec(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16),
                name = "Other", address = "AA:BB:CC:DD:EE:01"),
        ))
        compose.onNodeWithTag("preferred_chip", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun noChipForAnUnreadableOrUnknownCodec() {
        show(state(
            device(statusReadable = false, codec = null, android = null, preferred = ldac()),
            device(codec = null, android = AndroidCodecConfig(7), preferred = ldac(), name = "Other", address = "AA:BB:CC:DD:EE:01"),
        ))
        compose.onNodeWithTag("preferred_chip", useUnmergedTree = true).assertDoesNotExist()
    }

    // V4

    /** One of the expanded card's preferred codec actions, scrolled into view. */
    private fun action(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun withNothingSavedTheBannerPointsToSaveBelowTheCodec() {
        show(device())
        expand()
        text("No preferred codec").assertExists()
        compose.onNodeWithTag("restore_preferred").assertDoesNotExist()
        compose.onNodeWithTag("clear_preferred").assertDoesNotExist()
        action("save_preferred").performClick()
        assertEquals(listOf("savePreferred $ADDRESS ${ldac().describe()}"), actions.calls)
    }

    @Test
    fun whileActiveThePreferredCodecCanOnlyBeDeleted() {
        show(device(preferred = ldac()))
        expand()
        text("Preferred codec is active").assertExists()
        text(ldac().describe()).assertExists()
        compose.onNodeWithTag("save_preferred").assertDoesNotExist()
        compose.onNodeWithTag("restore_preferred").assertDoesNotExist()
        action("clear_preferred").performClick()
        assertEquals(listOf("deletePreferred $ADDRESS"), actions.calls)
    }

    @Test
    fun aDifferentPreferredCodecCanBeRestoredOrReplacedBySaving() {
        val preferred = codec(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16)
        show(device(preferred = preferred))
        expand()
        text("Preferred codec").assertExists()
        text(preferred.describe()).assertExists()
        compose.onNodeWithTag("clear_preferred").assertDoesNotExist()
        action("restore_preferred").performClick()
        action("save_preferred").performClick()
        assertEquals(listOf(
            "restorePreferred $ADDRESS ${preferred.describe()}",
            "savePreferred $ADDRESS ${ldac().describe()}",
        ), actions.calls)
    }

    @Test
    fun anUnknownCodecCantBeSavedButThePreferredOneRestored() {
        show(device(codec = null, android = AndroidCodecConfig(7), preferred = ldac()))
        expand()
        compose.onNodeWithTag("save_preferred").assertDoesNotExist()
        compose.onNodeWithTag("clear_preferred").assertDoesNotExist()
        action("restore_preferred").performClick()
        assertEquals(listOf("restorePreferred $ADDRESS ${ldac().describe()}"), actions.calls)
    }

    @Test
    fun noBannerOrSaveForAnUnknownCodecWithoutAPreferredOne() {
        show(device(codec = null, android = AndroidCodecConfig(7)))
        expand()
        compose.onNodeWithTag("preferred_banner").assertDoesNotExist()
        compose.onNodeWithTag("save_preferred").assertDoesNotExist()
    }

    @Test
    fun onlyDebugBuildsHaveTheDeviceMenu() {
        show(device(preferred = ldac()))
        compose.onNodeWithTag("device_menu").assertDoesNotExist()
    }

    @Test
    fun debugBuildsCanUnlink() {
        show(device(), showDevelopmentOptions = true)
        compose.onNodeWithTag("device_menu").performClick()
        compose.onNodeWithTag("unlink_device").performClick()
        assertEquals(listOf("unlink $ADDRESS"), actions.calls)
    }

    @Test
    fun debugBuildsOnlyUnlinkLinkedDevices() {
        show(device(associated = false), showDevelopmentOptions = true)
        compose.onNodeWithTag("device_menu").assertDoesNotExist()
    }

    // V5

    private val hidden = device(statusReadable = false, codec = null, android = null, associated = false,
        associationRequired = true)

    @Test
    fun anUnreadableCodecAsksForALink() {
        show(hidden)
        text("Link this device to see and change its codec.").assertExists()
        compose.onNodeWithTag("link_device").assertIsEnabled().performClick()
        assertEquals(listOf("link $ADDRESS"), actions.calls)
        text("LDAC").assertDoesNotExist()
    }

    @Test
    fun anUnreadableCodecsCardDoesntExpand() {
        show(hidden)
        compose.onNodeWithTag("device_$ADDRESS").assertHasNoClickAction()
        text("Codec").assertDoesNotExist()
    }

    @Test
    fun aReadableCodecWithoutLinkShowsTheChipAndExpandedTheBanner() {
        // The API 34 and 35 state: readable, choosing a codec or saving it asks to link
        show(device(associated = false, associationRequired = true))
        text("LDAC").assertExists()
        text("Not linked").assertExists()
        compose.onNodeWithTag("link_device").assertDoesNotExist()
        compose.onNodeWithTag("link_banner").assertDoesNotExist()
        expand()
        text("Changing the codec or saving it as preferred asks to link this device first.").assertExists()
        compose.onNodeWithTag("link_device").assertDoesNotExist()
    }

    @Test
    fun aPreferredCodecWithoutLinkSaysItIsntApplied() {
        // Only if the link was removed after saving
        show(device(associated = false, associationRequired = true, preferred = ldac()))
        compose.onNodeWithTag("preferred_chip", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("link_chip", useUnmergedTree = true).assertExists()
        expand()
        text("The preferred codec is only applied when this device connects once it's linked. " +
            "Changing or restoring the codec asks to link it.").assertExists()
    }

    @Test
    fun aLinkedDeviceHasNoLinkChip() {
        show(device())
        compose.onNodeWithTag("link_chip", useUnmergedTree = true).assertDoesNotExist()
        expand()
        compose.onNodeWithTag("link_banner").assertDoesNotExist()
    }

    @Test
    fun phonesWithoutTheCompanionFeatureCantLink() {
        show(hidden, canAssociate = false)
        text("This phone can't link devices, so the codec can't be read or changed.").assertExists()
        compose.onNodeWithTag("link_device").assertDoesNotExist()
    }

    @Test
    fun phonesWithoutTheCompanionFeatureSayItForAReadableCodec() {
        show(device(associated = false, associationRequired = true), canAssociate = false)
        text("Can't link").assertExists()
        expand()
        text("This phone can't link devices, so the codec can't be changed.").assertExists()
    }

    @Test
    fun theLinkButtonSpinsWhileLinking() {
        show(hidden, linkingAddress = ADDRESS)
        compose.onNodeWithTag("link_device").assertIsNotEnabled()
        text("Linking…").assertExists()
    }

    @Test
    fun theLinkButtonWaitsWhileAnotherDeviceLinks() {
        show(hidden, linkingAddress = "00:11:22:33:44:55")
        compose.onNodeWithTag("link_device").assertIsNotEnabled()
        text("Link Device").assertExists()
    }

    @Test
    fun theLinkChipSaysLinkingWhileLinking() {
        show(device(associated = false, associationRequired = true), linkingAddress = ADDRESS)
        text("Linking…").assertExists()
    }

    // V6

    @Test
    fun expandedCardEnablesTheOfferedOptions() {
        show(device())
        expand()
        text("Codec").assertExists()
        chip("LDAC").assertIsEnabled()
        chip("aptX HD").assertIsEnabled()
        chip("Opus").assertIsNotEnabled()
        chip("44100 Hz").assertIsEnabled()
        chip("16-bit").assertIsEnabled()
        // Collapsed chips are gone, and the bitrate row shows the system default as adaptive
        text("96000 Hz").assertExists()
        text("Bitrate").assertExists()
    }

    @Test
    fun choosingACodecKeepsTheRateAndSizeIfSupported() {
        // LDAC also supports 48 kHz and 24-bit, so they stay
        show(device(codec(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24)))
        expand()
        chip("LDAC").performClick()
        assertEquals(listOf("setCodec $ADDRESS LDAC, 48 kHz, 24-bit"), actions.calls)
    }

    @Test
    fun choosingACodecTakesTheFirstSupportedRateAndSize() {
        show(device())
        expand()
        chip("aptX HD").performClick()
        chip("SBC").performClick()
        assertEquals(listOf(
            "setCodec $ADDRESS aptX HD, 44.1 kHz, 24-bit",
            "setCodec $ADDRESS SBC, 44.1 kHz, 16-bit",
        ), actions.calls)
    }

    @Test
    fun choosingACodecPrefersTheCapabilityThatSupportsBoth() {
        val masks = listOf(
            A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_LDAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_32),
            A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_16),
            A2dpProfileUtil.ConfigMask(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_96000, CodecConfig.BITS_PER_SAMPLE_32),
        )
        show(device(masks = masks))
        expand()
        chip("AAC").performClick()
        assertEquals(listOf("setCodec $ADDRESS AAC, 96 kHz, 32-bit"), actions.calls)
    }

    @Test
    fun choosingTheCurrentCodecDoesNothing() {
        show(device())
        expand()
        chip("LDAC").performClick()
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun choosingARateSizeOrBitrateChangesOnlyThat() {
        show(device())
        expand()
        chip("48000 Hz").performClick()
        chip("24-bit").performClick()
        chip("990 kbps").performClick()
        assertEquals(listOf(
            "setCodec $ADDRESS LDAC, 48 kHz, 32-bit",
            "setCodec $ADDRESS LDAC, 96 kHz, 24-bit",
            "setCodec $ADDRESS LDAC, 96 kHz, 32-bit, 990 kbps",
        ), actions.calls)
    }

    // The codec being applied

    private fun SemanticsNodeInteraction.assertState(state: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))

    @Test
    fun whileACodecChangeIsPendingTheCollapsedChipsShowItsTarget() {
        show(device(target = SampleData.aac()))
        compose.onNodeWithText("AAC").assertState("Pending")
        compose.onNodeWithText("44100 Hz").assertState("Pending")
        compose.onNodeWithText("16-bit").assertState("Pending")
        text("LDAC").assertDoesNotExist()
    }

    @Test
    fun onlyTheRowsTheDeviceDoesntReportYetArePending() {
        show(device(target = ldac(CodecConfig.SAMPLE_RATE_48000)))
        expand()
        chip("LDAC").assertState("Selected")
        chip("48000 Hz").assertState("Pending")
        chip("32-bit").assertState("Selected")
        chip("96000 Hz").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun theBitrateLeftToTheSystemShowsTheReportedOne() {
        show(device(ldac(bitrate = LDACCodecConfig.BITRATE_HIGH), target = ldac()))
        expand()
        chip("990 kbps").assertState("Selected")
    }

    @Test
    fun choicesBuildOnThePendingCodec() {
        show(device(target = codec(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24)))
        expand()
        // Already the target
        chip("aptX HD").performClick()
        chip("44100 Hz").performClick()
        // Back to the reported codec, with the target's rate and size
        chip("LDAC").performClick()
        assertEquals(listOf(
            "setCodec $ADDRESS " +
                codec(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_24).describe(),
            "setCodec $ADDRESS " + ldac(CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24).describe(),
        ), actions.calls)
    }

    @Test
    fun aPendingPreferredCodecCountsAsActive() {
        show(device(target = SampleData.aac(), preferred = SampleData.aac()))
        expand()
        // The pending codec is the preferred one, so there's nothing to save
        text("Preferred codec is active").assertExists()
        compose.onNodeWithTag("save_preferred").assertDoesNotExist()
    }

    @Test
    fun savingWhileACodecChangeIsPendingOffersItsTarget() {
        show(device(target = SampleData.aac()))
        expand()
        action("save_preferred").performClick()
        assertEquals(listOf("savePreferred $ADDRESS ${SampleData.aac().describe()}"), actions.calls)
    }

    @Test
    fun ratesAndSizesTheCodecDoesntOfferAreDisabled() {
        show(device(codec(CodecConfig.CODEC_TYPE_APTX_HD, CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24)))
        expand()
        chip("44100 Hz").assertIsEnabled()
        chip("88200 Hz").assertIsNotEnabled()
        chip("16-bit").assertIsNotEnabled()
        chip("32-bit").assertIsNotEnabled()
        text("Bitrate").assertDoesNotExist()
    }
}
