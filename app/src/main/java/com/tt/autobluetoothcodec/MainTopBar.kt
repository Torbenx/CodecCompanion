package com.tt.autobluetoothcodec

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The main screen's lists of devices, with their names in the title and their short names in the switch. */
internal enum class DeviceList(val label: String, val shortLabel: String) {
    CONNECTED("Connected devices", "Connected"),
    SAVED("Saved devices", "Saved"),
}

/**
 * The main screen's top bar, like Google Calendar's month: the title is the list's name with an arrow, which opens
 * segmented buttons below the bar to switch between the lists. The bar, and the buttons with it, are tinted while the
 * list in view is [scrolled] under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainTopBar(
    list: DeviceList,
    onList: (DeviceList) -> Unit,
    scrolled: Boolean,
    actions: MainScreenActions,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val color = topBarColor(scrolled)
    Column {
        TopAppBar(
            title = { ListToggle(list, open) { open = !open } },
            actions = { AppMenu(actions::openLog, actions::openAbout) },
            colors = topBarColors(color)
        )
        AnimatedVisibility(open) {
            Surface(color = color) {
                ListSwitch(list, onList)
            }
        }
    }
}

/**
 * The title: the list's name if it fits beside the arrow, else its short name, else only the arrow, so the bar
 * never cuts it off, in a narrow window or with a large font. The arrow points up while the switch is open.
 */
@Composable
private fun ListToggle(list: DeviceList, open: Boolean, onToggle: () -> Unit) {
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val style = LocalTextStyle.current
        val density = LocalDensity.current
        // The arrow and the padding around the name
        val around = 24.dp + 12.dp
        val label = listOf(list.label, list.shortLabel).firstOrNull {
            val width = with(density) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width.toDp() }
            width + around <= maxWidth
        }
        val turn by animateFloatAsState(if (open) 180f else 0f, label = "arrow")
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = list.label
                    stateDescription = if (open) "Lists shown" else "Lists hidden"
                }
                .testTag("list_toggle")
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (label != null)
                Text(label, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.rotate(turn))
        }
    }
}

/** Segmented buttons between the lists, without the check and in the tonal buttons' colors, like Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListSwitch(list: DeviceList, onList: (DeviceList) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
    ) {
        for (entry in DeviceList.entries) {
            SegmentedButton(
                selected = entry == list,
                onClick = { onList(entry) },
                shape = SegmentedButtonDefaults.itemShape(entry.ordinal, DeviceList.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = scheme.primaryContainer,
                    activeContentColor = scheme.onPrimaryContainer,
                ),
                icon = {},
                modifier = Modifier.testTag("list_${entry.name.lowercase()}")
            ) {
                Text(entry.shortLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
