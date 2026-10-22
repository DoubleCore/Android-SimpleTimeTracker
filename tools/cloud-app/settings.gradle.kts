pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "STT Cloud Sync"
include(":app", ":cloud_sync")
project(":cloud_sync").projectDir = file("../../cloud_sync")
