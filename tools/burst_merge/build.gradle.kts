// Standalone JVM tool (not part of the Android build): runs the RAW Bayer temporal merge on a folder of DNG frames,
// for example the DNGs a Gate 1 run writes. Uses the pure-Kotlin sources of :neural-isp and :benchmarks directly.
//
//   ./gradlew -p tools/burst_merge run --args="--input <dir with frame_*.dng> --output <out dir>"
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir("../../neural-isp/src/main/java")
            srcDir("../../benchmarks/src/main/java")
            include(
                "BurstMergeTool.kt",
                "com/neuralcamera/isp/temporal/**",
                "com/neuralcamera/isp/dng/**",
                "com/neuralcamera/isp/color/**",
                "com/neuralcamera/isp/encode/**",
                "com/neuralcamera/benchmarks/Json.kt"
            )
        }
    }
}

application {
    mainClass.set("BurstMergeToolKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}
