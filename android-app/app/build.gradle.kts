plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.multisensorlogger"
    compileSdk = 37
    val workspaceDebugKey = rootProject.file(".local/debug.keystore")

    defaultConfig {
        applicationId = "com.example.multisensorlogger"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (workspaceDebugKey.isFile) {
            create("workspaceDebug") {
                storeFile = workspaceDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (workspaceDebugKey.isFile) {
                signingConfig = signingConfigs.getByName("workspaceDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 在 JVM 单元测试中只是空壳，对拍测试读基准 JSON 用真实实现
    testImplementation("org.json:json:20260814")
}
