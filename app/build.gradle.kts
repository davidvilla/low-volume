plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "es.vibecode.lowvolume"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.vibecode.lowvolume"
        minSdk = 28 // DynamicsProcessing
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so it can be installed directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Device tests run against the release build, the one that gets installed.
    testBuildType = "release"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
