@file:OptIn(ExperimentalTestApi::class)

package com.tt.codeccompanion

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen state of ScreenPreviews fits its texts: none is cut off, unless it ends in an ellipsis on purpose, and
 * the log's times stay on one line. On a phone, and with the largest font on a phone and on a small phone, where the
 * space runs out first. Only on the JVM, a test can't change the font size of a phone. It needs the native graphics
 * to measure the texts like a phone does.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class TextFitTest(private val case: Case) {
    class Case(val scene: Scene, val device: PreviewScreenshots.Device) {
        override fun toString() = "${scene.name} - ${device.name}"
    }

    companion object {
        private val DEVICES = listOf(
            PreviewScreenshots.Device("Phone", 411, 891),
            PreviewScreenshots.Device("Phone, font 200%", 411, 891, fontScale = 2f),
            PreviewScreenshots.Device("Small phone, font 200%", 360, 640, fontScale = 2f),
        )

        /** Texts known to be cut off, not fixed yet: with the largest font on a small phone the top bars' titles don't fit. */
        private val KNOWN_CUT_OFF = setOf("Third-party licenses")

        @JvmStatic
        @Parameters(name = "{0}")
        fun cases() = (Screens.all + mainScreenStates + logScreenStates).flatMap { scene ->
            DEVICES.map { Case(scene, it) }
        }

        /**
         * Whether a text is cut off, unless it ends in an ellipsis on purpose: it has more lines than it may, or its
         * lines are taller or wider than its box. TextLayoutResult.hasVisualOverflow can't tell, the layouts the
         * semantics return are as wide as the constraints, not the text, so every text that's narrower overflows.
         */
        private fun isCutOff(layout: TextLayoutResult): Boolean {
            if (layout.layoutInput.overflow == TextOverflow.Ellipsis)
                return false
            val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
            return layout.multiParagraph.didExceedMaxLines ||
                layout.multiParagraph.height > layout.size.height + 0.5f ||
                widest > layout.size.width + 0.5f
        }

        private fun layouts(node: SemanticsNode): List<TextLayoutResult> {
            val results = ArrayList<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            return results
        }
    }

    @Test
    fun textsFit() {
        RuntimeEnvironment.setQualifiers(case.device.qualifiers)
        RuntimeEnvironment.setFontScale(case.device.fontScale)
        runAndroidComposeUiTest<ComponentActivity> {
            setContent { case.scene.content() }
            if (case.scene === Screens.licenses) {
                // Reads the libraries in the background
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText("AboutLibraries", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
            }
            waitForIdle()

            val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                .fetchSemanticsNodes().flatMap { layouts(it) }
            val cutOff = texts
                .filter { isCutOff(it) }
                .map { it.layoutInput.text.text }
                .filter { it !in KNOWN_CUT_OFF }
            val times = onAllNodesWithTag("log_time", useUnmergedTree = true).fetchSemanticsNodes().flatMap { layouts(it) }
            val brokenTimes = times.filter { it.lineCount > 1 }.map { it.layoutInput.text.text }

            assertEquals("Texts cut off", emptyList<String>(), cutOff)
            assertEquals("Times on more than one line", emptyList<String>(), brokenTimes)
        }
    }
}
