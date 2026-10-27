import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 每次打包按构建时间自动生成版本号
val buildStamp: String = SimpleDateFormat("yyMMddHHmm", Locale.US).format(Date())

android {
    namespace = "com.yunhu.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yunhu.tv"
        minSdk = 23
        targetSdk = 34
        // versionCode 不能超过 Int 上限，去掉年份十位（yMMddHHmm，9 位数字）
        versionCode = buildStamp.substring(1).toInt()
        versionName = "1.1.${buildStamp}"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 无正式签名证书，release 用 debug 签名，保证 adb 可直接安装
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.6.0")
    // GIF 动图解码（头像/表情）
    implementation("io.coil-kt:coil-gif:2.6.0")
    // 视频播放：ExoPlayer 流式播放，OkHttp 数据源复用带 Referer/证书放行的客户端
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")
    // 老电视系统字体缺 Emoji 字形，内置 Emoji 字体兼容包
    implementation("androidx.emoji2:emoji2-bundled:1.4.0")
    implementation("androidx.emoji2:emoji2-views-helper:1.4.0")
}
