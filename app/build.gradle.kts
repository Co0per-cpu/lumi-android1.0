import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.lumi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.lumi.app"
        minSdk = 31          // Android 12+. El S26 FE trae Android 16.
        targetSdk = 36
        // En GitHub cada compilación sube el número, así el teléfono acepta la actualización.
        val numero = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = numero
        versionName = "0.1.$numero"
    }

    // Firma fija para que cada APK nuevo se instale encima del anterior sin desinstalar.
    signingConfigs {
        create("lumi") {
            storeFile = file("lumi-firma.keystore")
            storePassword = "lumi-android"
            keyAlias = "lumi"
            keyPassword = "lumi-android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lumi")
        }
        release {
            signingConfig = signingConfigs.getByName("lumi")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.browser:browser:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // IA en el teléfono (sucesor de MediaPipe LLM Inference). Modelos .litertlm.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    testImplementation("junit:junit:4.13.2")
}
