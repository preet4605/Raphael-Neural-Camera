plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.neuralcamera.runtime"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation(project(":models"))
    implementation(project(":device-profiles"))
    implementation(project(":benchmarks"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // ONNX Runtime with the QNN execution provider. Its POM pulls Qualcomm's com.qualcomm.qti:qnn-runtime (HTP stubs and
    // skels), so the app bundles its own QNN runtime instead of depending on vendor libraries a normal app cannot load.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    testImplementation("junit:junit:4.13.2")
}
