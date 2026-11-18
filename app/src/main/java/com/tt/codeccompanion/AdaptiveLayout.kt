package com.tt.codeccompanion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The main screen's and the about screen's width at most, wide enough for a card and its chips. */
internal val NARROW_CONTENT = 600.dp

/** The log's and the licenses' width at most, they're mostly text. */
internal val WIDE_CONTENT = 720.dp

/** Beside the content, less than this isn't tinted, it would only be a stripe. */
internal val MIN_TINTED_SIDE = 32.dp

/** The space on either side of content at most [cap] wide, centered in a window [available] wide. */
internal fun sideSpace(available: Dp, cap: Dp): Dp = maxOf((available - cap) / 2, 0.dp)

/**
 * Fills the window and passes [content] the space to leave on either side, so it's at most [cap] wide and centered
 * in wide windows. The scroll containers stay as wide as the window, so they also scroll when touched beside the
 * content, and put the space inside. With [tintSides], the space is tinted once it's at least [MIN_TINTED_SIDE]
 * wide; not on the main screen, where the cards already stand out from the background.
 */
@Composable
internal fun CappedContent(cap: Dp, tintSides: Boolean, content: @Composable (side: Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = sideSpace(maxWidth, cap)
        val tinted = if (!tintSides || side < MIN_TINTED_SIDE) {
            Modifier
        } else {
            val tint = MaterialTheme.colorScheme.surfaceContainer
            Modifier.drawBehind {
                val width = side.toPx()
                drawRect(tint, size = Size(width, size.height))
                drawRect(tint, topLeft = Offset(size.width - width, 0f), size = Size(width, size.height))
            }
        }
        Box(Modifier.fillMaxSize().then(tinted)) {
            content(side)
        }
    }
}
