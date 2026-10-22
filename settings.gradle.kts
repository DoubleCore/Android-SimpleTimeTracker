rootProject.name = "Simple Time Tracker"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(
    "app",
    "domain",
    "domain:common",
    "core",
    "core:common",
    "navigation",
    "data_local",
    "resources",
    "wear",
    "wear_api",
)

file("features").walkTopDown().maxDepth(2).forEach { dir ->
    val isFeatureModule = dir.name.startsWith("feature_")
    val isSubModule = dir.name in listOf("api", "views")

    if (dir.isDirectory && isFeatureModule) {
        include(dir.name)
        project(":${dir.name}").projectDir = dir
    }
    if (dir.isDirectory && isSubModule) {
        include(":${dir.parentFile.name}:${dir.name}")
        project(":${dir.parentFile.name}:${dir.name}").projectDir = dir
    }
}

include(":cloud_sync")
