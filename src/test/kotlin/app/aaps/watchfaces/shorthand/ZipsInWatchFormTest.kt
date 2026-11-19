package app.aaps.watchfaces.shorthand

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Every zip in `cwf-designs` holds its design in the form the watch reads. The watch does not know
 * the short keys of the generators (see [DesignShorthand]), so a zip with one would lose its
 * animation, its looks or its moon without any error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZipsInWatchFormTest {

    private fun jsonOf(zip: File): String = ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.first { it.name == "CustomWatchface.json" }
        input.readBytes().decodeToString()
    }

    @Test
    fun noZipHoldsAShortKeyOfTheGenerators() {
        val zips = File("cwf-designs").listFiles { file -> file.name.endsWith(".zip") }!!.sortedBy { it.name }
        assertThat(zips).isNotEmpty()
        for (zip in zips) assertWithMessage(zip.name).that(DesignShorthand.hasOldKeys(JSONObject(jsonOf(zip)))).isFalse()
    }
}
