plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.trae.androidmcp"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.trae.androidmcp"
        minSdk = 26          // Android 8.0：dispatchGesture / 全局动作均可用
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // 保持可直接安装调试；需要上架时再开启混淆与签名配置
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 纯 Java 的轻量 HTTP 服务端，无任何 native 依赖，Termux/手机均可直接运行
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // AndroidX 注解（@RequiresApi 等）
    implementation("androidx.annotation:annotation:1.8.0")
    // Go 实现的 frp 客户端库：Java 绑定（从 gomobile AAR 提取的 classes.jar）
    // 原生库 libgojni.so 放在 src/main/jniLibs/arm64-v8a/ 下
    implementation(files("libs/frpclient-classes.jar"))
}
