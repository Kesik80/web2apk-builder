plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.web2apk.template"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.web2apk.template"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}