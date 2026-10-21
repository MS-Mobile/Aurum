import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.msmobile.aurum"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.msmobile.aurum"
        minSdk = 29
        targetSdk = 37
        versionCode = System.getenv(EnvKeys.VERSION_CODE)?.toIntOrNull() ?: 1
        versionName = requireVersionName()

        testInstrumentationRunner = "com.msmobile.aurum.HiltTestRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = releaseKeystorePath()
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv(EnvKeys.KEYSTORE_PASSWORD)
                keyAlias = System.getenv(EnvKeys.KEYSTORE_ALIAS)
                keyPassword = System.getenv(EnvKeys.KEYSTORE_PASSWORD)
            }
        }
    }

    buildTypes {
        debug {
            // x86_64 only so debug builds also run on the emulator.
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            ndk {
                abiFilters += "arm64-v8a"
            }
            optimization {
                enable = true
            }
            // CI signs with the release keystore (see release-build.yml); local builds fall back to the debug key
            // so the release APK still installs directly.
            signingConfig = if (releaseKeystorePath() != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // Release-like (not debuggable, so ART compiles fully) but unminified, so instrumented tests measure
        // realistic processing times.
        create("profiling") {
            initWith(getByName("release"))
            optimization {
                enable = false
            }
            matchingFallbacks += "release"
        }
    }
    // tools/run-reference-test.sh passes -Paurum.testBuildType=profiling to time the port realistically.
    testBuildType = providers.gradleProperty("aurum.testBuildType").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // The reference script, spec and test photo live in assets/ but are not needed at runtime.
        ignoreAssetsPattern = "!*.py:!*.pyc:!<dir>__pycache__:!*.md:!*.jpg"
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.opencv)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

object EnvKeys {
    const val VERSION_CODE = "VERSION_CODE"
    const val KEYSTORE_FILE = "KEYSTORE_FILE"
    const val KEYSTORE_PASSWORD = "KEYSTORE_PASSWORD"
    const val KEYSTORE_ALIAS = "KEYSTORE_ALIAS"
}

/** The release keystore from the environment (set by CI), or null when absent or blank. */
fun releaseKeystorePath(): String? = System.getenv(EnvKeys.KEYSTORE_FILE)?.takeIf { it.isNotBlank() }

fun requireVersionName(): String {
    val versionPropsFile = file("${rootProject.projectDir}/version.properties")
    if (!versionPropsFile.exists()) {
        error("version.properties file not found at ${versionPropsFile.absolutePath}")
    }
    val versionProps = Properties().apply {
        versionPropsFile.inputStream().use { load(it) }
    }
    return versionProps.getProperty("versionName")
        ?: error("versionName property not found in version.properties")
}
