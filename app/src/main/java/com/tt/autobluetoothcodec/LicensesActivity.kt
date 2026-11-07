package com.tt.autobluetoothcodec

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

/**
 * Lists the libraries the app uses with their licenses. The AboutLibraries Gradle plugin collects
 * them from the dependencies at build time, tapping one shows its license text.
 */
class LicensesActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                LicensesScreen(onBack = ::finish)
            }
        }
    }
}

/** The third-party licenses screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Third-party licenses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        // Also keeps the content clear of display cutouts in landscape
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        // Padding inside the list, so the entries scroll behind the navigation bar
        CappedContent(WIDE_CONTENT, tintSides = true) { side ->
            val layoutDirection = LocalLayoutDirection.current
            LibrariesContainer(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + side,
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(layoutDirection) + side,
                    bottom = innerPadding.calculateBottomPadding()
                )
            )
        }
    }
}
