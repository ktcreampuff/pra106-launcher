plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shikaketegami.pra106launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shikaketegami.pra106launcher"
        minSdk = 24
        // targetSdkVersionが31以上だと、Android 13以降の「制限付き設定」機能により
        // サイドロードしたアプリのユーザー補助(Accessibility)有効化がブロックされる。
        // 本機のOEM版Settingsアプリには、その制限を解除する「制限付き設定を許可」メニューが
        // 見当たらないため、30未満に下げてこの制限自体を回避する（Play Store配布ではないため問題なし）。
        targetSdk = 30
        versionCode = 12
        versionName = "0.6.6"
    }

    // 署名を固定（CIのたびに鍵が変わると上書きインストールできないため）
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
