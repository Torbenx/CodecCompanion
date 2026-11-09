package com.tt.autobluetoothcodec

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.UserManager
import androidx.activity.ComponentActivity
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * The app's texts are only in English, so its locale is English as well, with the region and the regional preferences,
 * like the hour cycle or the first day of the week, of the system's locale. Dates and numbers then read like the
 * texts around them, "yesterday" and "Monday" instead of the system language's words, but in the user's order. Where
 * Android has no data for English in that region, it falls back to English's own formats.
 *
 * Android only has per-app languages since API 33, and AndroidX only backports them for AppCompat activities, so the
 * locale is applied by hand on every API level: [CodecApplication] and [AppActivity] set the process's default, which
 * formatters like DateUtils and java.time use, and [AppActivity] the configuration of each activity, which Compose uses.
 */
object AppLocale {
    /** English with the region and Unicode extensions of [system]. */
    fun of(system: Locale): Locale {
        return try {
            Locale.Builder().setLocale(system).setLanguage("en").setScript("").setVariant("").build()
        } catch (e: IllformedLocaleException) {
            Locale.Builder().setLanguage("en").setRegion(system.country).build()
        }
    }

    /** The app's locale for the system's current one. Resources.getSystem() isn't changed by the app's overrides. */
    fun current(): Locale = of(Resources.getSystem().configuration.locales[0])

    /** Makes the app's locale the process's default, again after every configuration change. */
    fun applyDefault() {
        Locale.setDefault(current())
    }

    /** The configuration that overrides an activity's locale with the app's, leaving everything else as it is. */
    fun overrideConfiguration(): Configuration = Configuration().apply { setLocale(current()) }
}

/**
 * The app's process, which applies [AppLocale] to formatters. The system sets the default locale to its own whenever the
 * configuration changes, before calling [onConfigurationChanged], and when it launches an activity, see [AppActivity].
 */
class CodecApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.applyDefault()
        // So the public key is there before the next reboot, whatever started the process. EventLog makes them when
        // it notices the unlock, for a process started before it
        if (getSystemService(UserManager::class.java).isUserUnlocked)
            LockedStorageCrypto.ensureKeys(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.applyDefault()
    }
}

/**
 * An activity in [AppLocale]. A change of the system's locale recreates it, which applies the new one. Launching an
 * activity sets the process's default locale back to the system's without telling the application, on the S24 with
 * API 37 at least, so each activity applies it again once it's attached.
 */
abstract class AppActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(AppLocale.overrideConfiguration())
        AppLocale.applyDefault()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.applyDefault()
    }
}
