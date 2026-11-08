package com.tt.autobluetoothcodec

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

internal const val REPOSITORY_URL = "https://github.com/Torbenx/AutoBluetoothCodec"

/**
 * Shows the app's version, where its source is, its license, the way to the third-party licenses
 * and what happens to the user's data.
 */
class AboutActivity : AppActivity() {
    private val actions = object : AboutScreenActions {
        override fun back() = finish()
        override fun openUrl(url: String) = this@AboutActivity.openUrl(url)
        override fun openLicenses() = startActivity(Intent(this@AboutActivity, LicensesActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Includes the commit in debug builds, see the versionNameSuffix
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        setContent {
            AppTheme { AboutScreen(version, actions) }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open $url", Toast.LENGTH_SHORT).show()
        }
    }
}

/** What the about screen's controls do, implemented by [AboutActivity]. */
internal interface AboutScreenActions {
    fun back()
    fun openUrl(url: String)
    fun openLicenses()
}

@Composable
private fun AppIcon() {
    if (LocalInspectionMode.current) {
        // Previews have no package manager, so the icon's layers are drawn in a circle instead
        Box(Modifier.size(72.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(108.dp))
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(108.dp))
        }
        return
    }
    val context = LocalContext.current
    // The launcher icon is adaptive, which painterResource can't draw
    val icon = remember(context) {
        context.packageManager.getApplicationIcon(context.applicationInfo).toBitmap().asImageBitmap()
    }
    Image(icon, contentDescription = null, modifier = Modifier.size(72.dp))
}

@Composable
private fun AboutItem(title: String, summary: String, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        modifier = Modifier.testTag(tag).clickable(onClick = onClick)
    )
}

/**
 * The app's license as its title and its paragraphs. The file wraps its lines at a fixed width, which would break
 * the lines a second time in the dialog, so each paragraph is joined to one line.
 */
internal fun licenseParagraphs(text: String): Pair<String, List<String>> {
    // Also with Windows line endings, where git may have checked the file out with them
    val paragraphs = text.trim().split(Regex("""\r?\n\s*\n""")).map { paragraph ->
        paragraph.lines().joinToString(" ") { it.trim() }
    }
    return paragraphs.first() to paragraphs.drop(1)
}

/** The app's license, copied from the repository's LICENSE at build time, see app/build.gradle.kts. */
@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    val (title, paragraphs) = remember(resources) {
        licenseParagraphs(resources.openRawResource(R.raw.app_license).bufferedReader().use { it.readText() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("close_license")) { Text("OK") } },
        title = { Text(title, Modifier.testTag("license_title")) },
        text = {
            Text(
                paragraphs.joinToString("\n\n"),
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag("license_text")
            )
        }
    )
}

/** The about screen, [version] is the app's version name. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun AboutScreen(version: String, actions: AboutScreenActions) {
    var showLicense by rememberSaveable { mutableStateOf(false) }
    if (showLicense)
        LicenseDialog { showLicense = false }
    Scaffold(
        // Lets UiAutomator find the elements by their test tags
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = actions::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        // Also keeps the content clear of display cutouts in landscape
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        // Padding inside the scroll container, so the content scrolls behind the navigation bar
        CappedContent(NARROW_CONTENT, tintSides = true) { side ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(innerPadding)
                    .padding(horizontal = side)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AppIcon()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Version $version",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("app_version")
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Applies the preferred codec of your Bluetooth devices when they connect.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("© 2026 Torben Thaysen", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                AboutItem("Source code", REPOSITORY_URL.removePrefix("https://"), "source_code") {
                    actions.openUrl(REPOSITORY_URL)
                }
                AboutItem("License", "MIT License", "license") { showLicense = true }
                AboutItem("Third-party licenses", "The licenses of the libraries this app uses", "third_party_licenses") {
                    actions.openLicenses()
                }
                // Release builds have no INTERNET permission, only debug builds have it for the Mobly snippet.
                // The log is excluded from backups, see backup_rules.xml.
                ListItem(
                    headlineContent = { Text("Privacy") },
                    supportingContent = {
                        Text("This app has no internet access and collects no data. Its log stays on this phone " +
                            "and is left out of backups, unless you share it.")
                    },
                    modifier = Modifier.testTag("privacy")
                )
            }
        }
    }
}
