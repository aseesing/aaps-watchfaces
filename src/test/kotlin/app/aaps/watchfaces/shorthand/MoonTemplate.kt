package app.aaps.watchfaces.shorthand

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.AapsDir
import app.aaps.wear.watchfaces.animation.DESIGN_SIZE
import app.aaps.wear.watchfaces.animation.LayerAnimator
import app.aaps.wear.watchfaces.animation.LayerSlot
import app.aaps.wear.watchfaces.animation.WakeAnimationJson
import app.aaps.wear.watchfaces.daynight.MoonLight
import app.aaps.wear.watchfaces.daynight.MoonPhoto
import org.json.JSONArray
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

    /** How much the dark part of the moon covers what is behind it: the default of the parameter `cover`. */
    val darkCover: Double get() = json().getJSONObject("params").getJSONObject("cover").getDouble("default")

    /**
     * The moon with [look] from the [photo] of the full moon, in a square of [size] pixels, drawn by the
     * layer engine of the watch with this template: for a design that bakes a moon into a picture.
     */
    fun draw(photo: Bitmap, size: Int, look: MoonLight.Look): Bitmap {
        val animation = JSONObject().put("templates", JSONObject().put("moon", json())).put("layers", JSONArray().put(JSONObject().put("use", "moon")))
        val spec = requireNotNull(WakeAnimationJson({ text, default -> runCatching { Color.parseColor(text) }.getOrDefault(default) }, { null }).read(JSONObject().put("animation", animation))).normalized()
        val animator = LayerAnimator(spec.layers, spec.lets, images = { _, _, side -> MoonPhoto.round(photo, side) })
        check(spec.errors.isEmpty() && animator.errors.isEmpty()) { "The moon template cannot be read: ${spec.errors + animator.errors}" }
        val values = mapOf("moon_lit" to look.litFraction, "moon_limb" to look.limbTurn, "moon_north" to look.northTurn)
        animator.setWakeValues({ values[it] }, 60)
        animator.setTime(0, 6000, 0f, 0)
        val image = createBitmap(size, size)
        val canvas = Canvas(image)
        for (slot in LayerSlot.entries) animator.drawSlot(canvas, slot, size / DESIGN_SIZE)
        animator.release()
        return image
    }
}
