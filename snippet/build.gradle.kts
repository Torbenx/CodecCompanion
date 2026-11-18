import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Mobly snippet for the device tests. It's instrumentation that runs inside the app's process,
// so it uses the app's own instances, see TestConcept.md section 8.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tt.codeccompanion.snippet"
    compileSdk = 36
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 28
        targetSdk = 36

        testInstrumentationRunner = "com.google.android.mobly.snippet.SnippetRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.mobly.snippet.lib)
    implementation(libs.androidx.test.runner)
    // Only the app's own classes are on the compile classpath. Its libraries come from the app at runtime.
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
}
