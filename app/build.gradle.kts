plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.neuralcamera.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.neuralcamera.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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
        compose = true
    }
    packaging {
        jniLibs {
            // The Hexagon skel libraries are loaded by the DSP from the filesystem, so native libs must be extracted.
            useLegacyPackaging = true
            // Keep only what a Snapdragon 8 Elite Gen 5 (HTP V81) needs. Remove these lines to run on other SoCs.
            excludes += setOf(
                "**/libQnnDsp*.so",
                "**/libQnnGpu.so",
                "**/libQnnHtpV68*.so",
                "**/libQnnHtpV69*.so",
                "**/libQnnHtpV73*.so",
                "**/libQnnHtpV75*.so",
                "**/libQnnHtpV79*.so"
            )
        }
    }
}

dependencies {
    implementation(project(":models"))
    implementation(project(":device-profiles"))
    implementation(project(":camera-core"))
    implementation(project(":capture-intelligence"))
    implementation(project(":neural-runtime"))
    implementation(project(":neural-isp"))
    implementation(project(":quality-engine"))
    implementation(project(":video-engine"))
    implementation(project(":gallery"))
    implementation(project(":benchmarks"))
    implementation(project(":ui"))
    implementation(project(":data-lab"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

