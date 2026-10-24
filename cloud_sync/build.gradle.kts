plugins {
    id("com.android.library")
}
android {
    namespace = "org.doublecore.stt.cloud"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { abortOnError = true }
}

dependencies {
    testImplementation(libs.test.junit)
}

// Private provisioning is opt-in and supplied outside the repository. Never use in public CI.
val privateAssets = providers.environmentVariable("STT_PRIVATE_ASSETS_DIR").orNull
if (privateAssets != null) {
    require(System.getenv("GITHUB_ACTIONS") != "true") { "Private credentials must not enter public Actions artifacts" }
    android.sourceSets.getByName("main").assets.srcDir(privateAssets)
}
