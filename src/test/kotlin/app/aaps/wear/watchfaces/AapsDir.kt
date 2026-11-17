package app.aaps.wear.watchfaces

import java.io.File

/**
 * The folder of the AAPS checkout that this build uses (`aapsDir`, see README.md). The build gives it
 * to the tests as the system property `aaps.dir`.
 */
object AapsDir {

    fun get(): File = File(requireNotNull(System.getProperty("aaps.dir")) { "Run the tests with Gradle: it sets aaps.dir" })
}
