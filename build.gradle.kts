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
    // The snapshot harness (src/test/kotlin/app/aaps/watchfaces/snapshot): its folders, given as -PsnapshotZips=... and so on
    mapOf("snapshotZips" to "snapshot.zips", "snapshotOut" to "snapshot.out", "before" to "snapshot.before", "after" to "snapshot.after", "diffOut" to "snapshot.diffOut")
        .forEach { (property, name) -> providers.gradleProperty(property).orNull?.let { systemProperty(name, file(it).absolutePath) } }
    // The snapshots only run when asked for: -Pdesigns=DesignSnapshot, --tests "*DesignSnapshotTest*" or -PsnapshotOut=...
    val testArgs = gradle.startParameter.taskRequests.flatMap { it.args }
    val snapshotAsked = testArgs.any { "Snapshot" in it } || providers.gradleProperty("designs").orNull.orEmpty().contains("Snapshot") ||
        providers.gradleProperty("snapshotOut").isPresent
    systemProperty("snapshot.run", snapshotAsked.toString())
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Snapshot renders (-PsnapshotOut=...): each design zip in its own JVM, one Test task per zip, so a
// design never draws with a font that another design loaded before it in the same JVM (Robolectric's
// native fonts on macOS). testDebugUnitTest then runs those tasks and renders nothing itself.
if (providers.gradleProperty("snapshotOut").isPresent) {
    val zipDir = file(providers.gradleProperty("snapshotZips").orNull ?: "cwf-designs")
    val zipNames = zipDir.listFiles { f -> f.extension == "zip" }?.map { it.name }?.sorted().orEmpty()
    afterEvaluate {
        val base = tasks.named<Test>("testDebugUnitTest")
        var previous: TaskProvider<Test>? = null
        val perZip = zipNames.mapIndexed { index, zip ->
            val before = previous
            tasks.register<Test>("snapshotDesign$index") {
                description = "Renders the snapshots of $zip in its own JVM"
                val b = base.get()
                classpath = b.classpath
                testClassesDirs = b.testClassesDirs
                javaLauncher.set(b.javaLauncher)
                jvmArgumentProviders.addAll(b.jvmArgumentProviders)
                b.systemProperties.forEach { (key, value) -> if (key !in systemProperties) systemProperty(key, value as Any) }
                systemProperty("snapshot.only", zip)
                filter { includeTestsMatching("*DesignSnapshotTest*") }
                if (before != null) mustRunAfter(before) else doFirst { File(file(providers.gradleProperty("snapshotOut").get()), "errors.txt").delete() }
            }.also { previous = it }
        }
        base.configure {
            dependsOn(perZip)
            systemProperty("snapshot.only", "-")
        }
    }
}
