plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.sentinel.app"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "dev.sentinel.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        val releaseApiBase = providers.gradleProperty("sentinelApiBaseUrl").orElse("https://api.example.com").get()
        buildConfigField("String", "API_BASE_URL", "\"$releaseApiBase\"")
        val googleWebClientId = providers.gradleProperty("sentinelGoogleWebClientId").orElse("").get()
        val escapedGoogleWebClientId = googleWebClientId.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$escapedGoogleWebClientId\"")
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    buildFeatures { compose = true; buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug {
            val debugApiBase = providers.gradleProperty("sentinelDebugApiBaseUrl").orElse("http://10.0.2.2:8080").get()
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBase\"")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Build the Rust core for Android ABIs before CMake links the small JNI bridge.
val buildRustCore by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64", "-o", "android-app/src/main/jniLibs", "build", "--release", "--lib", "--no-default-features")
}
tasks.matching { it.name.startsWith("configureCMake") }.configureEach { dependsOn(buildRustCore) }
