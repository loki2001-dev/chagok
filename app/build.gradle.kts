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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("smoke") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".smoke"
            matchingFallbacks += "debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // 스모크 테스트는 별도 앱 ID로 설치해 실제 사용자의 일지를 보존한다.
    testBuildType = "smoke"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    // 기획서 8장: 누적 주행거리(CAR_DRIVE), 연료·SoC(CAR_ENERGY)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // 기획서 7장: 음성 입력. ON_DEVICE 모드만 사용
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    // SDK feasibility probe only; production does not yet consume navigation data.
    add("smokeImplementation", "ai.pleos.playground:NaviHelper:3.0.5")

    testImplementation("junit:junit:4.13.2")
    // 단위 시험에서 org.json 실제 구현을 쓴다 (Android 스텁 대신)
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
