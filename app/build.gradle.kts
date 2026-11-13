plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sojibhuiya.jarves"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sojibhuiya.jarves"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.2.7"
    }

    // ---- 0.2.7: ONE fixed signing key for every installed APK.
    // Before this, `debug` had no signingConfig at all, so Gradle generated a fresh debug keystore
    // on every CI run: the certificate changed from build to build and Android refused the update
    // with "App not installed". Debug and release now share the same fixed keystore
    // (jarves-release.jks, restored from the JARVES_* secrets), so their certificates are equal
    // across runs and an APK can install over the previous one.
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("jarves-release.jks")
            storePassword = System.getenv("JARVES_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("JARVES_KEY_ALIAS")
            keyPassword = System.getenv("JARVES_KEY_PASSWORD")
            // minSdk 26: v2/v3 are what the app is signed with; v1 is not needed.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    // True only when the keystore file exists and its password is present, i.e. in CI.
    val hasFixedKey = rootProject.file("jarves-release.jks").exists() &&
        !System.getenv("JARVES_KEYSTORE_PASSWORD").isNullOrBlank()

    buildTypes {
        getByName("debug") {
            if (hasFixedKey) {
                signingConfig = signingConfigs.getByName("fixed")
            }
            isMinifyEnabled = false
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Design 4 uses runInterruptible() and withTimeoutOrNull() directly, so the coroutines
    // runtime must not be left to a transitive version.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    // Exercises the REAL OkHttp transport (incl. gzip/chunked decoding) instead of a fake —
    // the gap that let the 0.2.2 "no internet connection" bug through.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
