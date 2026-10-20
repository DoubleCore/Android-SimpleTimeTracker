plugins {
    id("com.android.application")
}

android {
    namespace = "org.doublecore.stt.lanprobe"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.doublecore.stt.lanprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-m0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildTypes {
        release {
            // M0 is an internal diagnostic tool, not a production transport.
            isMinifyEnabled = false
        }
    }
    lint {
        abortOnError = true
    }
}
