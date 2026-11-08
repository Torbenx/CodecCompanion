import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Collects the licenses of the dependencies for the third-party licenses screen
    alias(libs.plugins.aboutlibraries)
}

/** The commit the working tree is at, with "-dirty" if it has changes. */
fun gitDescription(): String {
    return try {
        providers.exec {
            commandLine("git", "describe", "--always", "--dirty")
        }.standardOutput.asText.get().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

android {
    namespace = "com.tt.autobluetoothcodec"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tt.autobluetoothcodec"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Lets the test harness tell which build is installed, like "1.0-fd46a1d-dirty"
            versionNameSuffix = "-" + gitDescription()
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        disable.add("MissingPermission")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric's SDK 36 sets up the application's shared memory through JDK internals
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
    sourceSets {
        // The UI tests run on the JVM with Robolectric and on the phones, see TestConcept.md section 7
        for (name in listOf("test", "androidTest")) {
            getByName(name) {
                java.srcDir("src/sharedTest/java")
                resources.srcDir("src/sharedTest/resources")
            }
        }
    }
}

/** Copies the app's own license into the raw resources, so the about screen shows the same text as the repository. */
abstract class CopyLicenseTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val raw = outputDir.get().asFile.resolve("raw")
        raw.deleteRecursively()
        raw.mkdirs()
        license.get().asFile.copyTo(raw.resolve("app_license.txt"))
    }
}

androidComponents {
    // The UI tests use the example data in the debug source set, see SampleData
    beforeVariants(selector().withBuildType("release")) {
        it.enableUnitTest = false
    }
    onVariants { variant ->
        val copyLicense = tasks.register<CopyLicenseTask>("copy${variant.name.replaceFirstChar { it.uppercase() }}License") {
            license.set(rootProject.layout.projectDirectory.file("LICENSE"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(copyLicense, CopyLicenseTask::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.aboutlibraries.compose.m3)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}