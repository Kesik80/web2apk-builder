plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.web2apk.builder"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.web2apk.builder"
        minSdk = 26
        targetSdk = 37
        versionCode = 17
        versionName = "0.16"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// After assembleDebug, put a copy named web2apk_builder_v<versionName>.apk next to app-debug.apk
// (same folder: app/build/outputs/apk/debug). The original file stays, so Run in Android Studio is unaffected.
val appVersion: String = android.defaultConfig.versionName ?: "0"
val debugApkDir = layout.buildDirectory.dir("outputs/apk/debug")
tasks.configureEach {
    if (name == "assembleDebug") {
        doLast {
            val dir = debugApkDir.get().asFile
            val src = File(dir, "app-debug.apk")
            if (src.exists()) {
                src.copyTo(File(dir, "web2apk_builder_v$appVersion.apk"), overwrite = true)
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.android.tools.build:apksig:8.7.3")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}