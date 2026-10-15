import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// 发布签名凭据从 keystore.properties 读（放在工程根目录）。
// 文件不存在时 release 仍可构建，只是产出未签名包，方便没有密钥的环境。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// 可选的本地 JDK 17 路径。工程里不写死机器路径——需要在
// ~/.gradle/gradle.properties 里设 gpsshowJdk17Path=<你的路径>。
// 不设时交给 Gradle 自动解析/下载工具链（多数网络环境可用）。
val localJdk17: String? = providers.gradleProperty("gpsshowJdk17Path").orNull
if (localJdk17 != null) {
    // 只影响工具链解析，不改变编译目标
    System.setProperty("org.gradle.java.installations.paths", localJdk17)
}

android {
    namespace = "com.example.gpsshow"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.gpsshow"
        minSdk = 26
        targetSdk = 36
        // versionCode 必须是正整数且单调递增（Android 约束，不能用 0），
        // 与用户可见的 versionName 是两套编号。
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 不混淆：这个应用很小，R8 带来的收益有限，
            // 而混淆会牵动 Compose / org.json 的反射路径，风险大于收益。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // 本地单测里 android.jar 的 org.json 是"not mocked" stub，
  // 必须引入真实实现，否则任何 JSON 序列化测试都会假失败/假通过。
  testImplementation(libs.org.json)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
