package app.aaps.watchfaces.shorthand

import app.aaps.wear.watchfaces.animation.Templates
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.design.Looks
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
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
 * animation, its looks or its moon without any error. The watch has no templates either: a zip brings
 * every template it uses ([DesignTemplates]).
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

    @Test
    fun everyZipBringsTheTemplatesItUses() {
        val zips = File("cwf-designs").listFiles { file -> file.name.endsWith(".zip") }!!.sortedBy { it.name }
        for (zip in zips) {
            val design = JSONObject(jsonOf(zip))
            // The design itself, and the design with each look laid over it
            val forms = listOf("the design" to design) + (design.optJSONArray(Looks.JSON_KEY) ?: JSONArray()).let { looks ->
                (0 until looks.length()).mapNotNull { i ->
                    val patch = looks.optJSONObject(i)?.optJSONObject(Looks.PATCH) ?: return@mapNotNull null
                    "look $i" to JSONObject(design.toString()).also { Looks.merge(it, patch) }
                }
            }
            for ((name, form) in forms) {
                val animation = form.optJSONObject(WakeAnimationSpec.JSON_KEY) ?: continue
                val errors = ArrayList<String>()
                Templates(animation.optJSONObject(WakeAnimationJson.TEMPLATES)).expand(animation.optJSONArray(WakeAnimationJson.LAYERS) ?: JSONArray(), errors)
                assertWithMessage("${zip.name}, $name").that(errors).isEmpty()
            }
        }
    }
}
