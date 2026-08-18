import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The physics. No Android dependencies of any kind live in this module, on purpose:
// these are the models the ported assertions pin, and they have to be runnable in a plain
// JVM test in under a second. The moment this module can see `android.*`, the tests need a
// device or a Robolectric shim and stop being run every time.
plugins {
    kotlin("jvm")
}

kotlin {
    // Built with whatever modern JDK is on the machine (21 here; the Android Studio JBR is
    // 25, whose version string Gradle 8.14 cannot parse), but emitting Java 17 bytecode -
    // that is what the Android Gradle Plugin will accept when the app module starts
    // consuming this library. Compiling to 21 would fail there instead of here, which is a
    // confusing place to discover it.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
