package com.tt.autobluetoothcodec

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withContext
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.state
import com.tt.autobluetoothcodec.UiTestData.RecordingActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** V13 of the test concept: the about screen and the way to it from the main screen's menu. */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = object : AboutScreenActions {
        val calls = ArrayList<String>()
        override fun back() { calls.add("back") }
        override fun openUrl(url: String) { calls.add("openUrl $url") }
        override fun openLicenses() { calls.add("openLicenses") }
    }

    @Test
    fun theMainMenuOpensTheAboutScreen() {
        val mainActions = RecordingActions()
        compose.setContent { MainScreen(state(device()), mainActions) }
        compose.onNodeWithTag("app_menu").performClick()
        compose.onNodeWithTag("open_about").performClick()
        assertEquals(listOf("openAbout"), mainActions.calls)
    }

    @Test
    fun itemsOpenTheirTargets() {
        compose.setContent { AboutScreen("1.0-abc1234", actions) }
        compose.onNodeWithTag("app_version").assertTextEquals("Version 1.0-abc1234")
        // Scrolled into view since the items may not fit on the screen
        compose.onNodeWithTag("source_code").performScrollTo().performClick()
        compose.onNodeWithTag("third_party_licenses").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(
            listOf("openUrl $REPOSITORY_URL", "openLicenses", "back"),
            actions.calls
        )
    }

    @Test
    fun theLicenseIsShownInTheApp() {
        compose.setContent { AboutScreen("1.0", actions) }
        compose.onNodeWithTag("license").performScrollTo().performClick()
        compose.onNodeWithTag("license_title").assertIsDisplayed().assertTextEquals("MIT License")
        // The repository's LICENSE, with its lines joined to paragraphs
        compose.onNodeWithTag("license_text")
            .assertTextContains("Copyright (c) 2026 Torben Thaysen", substring = true)
            .assertTextContains("Permission is hereby granted, free of charge, to any person obtaining a copy of this",
                substring = true)
        compose.onNodeWithTag("close_license").performClick()
        compose.onNodeWithTag("license_text").assertDoesNotExist()
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun theThirdPartyLicensesAreCollected() {
        // Collected by the AboutLibraries Gradle plugin at build time, the screen shows an empty list without them
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val libraries = Libs.Builder().withContext(context).build().libraries
        val material3 = libraries.single { it.uniqueId == "androidx.compose.material3:material3-android" }
        assertEquals(listOf("Apache-2.0"), material3.licenses.map { it.spdxId })
    }
}
