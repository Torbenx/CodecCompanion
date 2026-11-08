@file:OptIn(ExperimentalTestApi::class)

package com.tt.autobluetoothcodec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runAndroidComposeUiTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the screens of ScreenPreviews to PNG files on the JVM with Robolectric's native graphics, so they can be
 * looked at without Android Studio or a phone. It's not a screenshot test, it compares nothing. It only runs when
 * asked for:
 *
 *     RENDER_PREVIEWS=1 ./gradlew :app:testDebugUnitTest --tests '*PreviewScreenshots*'
 *
 * The images are in app/build/outputs/previews, named after the scene and the configuration.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PreviewScreenshots(private val shot: Shot) {
    /** A screen size, dark mode and font scale, like the configurations of ScreenPreviews. */
    class Device(val name: String, val widthDp: Int, val heightDp: Int, val dark: Boolean = false, val fontScale: Float = 1f) {
        val qualifiers get() = "w${widthDp}dp-h${heightDp}dp-${if (widthDp > heightDp) "land" else "port"}-" +
            "${if (dark) "night" else "notnight"}-xhdpi"
    }

    /** A scene in a configuration, with what's done to it before it's captured. */
    class Shot(val group: String, val scene: Scene, val device: Device, val interact: Interaction = {}) {
        val fileName get() = "$group - ${scene.name} - ${device.name}.png".replace(Regex("[^A-Za-z0-9 .,()%-]"), "_")
        override fun toString() = fileName
    }

    companion object {
        private val PHONE = Device("Phone", 411, 891)
        private val PHONE_DARK = Device("Phone, dark", 411, 891, dark = true)
        private val CONFIGURATIONS = listOf(
            PHONE,
            PHONE_DARK,
            Device("Phone, font 200%", 411, 891, fontScale = 2f),
            Device("Phone, landscape", 891, 411),
            Device("Small phone", 360, 640),
            Device("Foldable", 673, 841),
            Device("Tablet", 1280, 800),
        )

        /** The licenses screen reads the libraries in the background. */
        private val waitForLicenses: Interaction = {
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("AboutLibraries", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }

        /** Scenes that need a tap or two, which the previews can't do. */
        private val interactions = listOf<Pair<Scene, Pair<String, Interaction>>>(
            Screens.main to ("App menu" to { onNodeWithTag("app_menu").performClick() }),
            Screens.main to ("Lists shown" to { onNodeWithTag("list_toggle").performClick() }),
            Screens.main to ("Saved devices" to {
                onNodeWithTag("list_toggle").performClick()
                onNodeWithTag("list_saved").performClick()
            }),
            emptyStates.first { it.name == "No devices" } to ("Nothing saved" to {
                onNodeWithTag("list_toggle").performClick()
                onNodeWithTag("list_saved").performClick()
            }),
            // Only debug builds have the device menu, for unlinking
            mainScreenStates.first { it.name == "No codec info, long name, debug options" } to
                ("Device menu" to { onAllNodesWithTag("device_menu")[0].performClick() }),
            Screens.log to ("Share dialog" to { onNodeWithTag("share_log").performClick() }),
            Screens.log to ("Log menu" to { onNodeWithTag("log_menu").performClick() }),
            Screens.log to ("Clear dialog" to {
                onNodeWithTag("log_menu").performClick()
                onNodeWithTag("clear_log").performClick()
            }),
            Screens.log to ("Disable logging dialog" to {
                onNodeWithTag("log_menu").performClick()
                onNodeWithTag("disable_logging").performClick()
            }),
            Screens.log to ("Operation expanded" to { onNodeWithText("Codec chosen", substring = true).performClick() }),
            Screens.log to ("JSON shown" to {
                onNodeWithText("Couldn't read the codec").performTouchInput { longClick() }
            }),
            Screens.about to ("License dialog" to { onNodeWithTag("license").performScrollTo().performClick() }),
        )

        @JvmStatic
        @Parameters(name = "{0}")
        fun shots(): List<Shot> {
            val shots = ArrayList<Shot>()
            for (scene in Screens.all)
                for (device in CONFIGURATIONS)
                    shots.add(Shot("1 Screens", scene, device, if (scene === Screens.licenses) waitForLicenses else ({})))
            for (scene in mainScreenStates)
                // The empty states have their own layout, which has to fit everywhere
                for (device in if (scene in emptyStates) CONFIGURATIONS else listOf(PHONE, PHONE_DARK))
                    shots.add(Shot("2 Main states", scene, device))
            for (scene in logScreenStates)
                for (device in listOf(PHONE, PHONE_DARK))
                    shots.add(Shot("3 Log states", scene, device))
            for ((scene, interaction) in interactions)
                for (device in listOf(PHONE, PHONE_DARK))
                    shots.add(Shot("4 Interactions", Scene("${scene.name}, ${interaction.first}", scene.content), device,
                        interaction.second))
            for (scene in sniffingFrames)
                shots.add(Shot("5 Sniffing", scene, PHONE))
            return shots
        }

        private val outputDir = File("build/outputs/previews")

    }

    @Test
    fun render() {
        assumeTrue("Set RENDER_PREVIEWS to render the previews", System.getenv("RENDER_PREVIEWS") != null)
        RuntimeEnvironment.setQualifiers(shot.device.qualifiers)
        RuntimeEnvironment.setFontScale(shot.device.fontScale)
        runAndroidComposeUiTest<ComponentActivity> {
            setContent { shot.scene.content() }
            waitForIdle()
            shot.interact(this)
            waitForIdle()
            outputDir.mkdirs()
            File(outputDir, shot.fileName).outputStream().use {
                captureWindows().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /**
     * Draws the windows, the activity's and those of dialogs and menus, onto one bitmap. Each window is rendered
     * like on a phone, with Robolectric's hardware renderer, since drawing a view onto a canvas leaves out the
     * shadows of elevated surfaces.
     */
    @Suppress("UNCHECKED_CAST")
    private fun captureWindows(): Bitmap {
        val globalClass = Class.forName("android.view.WindowManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        fun <T> field(name: String) = globalClass.getDeclaredField(name).apply { isAccessible = true }.get(global) as List<T>
        val views = field<View>("mViews")
        val params = field<WindowManager.LayoutParams>("mParams")
        val root = views.first()
        // Robolectric's HardwareRenderingScreenshot.takeScreenshot(View, Bitmap), which is internal to it
        val renderHardware = Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot")
            .getDeclaredMethod("takeScreenshot", View::class.java, Bitmap::class.java)
            .apply { isAccessible = true }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        for ((view, param) in views.zip(params)) {
            if (view.width == 0 || view.height == 0)
                continue
            if (param.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                canvas.drawColor(Color.argb((param.dimAmount * 255).toInt(), 0, 0, 0))
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            // Robolectric leaves dialogs at the top left, popups place themselves
            if (param.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL)
                location[0] = (root.width - view.width) / 2
            if (param.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.CENTER_VERTICAL)
                location[1] = (root.height - view.height) / 2
            val window = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            renderHardware.invoke(null, view, window)
            canvas.drawBitmap(window, location[0].toFloat(), location[1].toFloat(), null)
        }
        return bitmap
    }
}

private typealias Interaction = AndroidComposeUiTest<ComponentActivity>.() -> Unit
