plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNum = (findProperty("buildNum") as String?)?.toIntOrNull() ?: 1
val ghRepo = (findProperty("ghRepo") as String?) ?: ""

android {
    namespace = "com.sharehub.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sharehub.app"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNum
        versionName = "1.$buildNum"
        buildConfigField("String", "GH_REPO", "\"$ghRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // same key on every build, so a new build installs over the old one
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
