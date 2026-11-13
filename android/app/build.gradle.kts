plugins {
    id("com.android.application")
    id("kotlin-android")
    // Flutter 플러그인은 Android·Kotlin 플러그인 다음에 적용
    id("dev.flutter.flutter-gradle-plugin")
    id("com.google.gms.google-services")
}

android {
    namespace = "kr.codiya.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        applicationId = "kr.codiya.app"
        minSdk = 23
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        multiDexEnabled = true
    }

    // 고정 서명 키: GitHub 비밀값 KEYSTORE_PASSWORD 가 있으면 사용
    // (키가 매번 바뀌면 휴대폰에서 '앱이 설치되지 않음'이 나옴)
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()
    signingConfigs {
        create("upload") {
            storeFile = file("upload.keystore")
            storePassword = keystorePassword
            keyAlias = "upload"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystorePassword.isNotEmpty()) {
                signingConfigs.getByName("upload")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging") // 📤 PC 문자 발송 신호 받기
}
