import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.localchat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.localchat"
        // Android 12+: covers every phone that can realistically run a multi-GB model, and avoids a
        // manifest-merger clash with the LiteRT-LM AAR. Lower it only if your build accepts it.
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // The LiteRT-LM Android runtime ships arm64-v8a native libraries only.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val litertlmVersion = providers.gradleProperty("litertlmVersion").getOrElse("latest.release")

    // Jetpack Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0") // collectAsStateWithLifecycle

    // The LiteRT-LM bytecode needs kotlinx-coroutines >= 1.11.0 (its POM declares less).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // On-device LLM runtime (same engine as the Google AI Edge Gallery app).
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertlmVersion")

    testImplementation("junit:junit:4.13.2")
}
