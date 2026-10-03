import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起 Compose 编译器独立成插件，开着 compose = true 就必须声明
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// 签名配置
//
// 从工程根目录的 keystore.properties 读（该文件已被 .gitignore 排除，绝不入库）。
// 密钥库本体建议放在工程目录之外，这样即使误操作也不会被打包进仓库。
//
// 文件不存在时**不报错**，只是不出签名版 ——
// 这样别人 clone 下来没有密钥也能正常构建 debug 和未签名 release。
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigningKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.yanfei.r90alarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yanfei.r90alarm"
        // minSdk 26：java.time 原生可用，不需要 coreLibraryDesugaring
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有密钥就签，没有就产出 app-release-unsigned.apk（保持旧行为）
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8.0 起 BuildConfig 默认不再生成。诊断日志要读 BuildConfig.VERSION_NAME，
        // 所以显式打开（也顺带让 applicationId/versionCode 在代码里可用）。
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // 用于 LocalLifecycleOwner + repeatOnLifecycle：
    // App 退到后台/熄屏时停掉秒级心跳，避免躺下后整夜空转耗电
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试：只测 R90Calculator，纯 Kotlin + java.time，跑在 JVM 上
    testImplementation("junit:junit:4.13.2")

    // 刻意的缺席清单：
    //   ✗ Retrofit / OkHttp / Ktor  —— 不联网
    //   ✗ Room / SQLite            —— 不建库
    //   ✗ Hilt / Koin              —— 无依赖注入需求
    //   ✗ WorkManager / AlarmManager —— 闹钟外包给系统时钟
}
