plugins { id("com.android.application") }
android {
    namespace = "org.doublecore.stt.cloudapp"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.doublecore.stt.cloud"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.2-cloud"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":cloud_sync"))
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("junit:junit:4.13.2")
    // AndroidX test storage calls Kotlin even when the production app is Java-only.
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
}
