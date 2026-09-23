import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release 签名配置。
 *
 * 密钥与密码放在工程根目录的 `keystore.properties`（已被 .gitignore 排除，不会进版本库）：
 *
 * ```
 * storeFile=keystore/etf-share-monitor.jks
 * storePassword=***
 * keyAlias=etf-share-monitor
 * keyPassword=***
 * ```
 *
 * 文件不存在时 release 构建退化为「未签名」（assembleRelease 仍可编译通过，
 * 只是产物不能直接安装），这样别人克隆仓库也能正常构建，不会因为缺密钥而失败。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystorePropsFile.exists() &&
    !keystoreProps.getProperty("storePassword").isNullOrBlank()

android {
    namespace = "com.yucl.etfshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yucl.etfshare"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.0"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 与 debug 签名一致的现代方案：v1+v2+v3 全开，兼容 Android 7 ~ 15
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 关闭混淆：本应用体积主要由 8MB 内置数据快照（以 Stored 方式入包）决定，
            // R8 能省下的收益很小，但 WorkManager 的 Worker 是反射实例化的、
            // 一旦被裁掉会导致定时任务静默失效。先用「与已验证的 debug 版行为完全一致」
            // 的配置发版，稳妥优先。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
            )
        }
    }

    androidResources {
        // 初始数据快照已压缩（.gz），且导入时按块流式拷贝：
        // 让 sqlite 资产以「不压缩」方式存入 APK，AssetManager.openFd() 才能生效，
        // 避免把 8MB 数据一次性读进堆内存（低内存机型会 OOM 导致本地无数据）。
        noCompress += listOf("gz", "sqlite", "db")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试：kxml2 提供 JVM 上的 XmlPullParser 实现，便于在本地验证 xlsx 解析
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
