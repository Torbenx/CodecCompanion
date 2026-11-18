package com.tt.codeccompanion

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.codeccompanion.SampleData.ADDRESS
import com.tt.codeccompanion.SampleData.codec
import com.tt.codeccompanion.SampleData.device
import com.tt.codeccompanion.SampleData.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the preferred codec banner's button goes: beside the title and the codec on a phone, below the codec on a small
 * phone with the largest font, where the title would otherwise wrap mid-word. And that the button doesn't push the text
 * away from the banner's edges or the title from the codec. Only on the JVM, a test can't change the font size of a
 * phone. It needs the native graphics to measure the labels like a phone does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreferredBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferred = codec(CodecConfig.CODEC_TYPE_AAC, CodecConfig.SAMPLE_RATE_44100, CodecConfig.BITS_PER_SAMPLE_16)

    /** The longest codec text there is. */
    private val longPreferred = AACCodecConfig().also {
        it.sampleRate = CodecConfig.SAMPLE_RATE_44100
        it.bitsPerSample = CodecConfig.BITS_PER_SAMPLE_16
        it.variableBitrate = AACCodecConfig.VARIABLE_BITRATE_VERY_HIGH
    }

    private fun showExpanded(preferred: CodecConfig? = this.preferred) {
        compose.setContent {
            AppTheme { MainScreen(state(device(preferred = preferred)), UiTestData.RecordingActions(), setOf(ADDRESS)) }
        }
    }

    private fun restore() = compose.onNodeWithTag("restore_preferred").getUnclippedBoundsInRoot()

    private fun restoreTop() = restore().top

    private fun textBounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun codecTextBounds() = textBounds(preferred.describe())

    /** The banner, in the unmerged tree since it's part of the clickable card. */
    private fun banner() = compose.onNodeWithTag("preferred_banner", useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertClose(message: String, expected: Dp, actual: Dp) =
        assertEquals(message, expected.value, actual.value, 1f)

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun onAPhoneRestoreIsCenteredBesideTheTitleAndTheCodec() {
        showExpanded()
        val text = (textBounds("Preferred codec").top + codecTextBounds().bottom) / 2
        assertClose("The centers of Restore and the text", text, (restore().top + restore().bottom) / 2)
    }

    @Test
    @Config(qualifiers = "w462dp-h1001dp-port", fontScale = 0.8f)
    fun withASmallFontRestoreDoesntPushTheTextFromTheEdgesOrTheTitleFromTheCodec() {
        showExpanded()
        val title = textBounds("Preferred codec")
        val codec = codecTextBounds()
        assertClose("Margins above and below the text", title.top - banner().top, banner().bottom - codec.bottom)
        assertClose("Title to codec", 2.dp, codec.top - title.bottom)
    }

    @Test
    @Config(qualifiers = "w462dp-h1001dp-port", fontScale = 0.8f)
    fun withoutAButtonNoRoomIsLeftForOne() {
        showExpanded(preferred = null)
        val title = textBounds("No preferred codec")
        val explanation = textBounds("Save the codec below to switch to it whenever this device connects.")
        assertClose("Above the title", 12.dp, title.top - banner().top)
        assertClose("Title to explanation", 2.dp, explanation.top - title.bottom)
        assertClose("Below the explanation", 12.dp, banner().bottom - explanation.bottom)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun theTextLinesUpWithTheLinkBannerAbove() {
        compose.setContent {
            AppTheme {
                MainScreen(state(device(associationRequired = true, associated = false)), UiTestData.RecordingActions(),
                    setOf(ADDRESS))
            }
        }
        assertClose("The start of both banners' text",
            textBounds("Changing the codec or saving it as preferred asks to link this device first.").left,
            textBounds("No preferred codec").left)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port")
    fun aLongCodecWrapsBesideRestore() {
        showExpanded(longPreferred)
        val codec = textBounds(longPreferred.describe())
        assertTrue("The codec wraps", codec.bottom - codec.top >= 40.dp)
        assertTrue("Restore stays beside it", restoreTop() < codec.bottom)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port", fontScale = 2f)
    fun withTheLargestFontOnASmallPhoneRestoreIsBelowTheCodec() {
        showExpanded()
        compose.onNodeWithText("Preferred codec", useUnmergedTree = true).assertExists()
        assertTrue(restoreTop() >= codecTextBounds().bottom)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port", fontScale = 2f)
    fun withTheLargestFontOnASmallPhoneSaveKeepsItsLabel() {
        showExpanded()
        compose.onNodeWithText("Save as preferred", useUnmergedTree = true).assertExists()
    }
}
