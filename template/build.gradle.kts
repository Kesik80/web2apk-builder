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
        versionCode = 11
        versionName = "1.10"
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

dependencies {
    // Document-start scripts: early.js must run before the page's own scripts.
    implementation("androidx.webkit:webkit:1.14.0")
}