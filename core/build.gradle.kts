import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 이 모듈은 다른 빌드(예: Android 앱 루트)에 하위 모듈로 포함될 수 있다.
// - 저장소는 settings.gradle.kts에서만 선언한다(FAIL_ON_PROJECT_REPOS를 쓰는 빌드와 호환).
// - Kotlin 플러그인 버전은 포함하는 루트가 정한다(AGP 9.4.1은 2.2.10).
// - Android 앱(Java 17)과 맞추기 위해 JVM 17 바이트코드로 낸다. 빌드를 돌리는 JDK는 17 이상이면 된다.
plugins {
    kotlin("jvm")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.test { useJUnitPlatform() }
