plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "chat.client"
    compileSdk = 35
    defaultConfig {
        applicationId = "chat.client"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "0.28.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // адрес релея: gradle-свойство с фолбеком (C3)
        // -PCHAT_SERVER=https://... ; release — прод по умолчанию
        buildConfigField(
            "String",
            "DEFAULT_SERVER",
            "\"${(project.findProperty("CHAT_SERVER") as String?) ?: "https://ednar.duckdns.org"}\"",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time на API<26 — через desugaring
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            // -PCHAT_SERVER=... перебивает и dev-адрес: иначе сборка под прод
            // невозможна, а debug всегда уходил на локальный релей
            buildConfigField("String", "DEFAULT_SERVER", "\"${(project.findProperty("CHAT_SERVER") as String?) ?: "http://192.168.1.43:18080"}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    packaging {
        resources.excludes += "META-INF/AL2.0"
        resources.excludes += "META-INF/LGPL2.1"
    }

    lint {
        // B8: неиспользуемые строки находятся автоматически
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += "GradleDependency"
        enable += "UnusedResources"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("chat.crypto:crypto:0.1.0")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.material3.windowsize)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.core)
    implementation(libs.core.splashscreen)
    implementation(libs.fragment)
    implementation(libs.coroutines.android)
    // Фаза GB: WebRTC-ядро звонков (аудио, P-256 DTLS — внутри, у нас ни строчки криптокода)
    implementation(libs.webrtc)

    // запасная фоновая доставка (Android 14+ ограничивает dataSync FGS)
    implementation(libs.work.runtime)
    // блокировка приложения: биометрия/PIN
    implementation(libs.biometric)

    implementation(libs.okhttp)
    // QR: генерация кода и сканер камеры
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    // GE3: on-device перевод (модель качается через Play services, без ключа)
    implementation(libs.mlkit.translate)

    coreLibraryDesugaring(libs.desugar.jdk)

    // JVM-тесты чистой логики; org.json в хост-рантайме
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // SM-S936B на Android 17: Espresso 3.5 из compose-test ломался
    // на InputManager.getInstance — форсим свежие androidx.test
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
