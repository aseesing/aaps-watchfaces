package app.aaps.watchfaces.shorthand

import app.aaps.wear.watchfaces.AapsDir
import org.json.JSONObject
import java.io.File

/**
 * The moon template of the manual of the layer engine (`docs/watchface-animation.html` in AAPS,
 * chapter 13, the example `ex-moon-template`): six plain layers that draw the moon as it is in the sky
 * now. The watch has no moon template of its own, so every design that shows the moon carries this one
 * in its `animation.templates`. Read from the manual, so the designs use what a reader can copy.
 */
object MoonTemplate {

    private val text: String by lazy {
        val page = File(AapsDir.get(), "docs/watchface-animation.html").readText()
        val figure = page.substringAfter("""<figure class="ex" id="ex-moon-template"""")
        val example = figure.substringAfter("<pre>").substringBefore("</pre>").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        JSONObject(example).getJSONObject("animation").getJSONObject("templates").getJSONObject("moon").toString()
    }

    /** A new copy of the template: `{"params": {...}, "layers": [...]}`. */
    fun json(): JSONObject = JSONObject(text)
}
