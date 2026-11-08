package com.tt.autobluetoothcodec

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A screen with nothing to show: a graphic, a short text and a button offering a way out. The graphic shrinks in short
 * windows, and the whole scrolls if it still doesn't fit, as with a large font in landscape. The padding is inside the
 * scroll container, so the content scrolls behind the navigation bar like the cards do. It scrolls by [scroll].
 */
@Composable
internal fun EmptyState(
    scene: DogScene,
    text: String,
    buttonLabel: String,
    onClick: () -> Unit,
    contentPadding: PaddingValues,
    supportingTexts: List<String> = emptyList(),
    scroll: ScrollState = rememberScrollState(),
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available = maxHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
        val artHeight = (available * 0.42f).coerceAtMost(200.dp)
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight)
                .padding(contentPadding).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DogArt(scene, Modifier.height(artHeight).aspectRatio(DOG_ART_RATIO, matchHeightConstraintsFirst = true))
            Spacer(Modifier.height(16.dp))
            Text(text, Modifier.widthIn(max = 400.dp), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center)
            for (supportingText in supportingTexts) {
                Spacer(Modifier.height(8.dp))
                Text(supportingText, Modifier.widthIn(max = 400.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick) { Text(buttonLabel) }
        }
    }
}
