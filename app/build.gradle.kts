plugins {
id("com.android.application")
id("org.jetbrains.kotlin.android")
id("org.jetbrains.kotlin.plugin.compose")
id("com.google.devtools.ksp")
}

android {
namespace = "com.daoncare.rxscanner"
compileSdk = 35

defaultConfig {
    applicationId = "com.daoncare.rxscanner.test"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0-ocr-test"
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
implementation(platform("androidx.compose:compose-bom:2025.02.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-extended")
implementation("androidx.activity:activity-compose:1.10.0")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

implementation("com.google.mlkit:text-recognition-korean:16.0.1")
implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
ksp("com.google.mlkit:genai-schema-compiler:1.0.0-alpha1")

implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
