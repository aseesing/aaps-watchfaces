// The design tests. Each one builds a design zip with the real watch face code of AAPS and checks it.
// See README.md.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The same Android and Kotlin Gradle plugins as AAPS
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradlePlugin)
    }
}

plugins {
    id("com.android.library") version libs.versions.gradlePlugin.get()
}

val aapsDir = gradle.extra["aapsDir"] as File

android {
    namespace = "app.aaps.watchfaces"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation("aaps:cwfrender")
    testImplementation("aaps:shared-impl")
    testImplementation("aaps:core-interfaces")
    testImplementation("aaps:core-data")

    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.wear.watchface.complications.data)
    testImplementation(libs.androidx.wear.watchface.complications.rendering)
    testImplementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.junit)
    testImplementation(libs.org.robolectric)
    testImplementation(libs.com.google.truth)
    testImplementation(libs.org.mockito.kotlin)
    testRuntimeOnly(libs.org.junit.vintage.engine)
    testRuntimeOnly(libs.org.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The tests read the fonts and photos and write their output relative to this folder
    workingDir = projectDir
    // The design tests read a picture of AAPS (the moon photo of the watch)
    systemProperty("aaps.dir", aapsDir.path)
    maxHeapSize = "3g"
    // The designs render on the CPU and do not share files: run several test classes at once
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 3).coerceIn(1, 4)
    // The design tests are not cached: they make the zips, so they must really run
    outputs.upToDateWhen { false }
    // Only some designs: ./gradlew testDebugUnitTest -Pdesigns=AuroraTown,D2 (part of the test class name)
    providers.gradleProperty("designs").orNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { names ->
        filter {
            names.forEach { includeTestsMatching("*$it*") }
            isFailOnNoMatchingTests = true
        }
    }
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}
