// The custom watch face designs for AAPS.
//
// This build uses the AAPS source code as an included build (a composite build). The designs are
// made in the tests of this project, with the real watch face code of AAPS (`:wear:cwfrender`).
//
// Where AAPS lies is `aapsDir`: set it in `local.properties` (your own machine) or in
// `gradle.properties` (the default for everybody), or give it with `-PaapsDir=...`.

import java.util.Properties

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

/** The folder of the AAPS checkout: `-PaapsDir`, else `local.properties`, else `gradle.properties`. */
val aapsDir: File = run {
    val local = Properties().apply {
        val file = file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val path = startParameter.projectProperties["aapsDir"]
        ?: local.getProperty("aapsDir")
        ?: providers.gradleProperty("aapsDir").orNull
        ?: error("Set aapsDir (the folder of the AAPS checkout) in local.properties or gradle.properties")
    file(path).canonicalFile.also {
        require(File(it, "wear/cwfrender/build.gradle.kts").exists()) { "aapsDir $it is not an AAPS checkout with wear/cwfrender" }
    }
}
gradle.extra["aapsDir"] = aapsDir

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    // The same library versions as AAPS
    versionCatalogs {
        create("libs") {
            from(files(File(aapsDir, "gradle/libs.versions.toml")))
        }
    }
}

rootProject.name = "aaps-watchfaces"

// The AAPS projects this build uses. Gradle builds only these (and what they need) from AAPS.
// Gradle still configures every project of an included build (configure on demand works only for
// the main build), so AAPS leaves out its `:app` when another build includes it: see its settings.gradle.
includeBuild(aapsDir) {
    dependencySubstitution {
        substitute(module("aaps:cwfrender")).using(project(":wear:cwfrender"))
        substitute(module("aaps:shared-impl")).using(project(":shared:impl"))
        substitute(module("aaps:core-interfaces")).using(project(":core:interfaces"))
        substitute(module("aaps:core-data")).using(project(":core:data"))
    }
}
