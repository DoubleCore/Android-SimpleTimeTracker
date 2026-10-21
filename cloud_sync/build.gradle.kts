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
