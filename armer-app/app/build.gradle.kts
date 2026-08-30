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
        versionCode = 5
        versionName = "1.2.0"
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
