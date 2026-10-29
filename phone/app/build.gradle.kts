plugins {
    id("com.android.application")
}

android {
    namespace = "dev.vreal.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.vreal.phone"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
