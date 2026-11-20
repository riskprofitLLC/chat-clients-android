plugins {
    id("com.android.library") version "8.10.0"
    kotlin("multiplatform") version "2.1.20"
}

repositories {
    mavenCentral()
    google()
}

// координаты для подключéния приложения через includeBuild (composite build)
group = "chat.crypto"
version = "0.1.0"

android {
    namespace = "chat.crypto"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val jvmMain by getting
        val androidMain by getting
        val jvmTest by getting
        val androidInstrumentedTest by getting

        // один JCA-код для JVM (тесты) и Android (Conscrypt в рантайме)
        androidMain.dependsOn(jvmMain)

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        androidInstrumentedTest.dependencies {
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("androidx.test:runner:1.6.2")
        }
    }
}
