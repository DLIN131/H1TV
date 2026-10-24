plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 電視版和平板版共用：抓網頁、解析、收藏／紀錄、App 內更新
android {
    namespace = "com.h1tv.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        disable += setOf("SetJavaScriptEnabled")
    }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2025.06.00"))
    api("androidx.compose.runtime:runtime")
    api("androidx.core:core-ktx:1.16.0")
    api("androidx.compose.foundation:foundation")
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    api("org.jsoup:jsoup:1.18.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("io.coil-kt:coil:2.7.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
