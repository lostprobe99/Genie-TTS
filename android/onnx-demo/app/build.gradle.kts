plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.genie.onnxdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.genie.onnxdemo"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.14"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("demo") {
            // 固定调试签名。换机器再打包仍能覆盖安装 1.14 及以后的包。
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("demo")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("demo")
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

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
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
