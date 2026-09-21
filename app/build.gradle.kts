plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional default for the "gateway domain" field, e.g. `-Pgate.defaultDomain=gateway.example.com` or a line in
// ~/.gradle/gradle.properties. Deliberately empty in the repository: never commit a personal server address.
val defaultGatewayDomain: String = providers.gradleProperty("gate.defaultDomain").getOrElse("").trim()
    .replace("\\", "\\\\").replace("\"", "\\\"")

// Single source of truth for the version. versionCode is derived from it, so it can never fall below an earlier build
// (Android refuses to "downgrade"): major.minor[.patch] -> major*10000 + minor*100 + patch
// (0.3 -> 300, 0.3.1 -> 301, 1.0.0 -> 10000). Releases are tagged v<versionName>.
val appVersionName = "0.3.2"
val appVersionCode: Int = run {
    val parts = appVersionName.split(".").map { it.toIntOrNull() ?: error("versionName must be numeric major.minor[.patch]: $appVersionName") }
    require(parts.size in 2..3 && parts[1] < 100 && parts.getOrElse(2) { 0 } < 100) { "versionName must be major.minor[.patch] with minor and patch below 100: $appVersionName" }
    parts[0] * 10000 + parts[1] * 100 + parts.getOrElse(2) { 0 }
}
tasks.register("printVersion") { doLast { println("versionName=$appVersionName"); println("versionCode=$appVersionCode") } }

android {
    namespace = "com.bishop.healthconnectgate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bishop.healthconnectgate"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
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
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    testImplementation("junit:junit:4.13.2")
    // Android's own org.json is a stub in local unit tests; the real implementation makes JSON code testable on the JVM.
    testImplementation("org.json:json:20250517")
}
