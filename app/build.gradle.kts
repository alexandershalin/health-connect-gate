plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional default for the "gateway domain" field, e.g. `-Pgate.defaultDomain=gateway.example.com` or a line in
// ~/.gradle/gradle.properties. Deliberately empty in the repository: never commit a personal server address.
val defaultGatewayDomain: String = providers.gradleProperty("gate.defaultDomain").getOrElse("").trim()
    .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.bishop.healthconnectgate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bishop.healthconnectgate"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "DEFAULT_GATEWAY_DOMAIN", "\"$defaultGatewayDomain\"")
    }

    // Release signing comes from the environment (CI secrets, or a keystore kept outside the repository).
    // Nothing secret is stored in this file. Without these variables the release variant stays unsigned.
    val releaseKeystore: String? = System.getenv("GATE_KEYSTORE_FILE")
    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("GATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GATE_KEY_ALIAS")
                keyPassword = System.getenv("GATE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off on purpose: SyncEngine serialises Health Connect records by reflection.
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
