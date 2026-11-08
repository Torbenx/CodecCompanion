package com.tt.autobluetoothcodec

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * The color of a top bar, and of what extends it below: Material's for an unscrolled bar, and its tint while the
 * content is [scrolled] under it. The screens tell from their scroll states, not from a scroll behavior, which only
 * follows the user's own scrolling: so the bar is tinted as well after the app scrolled the content, or restored where
 * it was scrolled to.
 */
@Composable
internal fun topBarColor(scrolled: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    val color by animateColorAsState(if (scrolled) scheme.surfaceContainer else scheme.surface, label = "bar")
    return color
}

/** A top bar's colors in [topBarColor], whether or not a scroll behavior thinks it's scrolled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun topBarColors(scrolled: Boolean): TopAppBarColors = topBarColors(topBarColor(scrolled))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun topBarColors(color: Color): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = color, scrolledContainerColor = color)
