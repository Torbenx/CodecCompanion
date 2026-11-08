package com.tt.autobluetoothcodec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.saved
import com.tt.autobluetoothcodec.SampleData.state
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The title gets shorter, then only the arrow is left, when the bar is too narrow for the list's name. The bar is
 * tinted while the list in view is scrolled.
 */
@RunWith(AndroidJUnit4::class)
// Measures text like a phone, Robolectric's default graphics make it a few pixels wide
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainTopBarTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show() = compose.setContent { AppTheme { MainScreen(state(device()), NoMainActions) } }

    private fun title(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun aPhoneShowsTheListsName() {
        show()
        title("Connected devices").assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port", fontScale = 2f)
    fun theLargestFontOnASmallPhoneShowsTheShortName() {
        show()
        title("Connected devices").assertDoesNotExist()
        title("Connected").assertExists()
    }

    @Test
    @Config(qualifiers = "w240dp-h411dp-port", fontScale = 2f)
    fun aNarrowSplitScreenAtTheLargestFontOnlyShowsTheArrow() {
        show()
        title("Connected devices").assertDoesNotExist()
        title("Connected").assertDoesNotExist()
        // Still named for screen readers
        compose.onNodeWithTag("list_toggle").assertContentDescriptionEquals("Connected devices")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun theBarIsTintedOnlyWhileTheListInViewIsScrolled() {
        // Expanded, so the connected devices don't fit
        val devices = (0 until 6).map { device(name = "Device $it", address = "00:00:00:00:00:0$it") }
        lateinit var scheme: ColorScheme
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            AppTheme {
                scheme = MaterialTheme.colorScheme
                MainScreen(state(*devices.toTypedArray(), savedDevices = listOf(saved())), NoMainActions,
                    initiallyExpanded = devices.map { it.address }.toSet())
            }
        }
        // Above the title, between it and the menu
        fun barColor(): Int {
            compose.waitForIdle()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap.getPixel(view.width / 2, 2)
        }
        assertEquals(scheme.surface.toArgb(), barColor())
        compose.onNodeWithTag(deviceTag(devices.last())).performScrollTo()
        assertEquals(scheme.surfaceContainer.toArgb(), barColor())
        // The saved devices are at the top
        compose.onRoot().performTouchInput { swipeLeft() }
        assertEquals(scheme.surface.toArgb(), barColor())
        compose.onRoot().performTouchInput { swipeRight() }
        assertEquals(scheme.surfaceContainer.toArgb(), barColor())
    }
}
