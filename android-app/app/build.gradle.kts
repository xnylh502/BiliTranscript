plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.bilitranscript"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.bilitranscript"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "1.2.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // OkHttp for network requests (Bilibili video download)
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  // Apache Commons Compress: tar.bz2 模型整包解压（k2-fsa 官方模型发布格式）
  implementation("org.apache.commons:commons-compress:1.27.1")

  // Kotlinx Serialization for JSON parsing
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

  // Sherpa-ONNX (local speech recognition)
  // Note: Download the AAR from GitHub releases and place in app/libs/
  // https://github.com/k2-fsa/sherpa-onnx/releases
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

  // Room (local SQLite database for history / logs / downloads)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
}
