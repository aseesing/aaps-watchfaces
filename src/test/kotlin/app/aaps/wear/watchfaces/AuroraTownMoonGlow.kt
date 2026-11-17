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
 * - it lies below the moon: the lit part of the moon covers it, and through the dark part a part of it
 *   shows ([Look.through]), as the glare of the moon in our air lies in front of the dark part too. So
 *   the dark part looks about as bright as the sky right beside the disc.
 *
 * There are three looks to choose from ([LOOKS]); [LOOK] is the one both designs use.
 */
object AuroraTownMoonGlow {

    /**
     * One look of the light, to choose from (see [LOOK]).
     *
     * @param name the name on the sheet that shows the looks side by side
     * @param strength how strong the light is at full moon, 0 to 1
     * @param reach how far the light reaches at full moon, in moon radii
     * @param through how much of the light shows through the disc, 0 to 1. The lit part of the moon
     *   covers it anyway; through the dark part this much shows, so the dark part can be made about as
     *   bright as the sky right beside the disc.
     * @param near how fast the bright glare right beside the disc falls off, as a part of the reach
     *   outside the disc: small gives a bright ring round the moon, large a soft haze
     */
    data class Look(val name: String, val strength: Double, val reach: Float, val through: Float, val near: Float = 0.12f)

    /** A: soft, the whole light shows through the dark part. */
    val SOFT = Look("A soft", strength = 0.6, reach = 2.0f, through = 1f)

    /** B: soft, and the dark part blocks part of the light. */
    val SOFT_PARTLY_BLOCKED = Look("B soft, partly blocked", strength = 0.6, reach = 2.0f, through = 0.85f)

    /** C: very subtle: the moon only looks a little luminous, no ring of light. */
    val VERY_SUBTLE = Look("C very subtle", strength = 0.4, reach = 1.6f, through = 0.8f, near = 0.3f)

    /** The looks to choose from, in the order of the sheet. */
    val LOOKS = listOf(SOFT, SOFT_PARTLY_BLOCKED, VERY_SUBTLE)

    /** The look of both designs: the animated one and the stock bake. Change this one line to choose another. */
    val LOOK = VERY_SUBTLE

    /** The colour of the light: a cold white. */
    const val COLOR = "#DCE6FF"

    /** How fast the light grows with the lit part: below 1, a thin crescent still has a little light. */
    const val GROWTH = 0.3

    /** The size of the light at new moon, against its size at full moon. */
    const val NEW_SCALE = 0.7

    /** How far the middle of the light lies from the middle of the disc at new moon, in moon radii (about 0.15 at a quarter). */
    const val LIT_SHIFT = 0.3

    /** The size of the picture of the light, in pixels. */
    const val HALO_PX = 96

    /** The strength of the light when [lit] of the moon is lit. */
    fun strength(lit: Double, look: Look = LOOK): Double = look.strength * lit.coerceIn(0.0, 1.0).pow(GROWTH)

    /** The size of the light when [lit] of the moon is lit, against its size at full moon. */
    fun scale(lit: Double): Double = NEW_SCALE + (1 - NEW_SCALE) * lit.coerceIn(0.0, 1.0)

    /** How far the middle of the light lies from the middle of the disc, in moon radii, when [lit] of the moon is lit. */
    fun shift(lit: Double): Double = LIT_SHIFT * (1 - lit.coerceIn(0.0, 1.0))

    /**
     * How bright the light is at [d] (0 in the middle, 1 where it ends), 0 to 1: strong just outside the
     * disc and falling off fast, with a faint wide skirt, like the glare of the moon in cold air.
     */
    fun light(d: Float, look: Look = LOOK): Float {
        // Measured from the edge of the disc, not from its middle: the lit part of the disc covers the
        // light, so the bright part must lie just outside it, or almost nothing of the light shows
        val edge = 1f / look.reach
        val x = ((d - edge) / (1 - edge)).coerceAtLeast(0f)
        val near = exp(-x / look.near)
        val skirt = exp(-x * x / (2 * SKIRT * SKIRT))
        return ((1 - SKIRT_PART) * near + SKIRT_PART * skirt) * (1 - x * x).coerceIn(0f, 1f)
    }

    /**
     * The light (0 to 1) at [dx], [dy] pixels from the middle of a moon of radius [r] pixels, with [lit] of
     * it lit and the lit side toward [sideX], [sideY] (a unit vector, x right, y down). The same as the
     * watch draws from [haloPng] and [holePng], for a picture with the moon baked in: draw the moon over it.
     */
    fun lightAt(dx: Float, dy: Float, r: Float, lit: Double, sideX: Float, sideY: Float, look: Look = LOOK): Float {
        val shift = (shift(lit) * r).toFloat()
        val reach = (look.reach * scale(lit) * r).toFloat()
        val d = sqrt(sq(dx - shift * sideX) + sq(dy - shift * sideY)) / reach
        if (d >= 1f) return 0f
        return strength(lit, look).toFloat() * light(d, look) * shows(sqrt(dx * dx + dy * dy) / r, r, look)
    }

    /** The light as a picture: [COLOR], with the brightness in its alpha, [HALO_PX] square. */
    fun haloPng(look: Look = LOOK): ByteArray {
        val size = HALO_PX
        val c = Color.parseColor(COLOR)
        return png(size) { x, y ->
            val d = sqrt(sq(x + 0.5f - size / 2f) + sq(y + 0.5f - size / 2f)) / (size / 2f)
            Color.argb((255 * light(d, look)).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))
        }
    }

    /** True when the light of [look] needs the mask [holePng]: when not all of it shows through the disc. */
    fun hasHole(look: Look = LOOK): Boolean = look.through < 1f

    /**
     * Where the light may show, for a square of the size of the light at full moon around the moon: all
     * of it outside the disc, [Look.through] of it inside (soft over one design pixel). Twice as sharp as
     * [haloPng].
     */
    fun holePng(look: Look = LOOK): ByteArray {
        val size = 2 * HALO_PX
        val pixelsPerRadius = size / 2f / look.reach
        return png(size) { x, y ->
            val d = sqrt(sq(x + 0.5f - size / 2f) + sq(y + 0.5f - size / 2f)) / pixelsPerRadius
            Color.argb((255 * shows(d, pixelsPerRadius, look)).roundToInt(), 255, 255, 255)
        }
    }

    /**
     * How much of the light shows at [d] moon radii from the middle of the disc: [Look.through] inside,
     * all of it outside, soft over one pixel ([pixelsPerRadius] the pixels of one radius).
     */
    private fun shows(d: Float, pixelsPerRadius: Float, look: Look): Float {
        val outside = ((d - 1) * pixelsPerRadius + 0.5f).coerceIn(0f, 1f)
        return look.through + (1 - look.through) * outside
    }

    /** How much of the light spreads to the sides: a faint skirt [SKIRT] of the reach wide. */
    private const val SKIRT_PART = 0.4f

    private const val SKIRT = 0.45f

    private fun sq(v: Float) = v * v

    private fun png(size: Int, pixel: (Int, Int) -> Int): ByteArray {
        val image = createBitmap(size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = pixel(x, y)
        image.setPixels(pixels, 0, size, 0, 0, size, size)
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
