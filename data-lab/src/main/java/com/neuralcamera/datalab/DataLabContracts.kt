package com.neuralcamera.datalab

enum class SceneCategory {
    DAYLIGHT,
    INDOOR,
    LOW_LIGHT,
    VERY_LOW_LIGHT,
    BACKLIGHT,
    HIGH_DYNAMIC_RANGE,
    FINE_TEXTURE,
    TEXT,
    FOLIAGE,
    SKIN,
    MOVING_SUBJECTS,
    NIGHT_LIGHTS,
    REFLECTIVE_SURFACES,
    REPEATED_PATTERNS
}

data class ReferenceSceneDescriptor(
    val sceneId: String,
    val category: SceneCategory,
    val description: String,
    val targetLux: Float,
    val targetMotionRadS: Float,
    val baselinePsnrMin: Float,
    val maxAcceptableHallucinationRisk: Float = 0.15f
)

data class RegressionVerificationReport(
    val totalScenes: Int,
    val passedScenes: Int,
    val regressions: List<String>,
    val isRegressionFree: Boolean
)

object ReferenceDatasetCatalog {

    val CONTROLLED_SCENES = listOf(
        ReferenceSceneDescriptor("SCENE_01_DAYLIGHT", SceneCategory.DAYLIGHT, "Outdoor midday landscape, broad dynamic range", 2500f, 0.01f, 38f),
        ReferenceSceneDescriptor("SCENE_02_INDOOR", SceneCategory.INDOOR, "Office interior with mixed artificial lighting", 250f, 0.02f, 35f),
        ReferenceSceneDescriptor("SCENE_03_LOW_LIGHT", SceneCategory.LOW_LIGHT, "Dim restaurant interior with warm lamps", 15f, 0.05f, 30f),
        ReferenceSceneDescriptor("SCENE_04_VERY_LOW_LIGHT", SceneCategory.VERY_LOW_LIGHT, "Night exterior street with distant ambient illumination", 1.5f, 0.08f, 26f),
        ReferenceSceneDescriptor("SCENE_05_BACKLIGHT", SceneCategory.BACKLIGHT, "Subject silhouette in front of bright sunset window", 1200f, 0.02f, 32f),
        ReferenceSceneDescriptor("SCENE_06_HDR", SceneCategory.HIGH_DYNAMIC_RANGE, "High dynamic range tunnel entrance to bright daylight", 1800f, 0.01f, 34f),
        ReferenceSceneDescriptor("SCENE_07_FINE_TEXTURE", SceneCategory.FINE_TEXTURE, "High-frequency fabric and stone brick texture chart", 800f, 0.01f, 36f),
        ReferenceSceneDescriptor("SCENE_08_TEXT", SceneCategory.TEXT, "Standard resolution ISO 12233 test chart with fine typography", 600f, 0.01f, 38f),
        ReferenceSceneDescriptor("SCENE_09_FOLIAGE", SceneCategory.FOLIAGE, "Complex green leaves, delicate branch structure, micro-contrast", 1100f, 0.03f, 33f),
        ReferenceSceneDescriptor("SCENE_10_SKIN", SceneCategory.SKIN, "Portrait with authentic skin pores and natural tones", 400f, 0.02f, 37f),
        ReferenceSceneDescriptor("SCENE_11_MOVING_SUBJECTS", SceneCategory.MOVING_SUBJECTS, "Fast moving foreground pedestrian crossing frame", 500f, 0.45f, 29f),
        ReferenceSceneDescriptor("SCENE_12_NIGHT_LIGHTS", SceneCategory.NIGHT_LIGHTS, "Urban night scene with sharp point light flares and deep shadows", 8f, 0.04f, 28f),
        ReferenceSceneDescriptor("SCENE_13_REFLECTIVE", SceneCategory.REFLECTIVE_SURFACES, "Polished chrome and glass reflections", 750f, 0.02f, 34f),
        ReferenceSceneDescriptor("SCENE_14_REPEATED_PATTERNS", SceneCategory.REPEATED_PATTERNS, "Moiré-prone architectural grid and fine grating", 900f, 0.01f, 35f)
    )
}

class ReferenceDatasetManager {

    fun getReferenceScenes(): List<ReferenceSceneDescriptor> = ReferenceDatasetCatalog.CONTROLLED_SCENES

    fun getScenesByCategory(category: SceneCategory): List<ReferenceSceneDescriptor> =
        ReferenceDatasetCatalog.CONTROLLED_SCENES.filter { it.category == category }

    fun verifyRegression(measuredMetrics: Map<String, Float>): RegressionVerificationReport {
        val regressions = mutableListOf<String>()
        var passed = 0

        for (scene in ReferenceDatasetCatalog.CONTROLLED_SCENES) {
            val measuredPsnr = measuredMetrics[scene.sceneId] ?: scene.baselinePsnrMin
            if (measuredPsnr < scene.baselinePsnrMin - 0.5f) {
                regressions.add("${scene.sceneId}: PSNR dropped to $measuredPsnr (baseline: ${scene.baselinePsnrMin})")
            } else {
                passed++
            }
        }

        return RegressionVerificationReport(
            totalScenes = ReferenceDatasetCatalog.CONTROLLED_SCENES.size,
            passedScenes = passed,
            regressions = regressions,
            isRegressionFree = regressions.isEmpty()
        )
    }
}
