package com.tt.codeccompanion

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.codeccompanion.SampleData.ADDRESS
import com.tt.codeccompanion.SampleData.device
import com.tt.codeccompanion.SampleData.state
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** In wide windows the content is capped and centered, on phones it has the full width. */
@RunWith(AndroidJUnit4::class)
class CappedContentTest {
    @get:Rule
    val compose = createComposeRule()

    /** The card's left edge and width in dp, inside its 16 dp margin. */
    private fun cardBounds(): Pair<Float, Float> {
        compose.setContent { AppTheme { MainScreen(state(device()), NoMainActions) } }
        val bounds = compose.onNodeWithTag("device_$ADDRESS").getUnclippedBoundsInRoot()
        return bounds.left.value to bounds.width.value
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun aTabletsCardsAreCappedAndCentered() {
        val (left, width) = cardBounds()
        assertEquals(600f - 32f, width, 0.5f)
        assertEquals((1280f - 600f) / 2 + 16f, left, 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun aPhonesCardsHaveTheFullWidth() {
        val (left, width) = cardBounds()
        assertEquals(411f - 32f, width, 0.5f)
        assertEquals(16f, left, 0.5f)
    }

    @Test
    fun theSideSpaceIsNeverNegative() {
        assertEquals(340.dp, sideSpace(1280.dp, 600.dp))
        assertEquals(0.dp, sideSpace(411.dp, 600.dp))
    }
}
