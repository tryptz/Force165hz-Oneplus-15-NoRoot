plugins {
    id("com.android.application")
}

android {
    namespace = "tf.arm165"
    compileSdk = 36

    defaultConfig {
        applicationId = "tf.arm165"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.6"
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

dependencies {}
