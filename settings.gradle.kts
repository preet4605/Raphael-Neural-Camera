pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "neural-camera"

include(":app")
include(":camera-core")
include(":capture-intelligence")
include(":neural-runtime")
include(":neural-isp")
include(":quality-engine")
include(":video-engine")
include(":device-profiles")
include(":models")
include(":benchmarks")
include(":gallery")
include(":ui")
include(":data-lab")
