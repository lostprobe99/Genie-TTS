plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.genie.tts"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.genie.tts"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.0.6"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("debugFixed") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugFixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugFixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("txt", "bin")
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    doFirst {
        val androidJars = classpath.files.filter { it.name == "android.jar" }
        val rest = classpath.files.filter { it.name != "android.jar" }
        classpath = files(rest + androidJars)
    }
}
