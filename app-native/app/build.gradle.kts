plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shieldrj.civic5mt"
    compileSdk = 36

    defaultConfig {
        // The same application ID as the Capacitor build on purpose. Installing this is then
        // an upgrade rather than a second app, so the data directory survives - which is what
        // lets the rescued lifetime record be imported in place rather than side-loaded.
        applicationId = "com.shieldrj.civic5mt"

        // Android 10. The Capacitor build said 24 because that was Capacitor's default, not
        // a requirement. 29 is what lets a foreground service declare its type in the
        // manifest and lets notification channels be unconditional, so it removes real
        // branching rather than just raising a number. This app runs on one modern phone.
        minSdk = 29
        targetSdk = 36

        versionCode = 1
        versionName = "2.0.0-native"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The physics and the protocol client. Everything testable lives there; this module is
    // only the parts that genuinely need Android - a Bluetooth socket, a service, a screen.
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
