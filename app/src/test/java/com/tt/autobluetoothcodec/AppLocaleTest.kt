package com.tt.autobluetoothcodec

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLocaleTest {
    private fun appLocale(system: String) = AppLocale.of(Locale.forLanguageTag(system)).toLanguageTag()

    @Test
    fun keepsTheRegionInEnglish() {
        assertEquals("en-DE", appLocale("de-DE"))
        assertEquals("en-US", appLocale("en-US"))
        assertEquals("en-GB", appLocale("en-GB"))
    }

    @Test
    fun keepsTheRegionalPreferences() {
        // Android 14's regional preferences: the hour cycle and the first day of the week
        assertEquals("en-US-u-fw-mon-hc-h23", appLocale("en-US-u-fw-mon-hc-h23"))
        assertEquals("en-FR-u-fw-sun", appLocale("fr-FR-u-fw-sun"))
    }

    @Test
    fun dropsTheScriptAndVariant() {
        assertEquals("en-TW", appLocale("zh-Hant-TW"))
        assertEquals("en-DE", appLocale("de-DE-1996"))
    }

    @Test
    fun withoutARegionItsPlainEnglish() {
        assertEquals("en", appLocale("de"))
        assertEquals("en", appLocale("und"))
    }
}
