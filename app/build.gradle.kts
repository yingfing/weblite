plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 版本号自动跟随 git tag（v1.4.2 → versionName 1.4.2，versionCode 10402）
// 本地无 git / CI 环境变量兜底，避免再出现 tag 升了应用版本没升的情况
val releaseTag: String = runCatching {
    val fromGit = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
        .redirectErrorStream(true).start().inputStream.readBytes().toString(Charsets.UTF_8).trim().removePrefix("v")
    if (fromGit.matches(Regex("\\d+\\.\\d+\\.\\d+.*"))) fromGit else null
}.getOrNull()
    ?: System.getenv("GITHUB_REF_NAME")?.removePrefix("v")?.takeIf { it.matches(Regex("\\d+\\.\\d+\\.\\d+.*")) }
    ?: "0.1.0"

val releaseVersionCode: Int = releaseTag.split(".").let { p ->
    (p.getOrNull(0)?.toIntOrNull() ?: 0) * 10000 + (p.getOrNull(1)?.toIntOrNull() ?: 0) * 100 + (p.getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0)
}

android {
    namespace = "com.yingfing.weblite"

    compileSdk = 35

    defaultConfig {
        applicationId = "com.yingfing.weblite"
        minSdk = 21          // 放宽到 Android 5.0+，兼容老设备（如 8H57_H5 TV 盒）
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseTag
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // DIY 项目：release 直接用 debug 签名，保证打出的 APK 可直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}
