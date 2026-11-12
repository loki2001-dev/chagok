plugins {
    id("com.android.application")
}

android {
    namespace = "io.chagok.app"
    compileSdk = 34
    // AAOS 표준 API (CarUxRestrictionsManager)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "io.chagok.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 기획서 8장: 누적 주행거리(CAR_DRIVE), 연료·SoC(CAR_ENERGY)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // 기획서 7장: 음성 입력. ON_DEVICE 모드만 사용
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    testImplementation("junit:junit:4.13.2")
    // 단위 시험에서 org.json 실제 구현을 쓴다 (Android 스텁 대신)
    testImplementation("org.json:json:20240303")
}
