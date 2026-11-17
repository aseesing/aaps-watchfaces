// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The light around the moon of Aurora Town (pure functions only). One model for both designs: the
 * animated one gives it to the watch as a layer that follows the moon of tonight, the stock one bakes it
 * into its background with the fixed crescent of that picture.
 *
 * The light comes from the lit part of the moon, so it follows how much is lit ("lit", 0 to 1):
 * - it is as strong as [strength] and as big as [scale]: nothing at new moon, the most at full moon,
 * - its middle lies [shift] away from the middle of the disc, toward the lit side,
 * - it never shows inside the disc ([holePng]): the dark part of the moon stays dark.
 */
object AuroraTownMoonGlow {

    /** The colour of the light: a cold white. */
    const val COLOR = "#DCE6FF"

    /** How far the light reaches at full moon, in moon radii. */
    const val REACH = 2.3f

    /** How strong the light is at full moon, 0 to 1. */
    const val FULL_STRENGTH = 0.95

    /** How fast the light grows with the lit part: below 1, a thin crescent still has a little light. */
    const val GROWTH = 0.6

    /** The size of the light at new moon, against its size at full moon. */
    const val NEW_SCALE = 0.7

    /** How far the middle of the light lies from the middle of the disc at new moon, in moon radii (about 0.42 at a quarter). */
    const val LIT_SHIFT = 0.85

    /** The size of the picture of the light, in pixels. */
    const val HALO_PX = 96

    /** The strength of the light when [lit] of the moon is lit. */
    fun strength(lit: Double): Double = FULL_STRENGTH * lit.coerceIn(0.0, 1.0).pow(GROWTH)

    /** The size of the light when [lit] of the moon is lit, against its size at full moon. */
    fun scale(lit: Double): Double = NEW_SCALE + (1 - NEW_SCALE) * lit.coerceIn(0.0, 1.0)

    /** How far the middle of the light lies from the middle of the disc, in moon radii, when [lit] of the moon is lit. */
    fun shift(lit: Double): Double = LIT_SHIFT * (1 - lit.coerceIn(0.0, 1.0))

    /**
     * How bright the light is at [d] (0 in the middle, 1 where it ends), 0 to 1: strong just outside the
     * disc and falling off fast, with a faint wide skirt, like the glare of the moon in cold air.
     */
    fun light(d: Float): Float {
        fun profile(x: Float): Float {
            val k = x * REACH
            val core = (1f / (1f + k * k)).pow(1.5f)
            val skirt = exp(-x * x / (2 * SKIRT * SKIRT))
            return (1 - SKIRT_PART) * core + SKIRT_PART * skirt
        }
        val end = profile(1f)
        return ((profile(d) - end) / (profile(0f) - end)).coerceIn(0f, 1f) * (1 - d * d).coerceIn(0f, 1f)
    }

    /**
     * The light (0 to 1) at [dx], [dy] pixels from the middle of a moon of radius [r] pixels, with [lit] of
     * it lit and the lit side toward [sideX], [sideY] (a unit vector, x right, y down). The same as the
     * watch draws from [haloPng] and [holePng], for a picture with the moon baked in.
     */
    fun lightAt(dx: Float, dy: Float, r: Float, lit: Double, sideX: Float, sideY: Float): Float {
        val shift = (shift(lit) * r).toFloat()
        val reach = (REACH * scale(lit) * r).toFloat()
        val d = sqrt(sq(dx - shift * sideX) + sq(dy - shift * sideY)) / reach
        if (d >= 1f) return 0f
        return strength(lit).toFloat() * light(d) * outsideDisc(sqrt(dx * dx + dy * dy) / r, r)
    }

    /** The light as a picture: [COLOR], with the brightness in its alpha, [HALO_PX] square. */
    fun haloPng(): ByteArray {
        val size = HALO_PX
        val c = Color.parseColor(COLOR)
        return png(size) { x, y ->
            val d = sqrt(sq(x + 0.5f - size / 2f) + sq(y + 0.5f - size / 2f)) / (size / 2f)
            Color.argb((255 * light(d)).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))
        }
    }

    /**
     * Where the light may show, for a square of the size of the light at full moon around the moon:
     * everywhere, except inside the disc (soft over one design pixel). Twice as sharp as [haloPng].
     */
    fun holePng(): ByteArray {
        val size = 2 * HALO_PX
        val pixelsPerRadius = size / 2f / REACH
        return png(size) { x, y ->
            val d = sqrt(sq(x + 0.5f - size / 2f) + sq(y + 0.5f - size / 2f)) / pixelsPerRadius
            Color.argb((255 * outsideDisc(d, pixelsPerRadius)).roundToInt(), 255, 255, 255)
        }
    }

    /** 0 inside the disc, 1 outside, soft over one pixel: [d] in moon radii, [pixelsPerRadius] the pixels of one radius. */
    fun outsideDisc(d: Float, pixelsPerRadius: Float): Float = ((d - 1) * pixelsPerRadius + 0.5f).coerceIn(0f, 1f)

    /** How much of the light spreads to the sides: a faint skirt [SKIRT] of the reach wide. */
    private const val SKIRT_PART = 0.4f
    private const val SKIRT = 0.36f

    private fun sq(v: Float) = v * v

    private fun png(size: Int, pixel: (Int, Int) -> Int): ByteArray {
        val image = createBitmap(size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = pixel(x, y)
        image.setPixels(pixels, 0, size, 0, 0, size, size)
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
