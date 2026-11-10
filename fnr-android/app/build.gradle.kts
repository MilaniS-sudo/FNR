import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Gateway address and app token come from local.properties (never shared or handed in).
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val gatewayUrl: String = localProps.getProperty("FNR_GATEWAY_URL", "http://10.0.2.2:8000/")
val appToken: String = localProps.getProperty("FNR_APP_TOKEN", "change-me-demo-token")

android {
    namespace = "za.ac.richfield.fnr"
    compileSdk = 34

    defaultConfig {
        applicationId = "za.ac.richfield.fnr"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GATEWAY_URL", "\"$gatewayUrl\"")
        buildConfigField("String", "APP_TOKEN", "\"$appToken\"")
    }

    buildTypes {
        debug {
            // Debug builds may talk to a Gateway on your laptop over plain HTTP (same Wi-Fi).
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            // Release builds only allow HTTPS (NFR-SEC: data encrypted in transit).
            manifestPlaceholders["cleartext"] = "false"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX core and UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Architecture (MVVM)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Database: Room on SQLite, encrypted with SQLCipher
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Background work (AI processing, automatic backup)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Audio playback with clipping
    implementation("androidx.media3:media3-exoplayer:1.4.1")

    // Networking to the AI Gateway
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Unit tests (Phase 5): run with the green arrow next to the test class
    testImplementation("junit:junit:4.13.2")
}
