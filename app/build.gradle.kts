plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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
        debug {
            // Installs alongside the Capacitor app rather than replacing it. They share an
            // application ID otherwise, so putting a development build on the phone would
            // uninstall the app that currently works in the car - and this one does not yet
            // do most of what that one does.
            //
            // This costs nothing now only because the lifetime record was already rescued to
            // a file. A separate application ID means a separate data directory, so the
            // native app cannot inherit the WebView storage in place; it imports from that
            // JSON instead, which is the path it was always going to take.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Civic 5MT dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key for now, so a release build can actually be installed
            // and daily-driven before a real keystore exists. Swapping keys later means
            // uninstalling first - which is fine while the records live in SharedPreferences
            // JSON that can be pulled with adb and pushed back.
            signingConfig = signingConfigs.getByName("debug")
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
    sourceSets["test"].java.srcDirs("src/test/kotlin")

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        // Room entities are plain data classes and the CSV mapper never opens a database, so
        // these run on the JVM in a second. Anything needing a real Android framework class
        // belongs in an instrumented test, not here.
        unitTests.isReturnDefaultValues = true
    }

    // Export the schema to a file that gets committed. A migration is then something you can
    // see in a diff rather than something you find out about when a phone fails to open the
    // database it already had.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
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
    // Hosting Compose in a WindowManager window needs a SavedStateRegistryOwner wired onto
    // the view by hand; without it the ComposeView throws as soon as it composes.
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Room, for the trip history. This is what a database is actually for here: many rows,
    // queried by time, kept forever. The two singleton records stay in SharedPreferences.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
