plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.jono.todoistwidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jono.todoistwidget"
        minSdk = 31
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // One fixed key, so each new build installs over the previous one.
    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/todoist.jks")
            storePassword = "todoist"
            keyAlias = "todoist"
            keyPassword = "todoist"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
