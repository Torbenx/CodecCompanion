package com.tt.autobluetoothcodec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The bars of the other screens are tinted while their content is scrolled, like the main screen's, see [MainTopBarTest]. */
@RunWith(AndroidJUnit4::class)
// Draws the screen like a phone, Robolectric's default graphics draw nothing
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopBarTintTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var scheme: ColorScheme
    private lateinit var view: View

    private fun show(scene: Scene) = compose.setContent {
        view = LocalView.current
        AppTheme { scheme = MaterialTheme.colorScheme }
        scene.content()
    }

    // Above the title, in the middle of the bar
    private fun barColor(): Int {
        compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap.getPixel(view.width / 2, 2)
    }

    private fun assertTintedOnlyWhileScrolled() {
        assertEquals(scheme.surface.toArgb(), barColor())
        compose.onRoot().performTouchInput { swipeUp() }
        assertEquals(scheme.surfaceContainer.toArgb(), barColor())
        // From the middle, swipeDown's default start is on the bar, which doesn't scroll
        repeat(3) { compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) } }
        assertEquals(scheme.surface.toArgb(), barColor())
    }

    @Test
    fun theAboutScreensBar() {
        show(Screens.about)
        assertTintedOnlyWhileScrolled()
    }

    @Test
    fun theLogsBar() {
        show(Screens.log)
        assertTintedOnlyWhileScrolled()
    }

    @Test
    fun theThirdPartyLicensesBar() {
        show(Screens.licenses)
        // The screen reads the libraries in the background
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("AboutLibraries", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTintedOnlyWhileScrolled()
    }
}
