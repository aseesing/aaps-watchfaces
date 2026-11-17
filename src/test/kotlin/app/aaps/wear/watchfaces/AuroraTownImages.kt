// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import app.aaps.wear.watchfaces.daynight.MoonLight
import app.aaps.wear.watchfaces.daynight.MoonPicture
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pictures of the Christmas face "Aurora Town": a snowy village at night under the northern lights.
 *
 * They are made from two AI made photos that are not part of the app. They lie in `xmas-bg/` at the root
 * of the repository: `02-master.jpg` (the village, a round photo of 1024 x 1024 on a white surround,
 * lights off, no aurora, no moon) and `03-aurora.jpg` (only northern lights, on black). The unit tests
 * run in the folder of this project, so the default [sourceDir] is `xmas-bg`. The moon is the photo of
 * the full moon that the watch app draws itself ([MoonPicture]).
 *
 * The village is mirrored left to right (the church tower stands on the left) and fills the round
 * face. All pictures are 2 x the design size ([SCALE]): the face is 800 x 800 for a design of 400 x 400.
 * All places in the constants are in design coordinates (400 x 400), after the mirror.
 *
 * Two zips use these pictures:
 * - the animated one: [animatedBackground] (village and clean clock only) with the aurora ([aurora],
 *   [auroraMirrored]) screened through the [skyMask], blinking lights at [EAVE_LIGHTS] and [TREE_LIGHTS]
 *   and falling snow ([crystalSmall], [crystalBig]) as animation layers.
 * - the stock one for plain AAPS: [stockBackground] has everything baked in, and [secondHandSnow] turns
 *   once a minute as the second hand.
 *
 * Everything is deterministic: the same sources give the same bytes. The pictures are made once per
 * instance and kept.
 *
 * @param sourceDir the folder with `02-master.jpg` and `03-aurora.jpg`.
 * @param moonPhotoFile the photo of the full moon that the app uses.
 */
class AuroraTownImages(
    private val sourceDir: File = File("xmas-bg"),
    private val moonPhotoFile: File = File(AapsDir.get(), "wear/src/main/res/drawable-nodpi/personal_moon_full.jpg")
) {

    /** Red, green and blue planes, 0 to 255, of a picture of [w] x [h]. */
    private class Rgb(val w: Int, val h: Int) {

        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)

        fun copy(): Rgb = Rgb(w, h).also {
            r.copyInto(it.r)
            g.copyInto(it.g)
            b.copyInto(it.b)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Public pictures
    // ---------------------------------------------------------------------------------------------

    /** The village with the clean clock: lights off, no aurora, no moon. JPG, 800 x 800. */
    fun animatedBackground(): ByteArray = animatedJpg.first

    /** The village with the aurora, the moon and all lights on, for plain AAPS. JPG, 800 x 800. */
    fun stockBackground(): ByteArray = stockJpg.first

    /** Where the sky is: white, alpha 255 in the sky, 0 elsewhere, with a soft edge. PNG, 800 x 800. */
    fun skyMask(): ByteArray = maskPng

    /** The northern lights, toned down for a screen blend over the sky; black means no effect. JPG, 800 x 800. */
    fun aurora(): ByteArray = auroraJpg.first

    /** [aurora] mirrored left to right: the second curtain, moving the other way. JPG, 800 x 800. */
    fun auroraMirrored(): ByteArray = auroraMirroredJpg.first

    /** A small snow crystal: six arms with side branches, white with a soft glow. PNG, [CRYSTAL_SMALL_PX] square. */
    fun crystalSmall(): ByteArray = crystalSmallPng

    /** A big snow crystal, like [crystalSmall]. PNG, [CRYSTAL_BIG_PX] square. */
    fun crystalBig(): ByteArray = crystalBigPng

    /** Snow over the whole face, for the second hand of the stock zip (it turns about the centre). PNG, 800 x 800. */
    fun secondHandSnow(): ByteArray = snowPng

    /** The hour hand of the tower clock, pointing to 12; the centre of the square is the pivot. PNG, [HAND_PX] square. */
    fun hourHand(): ByteArray = hourHandPng

    /** The minute hand of the tower clock, like [hourHand]. PNG, [HAND_PX] square. */
    fun minuteHand(): ByteArray = minuteHandPng

    /** The JPG quality that was used for each JPG picture, to report it. */
    fun jpgQualities(): Map<String, Int> = mapOf(
        "animated background" to animatedJpg.second, "stock background" to stockJpg.second,
        "aurora" to auroraJpg.second, "aurora mirrored" to auroraMirroredJpg.second
    )

    // ---------------------------------------------------------------------------------------------
    // Debug pictures (for the test, to look at)
    // ---------------------------------------------------------------------------------------------

    /** The stock background with the sky mask in red over it and the horizon line in yellow. PNG. */
    fun debugMask(): ByteArray {
        val image = stock.copy()
        val n = SIZE * SIZE
        for (i in 0 until n) {
            val a = sky[i] * 0.45f
            image.r[i] = image.r[i] * (1 - a) + 255 * a
            image.g[i] = image.g[i] * (1 - a)
            image.b[i] = image.b[i] * (1 - a)
            // The edge: where the mask is between full and empty
            if (sky[i] in 0.05f..0.95f) {
                image.r[i] = 255f
                image.g[i] = 255f
                image.b[i] = 0f
            }
        }
        return png(toBitmap(image))
    }

    /** The animated background with the light points as dots and the clock circle. PNG. */
    fun debugLights(): ByteArray {
        val bitmap = toBitmap(withClock)
        val canvas = Canvas(bitmap)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = Color.CYAN
        for ((x, y) in EAVE_LIGHTS) canvas.drawCircle(x * SCALE, y * SCALE, 1.6f, dot)
        dot.color = Color.MAGENTA
        for ((x, y) in TREE_LIGHTS) canvas.drawCircle(x * SCALE, y * SCALE, 1.8f, dot)
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        line.style = Paint.Style.STROKE
        line.strokeWidth = 1f
        line.color = Color.GREEN
        canvas.drawCircle(CLOCK_X * SCALE, CLOCK_Y * SCALE, CLOCK_RADIUS * SCALE, line)
        // The tree outline the tree lights must stay in
        line.color = Color.YELLOW
        val outline = Path()
        for (k in TREE_OUTLINE.indices) {
            val (y, cx, half) = TREE_OUTLINE[k]
            val px = (cx - half) * OUT_PER_SOURCE
            if (k == 0) outline.moveTo(px, y * OUT_PER_SOURCE) else outline.lineTo(px, y * OUT_PER_SOURCE)
        }
        for (k in TREE_OUTLINE.indices.reversed()) {
            val (y, cx, half) = TREE_OUTLINE[k]
            outline.lineTo((cx + half) * OUT_PER_SOURCE, y * OUT_PER_SOURCE)
        }
        canvas.drawPath(outline, line)
        // The text area of the design
        line.color = Color.WHITE
        canvas.drawRect(80 * SCALE, 70 * SCALE, 320 * SCALE, 250 * SCALE, line)
        return png(bitmap)
    }

    // ---------------------------------------------------------------------------------------------
    // The steps, each made once
    // ---------------------------------------------------------------------------------------------

    /** The master photo, mirrored left to right, 1024 x 1024. */
    private val master: Rgb by lazy { mirrored(load(File(sourceDir, "02-master.jpg"))) }

    /** The round photo area scaled to 800 x 800; the white surround is replaced by the edge of the photo. */
    private val photo: Rgb by lazy { roundPhoto(master) }

    /** [photo] with the clean clock faces. */
    private val withClock: Rgb by lazy { toRgb(paintClocks(toBitmap(photo))) }

    /** The sky line in [master]: for each column, the first row that is not sky. */
    private val horizon: FloatArray by lazy { findHorizon(master) }

    /** The sky mask, 0 to 1, 800 x 800. */
    private val sky: FloatArray by lazy { skyMaskPlane(horizon) }

    /** The toned aurora, 800 x 800. */
    private val auroraRgb: Rgb by lazy { tonedAurora(load(File(sourceDir, "03-aurora.jpg"))) }

    private val stock: Rgb by lazy { stockPicture() }

    private val animatedJpg: Pair<ByteArray, Int> by lazy { jpgWithin(toBitmap(withClock), BACKGROUND_BUDGET) }
    private val stockJpg: Pair<ByteArray, Int> by lazy { jpgWithin(toBitmap(stock), BACKGROUND_BUDGET) }
    private val maskPng: ByteArray by lazy { png(maskBitmap()) }
    private val auroraJpg: Pair<ByteArray, Int> by lazy { jpgWithin(toBitmap(auroraRgb), AURORA_BUDGET) }
    private val auroraMirroredJpg: Pair<ByteArray, Int> by lazy { jpgWithin(toBitmap(mirrored(auroraRgb)), AURORA_BUDGET) }
    private val crystalSmallPng: ByteArray by lazy { png(crystalSprite(CRYSTAL_SMALL_PX)) }
    private val crystalBigPng: ByteArray by lazy { png(crystalSprite(CRYSTAL_BIG_PX)) }
    private val snowPng: ByteArray by lazy { png(snowField()) }
    private val hourHandPng: ByteArray by lazy { png(hand(HOUR_LENGTH, HOUR_WIDTH, HOUR_TAIL)) }
    private val minuteHandPng: ByteArray by lazy { png(hand(MINUTE_LENGTH, MINUTE_WIDTH, MINUTE_TAIL)) }

    // ---------------------------------------------------------------------------------------------
    // Photo
    // ---------------------------------------------------------------------------------------------

    private fun load(file: File): Rgb {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(file.readBytes(), 0, file.length().toInt())) { "Cannot read $file" }
        return toRgb(bitmap)
    }

    private fun mirrored(source: Rgb): Rgb {
        val out = Rgb(source.w, source.h)
        for (y in 0 until source.h) for (x in 0 until source.w) {
            val from = y * source.w + (source.w - 1 - x)
            val to = y * source.w + x
            out.r[to] = source.r[from]
            out.g[to] = source.g[from]
            out.b[to] = source.b[from]
        }
        return out
    }

    /**
     * The circle of the full frame of [source] scaled to [SIZE], 2 x 2 samples per pixel. Points
     * beyond [PHOTO_RIM] source pixels from the centre take the colour at the rim, so the white
     * surround never shows at the edge of the screen.
     */
    private fun roundPhoto(source: Rgb): Rgb {
        val out = Rgb(SIZE, SIZE)
        val c = source.w / 2f
        val sample = FloatArray(3)
        for (j in 0 until SIZE) for (i in 0 until SIZE) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (sy in 0..1) for (sx in 0..1) {
                var u = (i + 0.25f + 0.5f * sx) / OUT_PER_SOURCE
                var v = (j + 0.25f + 0.5f * sy) / OUT_PER_SOURCE
                val d = hypot(u - c, v - c)
                if (d > PHOTO_RIM) {
                    u = c + (u - c) * PHOTO_RIM / d
                    v = c + (v - c) * PHOTO_RIM / d
                }
                bilinear(source, u, v, sample)
                r += sample[0]
                g += sample[1]
                b += sample[2]
            }
            val k = j * SIZE + i
            // Black beyond the round screen: nobody sees it, and it makes the jpg smaller
            val inside = edgeFade(i, j)
            out.r[k] = r / 4 * inside
            out.g[k] = g / 4 * inside
            out.b[k] = b / 4 * inside
        }
        return out
    }

    /** 1 on the round screen of 800 x 800, 0 just beyond it. */
    private fun edgeFade(i: Int, j: Int): Float = smooth((SIZE / 2f + 3 - hypot(i + 0.5f - SIZE / 2f, j + 0.5f - SIZE / 2f)) / 2)

    /** The colour at ([u], [v]); pixel i has its centre at i + 0.5. */
    private fun bilinear(source: Rgb, u: Float, v: Float, out: FloatArray) {
        val x = (u - 0.5f).coerceIn(0f, source.w - 1.001f)
        val y = (v - 0.5f).coerceIn(0f, source.h - 1.001f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val k00 = y0 * source.w + x0
        val k10 = k00 + 1
        val k01 = k00 + source.w
        val k11 = k01 + 1
        fun mix(p: FloatArray) = (p[k00] * (1 - fx) + p[k10] * fx) * (1 - fy) + (p[k01] * (1 - fx) + p[k11] * fx) * fy
        out[0] = mix(source.r)
        out[1] = mix(source.g)
        out[2] = mix(source.b)
    }

    // ---------------------------------------------------------------------------------------------
    // Clock
    // ---------------------------------------------------------------------------------------------

    /**
     * Paints a clean, lit clock face with 12 hour ticks and no hands over the tower clock, and cleans
     * the small clock on the side of the tower the same way.
     */
    private fun paintClocks(bitmap: Bitmap): Bitmap {
        val canvas = Canvas(bitmap)
        val cx = CLOCK_X * SCALE
        val cy = CLOCK_Y * SCALE
        val radius = CLOCK_RADIUS * SCALE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // A soft warm glow just around the face, like the light that falls on the stones
        paint.shader = RadialGradient(
            cx, cy, radius * 1.45f,
            intArrayOf(Color.argb(150, 255, 214, 150), Color.argb(70, 255, 200, 130), Color.argb(0, 255, 190, 120)),
            floatArrayOf(0.62f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.45f, paint)
        // The dark iron ring
        paint.shader = null
        paint.color = Color.rgb(74, 60, 48)
        canvas.drawCircle(cx, cy, radius, paint)
        // The face: lit from behind, brightest in the middle
        paint.shader = RadialGradient(
            cx - radius * 0.12f, cy - radius * 0.12f, radius * 0.95f,
            intArrayOf(Color.rgb(255, 249, 230), Color.rgb(252, 238, 205), Color.rgb(236, 212, 168)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 0.9f, paint)
        paint.shader = null
        // A thin line on the face, inside the ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.6f
        paint.color = Color.argb(110, 120, 96, 70)
        canvas.drawCircle(cx, cy, radius * 0.84f, paint)
        // 12 hour ticks; 12, 3, 6 and 9 a little stronger
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(66, 54, 44)
        for (hour in 0 until 12) {
            val angle = hour * PI / 6
            val main = hour % 3 == 0
            paint.strokeWidth = if (main) 1.5f else 0.95f
            val inner = radius * (if (main) 0.58f else 0.64f)
            val outer = radius * 0.78f
            val dx = sin(angle).toFloat()
            val dy = -cos(angle).toFloat()
            canvas.drawLine(cx + dx * inner, cy + dy * inner, cx + dx * outer, cy + dy * outer, paint)
        }
        // The small clock on the side of the tower, seen from the side: an upright oval
        val side = RectF(SIDE_CLOCK_X - SIDE_CLOCK_RX, SIDE_CLOCK_Y - SIDE_CLOCK_RY, SIDE_CLOCK_X + SIDE_CLOCK_RX, SIDE_CLOCK_Y + SIDE_CLOCK_RY)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(84, 68, 54)
        canvas.drawOval(RectF(side).apply { inset(-0.8f, -0.8f) }, paint)
        paint.shader = LinearGradient(
            side.left, 0f, side.right, 0f, intArrayOf(Color.rgb(250, 238, 210), Color.rgb(246, 228, 192), Color.rgb(222, 196, 152)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawOval(side, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.7f
        paint.color = Color.argb(170, 70, 58, 46)
        for (hour in 0 until 12 step 3) {
            val angle = hour * PI / 6
            val dx = sin(angle).toFloat()
            val dy = -cos(angle).toFloat()
            canvas.drawLine(
                SIDE_CLOCK_X + dx * SIDE_CLOCK_RX * 0.62f, SIDE_CLOCK_Y + dy * SIDE_CLOCK_RY * 0.62f,
                SIDE_CLOCK_X + dx * SIDE_CLOCK_RX * 0.8f, SIDE_CLOCK_Y + dy * SIDE_CLOCK_RY * 0.8f, paint
            )
        }
        // A little grain over both faces, like the rest of the photo
        val random = Random(SEED_GRAIN)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val box = RectF(cx - radius, cy - radius, side.right + 1, cy + radius)
        for (y in floor(box.top).toInt()..ceil(box.bottom).toInt()) for (x in floor(box.left).toInt()..ceil(box.right).toInt()) {
            val inFace = hypot(x + 0.5f - cx, y + 0.5f - cy) <= radius
            val sx = (x + 0.5f - SIDE_CLOCK_X) / (SIDE_CLOCK_RX + 1)
            val sy = (y + 0.5f - SIDE_CLOCK_Y) / (SIDE_CLOCK_RY + 1)
            if (!inFace && sx * sx + sy * sy > 1) continue
            val k = y * bitmap.width + x
            val n = (random.nextGaussian() * 1.6).toFloat()
            val p = pixels[k]
            pixels[k] = Color.rgb(clamp(Color.red(p) + n), clamp(Color.green(p) + n), clamp(Color.blue(p) + n))
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return bitmap
    }

    /**
     * A clock hand pointing up, in a square of [HAND_PX] whose centre is the pivot: dark iron with a
     * small soft shadow. [length], [width] and [tail] are parts of the clock radius.
     */
    private fun hand(length: Float, width: Float, tail: Float): Bitmap {
        val big = HAND_PX * SUPER
        val c = big / 2f
        val unit = CLOCK_RADIUS * SCALE * SUPER
        val shape = Path().apply {
            val w = width * unit / 2
            val tip = c - length * unit
            moveTo(c, tip)
            // A slim spear: wide near the pivot, a small bulge before the tip
            lineTo(c + w * 0.55f, tip + length * unit * 0.22f)
            lineTo(c + w * 0.35f, tip + length * unit * 0.32f)
            lineTo(c + w, c - w * 0.6f)
            lineTo(c + w * 0.7f, c + tail * unit)
            lineTo(c - w * 0.7f, c + tail * unit)
            lineTo(c - w, c - w * 0.6f)
            lineTo(c - w * 0.35f, tip + length * unit * 0.32f)
            lineTo(c - w * 0.55f, tip + length * unit * 0.22f)
            close()
            addCircle(c, c, width * unit * 0.75f, Path.Direction.CW)
        }
        // The shadow: the same shape, moved a little down and right, blurred
        val shadow = createBitmap(big, big)
        val shadowCanvas = Canvas(shadow)
        shadowCanvas.translate(0.6f * SUPER, 0.9f * SUPER)
        shadowCanvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        val shadowAlpha = blur(alphaPlane(shadow), big, big, (0.7f * SUPER).roundToInt())
        val image = createBitmap(big, big)
        val pixels = IntArray(big * big) { Color.argb((shadowAlpha[it] * 0.45f).roundToInt().coerceIn(0, 255), 10, 8, 6) }
        image.setPixels(pixels, 0, big, 0, 0, big, big)
        val canvas = Canvas(image)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(c - width * unit / 2, 0f, c + width * unit / 2, 0f, Color.rgb(62, 56, 52), Color.rgb(24, 21, 19), Shader.TileMode.CLAMP)
        canvas.drawPath(shape, paint)
        return downsample(image, SUPER)
    }

    // ---------------------------------------------------------------------------------------------
    // Sky
    // ---------------------------------------------------------------------------------------------

    /**
     * For each column of [source] the first row (from the top) that is not sky.
     *
     * The sky is a smooth, dark gradient without texture. Some mountain rock has nearly the colour of
     * the sky, but it has texture. So, going down a column, the sky ends at the first row where
     * - the texture (the mean difference to the mean in a 3 x 3 square) is above [SKY_TEXTURE] for
     *   3 rows, or
     * - the brightness differs from the sky just above by more than [SKY_STEP] for 2 rows.
     * A 3 x 3 median first removes most faint stars. Then two clean-ups along the line: a stop of at
     * most 4 columns far above both sides is a star (removed), and a dip of at most 5 columns far below
     * both sides is a gap in the rock (closed).
     */
    private fun findHorizon(source: Rgb): FloatArray {
        val w = source.w
        val h = source.h
        val luma = FloatArray(w * h) { 0.3f * source.r[it] + 0.55f * source.g[it] + 0.15f * source.b[it] }
        val median = luma.copyOf()
        val window = FloatArray(9)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var k = 0
            for (dy in -1..1) for (dx in -1..1) window[k++] = luma[(y + dy) * w + x + dx]
            window.sort()
            median[y * w + x] = window[4]
        }
        val texture = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var mean = 0f
            for (dy in -1..1) for (dx in -1..1) mean += median[(y + dy) * w + x + dx]
            mean /= 9
            var spread = 0f
            for (dy in -1..1) for (dx in -1..1) spread += abs(median[(y + dy) * w + x + dx] - mean)
            texture[y * w + x] = spread / 9
        }
        val c = w / 2f
        val horizon = FloatArray(w)
        for (x in 0 until w) {
            val dx = x + 0.5f - c
            val half = sqrt(max(0f, PHOTO_RIM * PHOTO_RIM - dx * dx))
            if (half < 20f) continue
            val top = ceil(c - half).toInt() + 1
            val bottom = floor(c + half).toInt() - 1
            horizon[x] = bottom.toFloat()
            for (y in top + 10 until bottom - 3) {
                val textured = (0..2).all { texture[(y + it) * w + x] > SKY_TEXTURE }
                var sky = 0f
                for (k in y - 10 until y - 3) sky += median[k * w + x]
                sky /= 7
                val step = abs(median[y * w + x] - sky) > SKY_STEP && abs(median[(y + 1) * w + x] - sky) > SKY_STEP
                if (textured || step) {
                    horizon[x] = y.toFloat()
                    break
                }
            }
        }
        // Stars: a few columns that stop far above both sides
        for (width in 1..4) for (x in 1 until w - width) {
            val left = horizon[x - 1]
            val right = horizon[x + width]
            if (left <= 0f || right <= 0f) continue
            val edge = min(left, right)
            if ((x until x + width).all { horizon[it] > 0f && horizon[it] < edge - SKY_SPIKE }) for (k in x until x + width) horizon[k] = edge
        }
        // Narrow dips into the rock: a grey opening (the lowest of the neighbours, then the highest of those)
        val lowest = FloatArray(w) { x -> if (horizon[x] <= 0f) 0f else (max(0, x - SKY_DIP)..min(w - 1, x + SKY_DIP)).filter { horizon[it] > 0f }.minOf { horizon[it] } }
        return FloatArray(w) { x ->
            if (horizon[x] <= 0f) 0f
            else min(horizon[x], (max(0, x - SKY_DIP)..min(w - 1, x + SKY_DIP)).filter { lowest[it] > 0f }.maxOf { lowest[it] })
        }
    }

    /** The sky mask at 800 x 800 from the [horizon] of the master, with a soft edge of about one pixel. */
    private fun skyMaskPlane(horizon: FloatArray): FloatArray {
        val plane = FloatArray(SIZE * SIZE)
        val c = master.w / 2f
        // Four samples per pixel give the soft edge
        for (j in 0 until SIZE) for (i in 0 until SIZE) {
            var sum = 0f
            for (sy in 0..1) for (sx in 0..1) {
                var u = (i + 0.25f + 0.5f * sx) / OUT_PER_SOURCE
                var v = (j + 0.25f + 0.5f * sy) / OUT_PER_SOURCE
                val d = hypot(u - c, v - c)
                if (d > PHOTO_RIM) {
                    u = c + (u - c) * PHOTO_RIM / d
                    v = c + (v - c) * PHOTO_RIM / d
                }
                val x = (u - 0.5f).coerceIn(0f, master.w - 1.001f)
                val x0 = x.toInt()
                val line = min(horizon[x0], horizon[x0 + 1]) - SKY_MARGIN
                sum += ((line - v) / SKY_SOFT + 0.5f).coerceIn(0f, 1f)
            }
            plane[j * SIZE + i] = sum / 4
        }
        return plane
    }

    private fun maskBitmap(): Bitmap {
        val pixels = IntArray(SIZE * SIZE) {
            val a = (sky[it] * 255).roundToInt()
            if (a == 0) Color.TRANSPARENT else Color.argb(a, 255, 255, 255)
        }
        return createBitmap(SIZE, SIZE).apply { setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE) }
    }

    // ---------------------------------------------------------------------------------------------
    // Aurora
    // ---------------------------------------------------------------------------------------------

    /**
     * The aurora photo scaled to the face and toned down: less bright, less saturated, the highlights
     * pressed, a little shifted to the teal of the sky. Screened over the sky it looks like a faint
     * real aurora, not neon.
     */
    private fun tonedAurora(source: Rgb): Rgb {
        val out = Rgb(SIZE, SIZE)
        val sample = FloatArray(3)
        for (j in 0 until SIZE) for (i in 0 until SIZE) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (sy in 0..1) for (sx in 0..1) {
                val u = (i + 0.25f + 0.5f * sx) * source.w / SIZE
                val v = (j + 0.25f + 0.5f * sy) * source.h / SIZE / AURORA_STRETCH + AURORA_SHIFT
                bilinear(source, u, v, sample)
                r += sample[0]
                g += sample[1]
                b += sample[2]
            }
            r /= 4
            g /= 4
            b /= 4
            val grey = 0.3f * r + 0.55f * g + 0.15f * b
            // Less saturated
            r = grey + (r - grey) * AURORA_SATURATION
            g = grey + (g - grey) * AURORA_SATURATION
            b = grey + (b - grey) * AURORA_SATURATION
            // Highlights pressed: a curve that keeps the faint parts and caps the bright ones
            val k = j * SIZE + i
            val y = j.toFloat() / SIZE
            // Fades out at the top edge of the face, and before the bottom of the photo (no repeated last row)
            val sourceRow = (j + 0.5f) * source.h / SIZE / AURORA_STRETCH + AURORA_SHIFT
            val fade = smooth((y - 0.02f) / 0.12f) * smooth((source.h - 30 - sourceRow) / 160f)
            out.r[k] = tone(r * 0.85f) * fade
            out.g[k] = tone(g) * fade
            out.b[k] = tone(b * 1.05f + g * 0.08f) * fade
        }
        return out
    }

    private fun tone(value: Float): Float {
        val x = (value / 255f).coerceIn(0f, 1f)
        return 255f * AURORA_GAIN * (1 - exp(-2.2f * x)) / (1 - exp(-2.2f)) * x.pow(0.35f)
    }

    // ---------------------------------------------------------------------------------------------
    // Stock picture
    // ---------------------------------------------------------------------------------------------

    private fun stockPicture(): Rgb {
        val image = withClock.copy()
        val n = SIZE * SIZE
        // The aurora, screened through the sky mask
        for (i in 0 until n) {
            // The mask goes on beyond the round screen; the picture stays black there
            val m = sky[i] * edgeFade(i % SIZE, i / SIZE)
            if (m <= 0f) continue
            image.r[i] = screen(image.r[i], auroraRgb.r[i] * m)
            image.g[i] = screen(image.g[i], auroraRgb.g[i] * m)
            image.b[i] = screen(image.b[i], auroraRgb.b[i] * m)
        }
        drawMoon(image)
        drawLights(image)
        return image
    }

    /** The moon as a waxing crescent in the upper right, with a faint halo. */
    private fun drawMoon(image: Rgb) {
        val cx = MOON_X * SCALE
        val cy = MOON_Y * SCALE
        val size = (MOON_SIZE * SCALE).roundToInt()
        val light = Rgb(SIZE, SIZE)
        glow(light, cx, cy, size * 0.9f, 22f, floatArrayOf(0.75f, 0.85f, 1f))
        glow(light, cx, cy, size * 0.45f, 18f, floatArrayOf(0.9f, 0.95f, 1f))
        screenInto(image, light)
        val photoBytes = moonPhotoFile.readBytes()
        val moonPhoto = requireNotNull(BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)) { "Cannot read $moonPhotoFile" }
        val moon = MoonPicture.draw(moonPhoto, size, MoonLight.Look(MOON_TERMINATOR, MOON_LIMB_ANGLE, 0.0))
        // The same moon fully lit tells how much light each pixel has, to make the dark part fainter
        val full = MoonPicture.draw(moonPhoto, size, MoonLight.Look(-1.0, MOON_LIMB_ANGLE, 0.0))
        val pixels = IntArray(size * size)
        moon.getPixels(pixels, 0, size, 0, 0, size, size)
        val fullPixels = IntArray(size * size)
        full.getPixels(fullPixels, 0, size, 0, 0, size, size)
        val left = (cx - size / 2f).roundToInt()
        val top = (cy - size / 2f).roundToInt()
        for (y in 0 until size) for (x in 0 until size) {
            val p = pixels[y * size + x]
            val f = fullPixels[y * size + x]
            val lit = if (Color.alpha(f) == 0) 0f
            else ((Color.alpha(p).toFloat() / Color.alpha(f) - MoonPicture.DARK_COVER.toFloat()) / (1 - MoonPicture.DARK_COVER.toFloat())).coerceIn(0f, 1f)
            // The earth light on the dark part: there, but much fainter than the app draws it on its own
            val a = Color.alpha(p) / 255f * (MOON_EARTH_LIGHT + (1 - MOON_EARTH_LIGHT) * lit)
            if (a <= 0f) continue
            val k = (top + y) * SIZE + left + x
            // A touch warmer and less bright than the photo, like the moon low in a cold sky
            image.r[k] = image.r[k] * (1 - a) + Color.red(p) * 0.97f * a
            image.g[k] = image.g[k] * (1 - a) + Color.green(p) * 0.96f * a
            image.b[k] = image.b[k] * (1 - a) + Color.blue(p) * 0.92f * a
        }
    }

    /** All lights on: warm white along the eaves, mixed colours and warm white on the tree, with a soft glow. */
    private fun drawLights(image: Rgb) {
        val light = Rgb(SIZE, SIZE)
        for ((x, y) in EAVE_LIGHTS) {
            glow(light, x * SCALE, y * SCALE, 3.6f, 70f, WARM_WHITE)
            glow(light, x * SCALE, y * SCALE, 1.0f, 230f, WARM_CORE)
        }
        for ((index, point) in TREE_LIGHTS.withIndex()) {
            val color = treeLightColor(index)
            val tint = floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
            val core = floatArrayOf(0.6f + 0.4f * tint[0], 0.6f + 0.4f * tint[1], 0.6f + 0.4f * tint[2])
            glow(light, point.first * SCALE, point.second * SCALE, 4.6f, 85f, tint)
            glow(light, point.first * SCALE, point.second * SCALE, 1.15f, 240f, core)
        }
        screenInto(image, light)
    }

    /** Adds a soft round light (a gaussian with [sigma] pixels) of [strength] (0 to 255) and [color] (0 to 1) to [light]. */
    private fun glow(light: Rgb, cx: Float, cy: Float, sigma: Float, strength: Float, color: FloatArray) {
        val reach = ceil(sigma * 3.2f).toInt()
        val x0 = max(0, (cx - reach).toInt())
        val x1 = min(light.w - 1, (cx + reach).toInt())
        val y0 = max(0, (cy - reach).toInt())
        val y1 = min(light.h - 1, (cy + reach).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            val v = strength * exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
            val k = y * light.w + x
            light.r[k] += v * color[0]
            light.g[k] += v * color[1]
            light.b[k] += v * color[2]
        }
    }

    private fun screenInto(image: Rgb, light: Rgb) {
        for (i in 0 until image.w * image.h) {
            image.r[i] = screen(image.r[i], min(255f, light.r[i]))
            image.g[i] = screen(image.g[i], min(255f, light.g[i]))
            image.b[i] = screen(image.b[i], min(255f, light.b[i]))
        }
    }

    /** 0 below 0, 1 above 1, and a smooth S curve between. */
    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    private fun screen(base: Float, top: Float): Float = 255f - (255f - base) * (255f - top) / 255f

    // ---------------------------------------------------------------------------------------------
    // Snow
    // ---------------------------------------------------------------------------------------------

    /** One snow crystal in a square of [px]: six arms with side branches, a small hexagon in the middle, a soft glow. */
    private fun crystalSprite(px: Int): Bitmap {
        val big = px * SUPER
        val shape = createBitmap(big, big)
        drawCrystal(Canvas(shape), big / 2f, big / 2f, big * 0.36f, 0f, big / 48f * 1.1f)
        return withGlow(shape, big, (big / 18f).roundToInt(), 0.7f).let { downsample(it, SUPER) }
    }

    /** Draws a crystal of arm length [arm] at ([cx], [cy]), turned by [turn] radians, lines [stroke] wide. */
    private fun drawCrystal(canvas: Canvas, cx: Float, cy: Float, arm: Float, turn: Float, stroke: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = stroke
        for (k in 0 until 6) {
            val a = turn + k * PI.toFloat() / 3
            val ux = sin(a)
            val uy = -cos(a)
            canvas.drawLine(cx, cy, cx + ux * arm, cy + uy * arm, paint)
            // Side branches, pointing out toward the tip, shorter near the tip
            for ((at, length) in listOf(0.38f to 0.34f, 0.62f to 0.27f, 0.82f to 0.16f)) {
                val bx = cx + ux * arm * at
                val by = cy + uy * arm * at
                for (side in listOf(-1f, 1f)) {
                    val b = a + side * PI.toFloat() / 3.2f
                    canvas.drawLine(bx, by, bx + sin(b) * arm * length, by - cos(b) * arm * length, paint)
                }
            }
        }
        // The small hexagon in the middle
        val hexagon = Path()
        for (k in 0..6) {
            val a = turn + k * PI.toFloat() / 3 + PI.toFloat() / 6
            val x = cx + sin(a) * arm * 0.2f
            val y = cy - cos(a) * arm * 0.2f
            if (k == 0) hexagon.moveTo(x, y) else hexagon.lineTo(x, y)
        }
        paint.strokeWidth = stroke * 0.85f
        canvas.drawPath(hexagon, paint)
    }

    /**
     * Snow over the whole face for the second hand: crystals and soft flakes of mixed size, fewer in
     * the text area, none beyond the round edge.
     */
    private fun snowField(): Bitmap {
        val big = SIZE * SNOW_SUPER
        val shape = createBitmap(big, big)
        val canvas = Canvas(shape)
        val random = Random(SEED_SNOW)
        val flake = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        var placed = 0
        while (placed < SNOW_COUNT) {
            val x = random.nextFloat() * SIZE
            val y = random.nextFloat() * SIZE
            val crystal = random.nextFloat() < 0.45f
            if (hypot(x - SIZE / 2f, y - SIZE / 2f) > SIZE / 2f - 14) continue
            val inText = x / SCALE in 80f..320f && y / SCALE in 70f..250f
            if (inText && random.nextFloat() > 0.3f) continue
            if (crystal) {
                val arm = (6f + random.nextFloat().pow(1.6f) * 14f) * SNOW_SUPER
                val alpha = 150 + random.nextInt(105)
                canvas.saveLayerAlpha(null, alpha)
                drawCrystal(canvas, x * SNOW_SUPER, y * SNOW_SUPER, arm, random.nextFloat() * PI.toFloat(), max(1.1f * SNOW_SUPER, arm / 9f))
                canvas.restore()
            } else {
                val radius = (1.6f + random.nextFloat().pow(2f) * 3.6f) * SNOW_SUPER
                flake.alpha = 110 + random.nextInt(130)
                canvas.drawCircle(x * SNOW_SUPER, y * SNOW_SUPER, radius, flake)
            }
            placed++
        }
        return quantizeAlpha(downsample(withGlow(shape, big, 2 * SNOW_SUPER + 1, 0.5f), SNOW_SUPER), SNOW_ALPHA_STEP)
    }

    /** [shape] (white on clear, [size] square) with a soft glow of [radius] pixels and [strength] around it, slightly cold white. */
    private fun withGlow(shape: Bitmap, size: Int, radius: Int, strength: Float): Bitmap {
        val core = alphaPlane(shape)
        val halo = blur(core, size, size, radius)
        val pixels = IntArray(size * size) {
            val a = 1 - (1 - core[it] / 255f) * (1 - strength * halo[it] / 255f)
            val alpha = (a * 255).roundToInt().coerceIn(0, 255)
            if (alpha == 0) Color.TRANSPARENT else Color.argb(alpha, 240, 248, 255)
        }
        return createBitmap(size, size).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
    }

    /** Rounds the alpha of [bitmap] to steps of [step], so the png gets much smaller; the eye does not see it on snow. */
    private fun quantizeAlpha(bitmap: Bitmap, step: Int): Bitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = ((Color.alpha(p) + step / 2) / step * step).coerceAtMost(255)
            pixels[i] = if (a == 0) Color.TRANSPARENT else Color.argb(a, 240, 248, 255)
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return bitmap
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun toRgb(bitmap: Bitmap): Rgb {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = Rgb(w, h)
        for (i in pixels.indices) {
            out.r[i] = Color.red(pixels[i]).toFloat()
            out.g[i] = Color.green(pixels[i]).toFloat()
            out.b[i] = Color.blue(pixels[i]).toFloat()
        }
        return out
    }

    private fun toBitmap(image: Rgb): Bitmap {
        val pixels = IntArray(image.w * image.h) { Color.rgb(clamp(image.r[it]), clamp(image.g[it]), clamp(image.b[it])) }
        return createBitmap(image.w, image.h).apply { setPixels(pixels, 0, image.w, 0, 0, image.w, image.h) }
    }

    private fun clamp(value: Float): Int = value.roundToInt().coerceIn(0, 255)

    private fun alphaPlane(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FloatArray(pixels.size) { Color.alpha(pixels[it]).toFloat() }
    }

    /** A box blur of [radius], [passes] times (three passes are close to a gaussian); the edges are repeated. */
    private fun blur(plane: FloatArray, w: Int, h: Int, radius: Int, passes: Int = 3): FloatArray {
        if (radius <= 0) return plane.copyOf()
        var a = plane.copyOf()
        var b = FloatArray(plane.size)
        val span = 2 * radius + 1
        repeat(passes) {
            for (y in 0 until h) {
                var sum = 0f
                for (k in -radius..radius) sum += a[y * w + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    b[y * w + x] = sum / span
                    sum += a[y * w + (x + radius + 1).coerceAtMost(w - 1)] - a[y * w + (x - radius).coerceAtLeast(0)]
                }
            }
            for (x in 0 until w) {
                var sum = 0f
                for (k in -radius..radius) sum += b[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    a[y * w + x] = sum / span
                    sum += b[(y + radius + 1).coerceAtMost(h - 1) * w + x] - b[(y - radius).coerceAtLeast(0) * w + x]
                }
            }
        }
        return a
    }

    /** Makes [bitmap] [factor] times smaller by averaging blocks (with the colour weighted by alpha). */
    private fun downsample(bitmap: Bitmap, factor: Int): Bitmap {
        val w = bitmap.width
        val sw = w / factor
        val sh = bitmap.height / factor
        val pixels = IntArray(w * bitmap.height)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, bitmap.height)
        val out = IntArray(sw * sh)
        val n = factor * factor
        for (y in 0 until sh) for (x in 0 until sw) {
            var a = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            for (dy in 0 until factor) for (dx in 0 until factor) {
                val p = pixels[(y * factor + dy) * w + x * factor + dx]
                val pa = Color.alpha(p).toFloat()
                a += pa
                r += Color.red(p) * pa
                g += Color.green(p) * pa
                b += Color.blue(p) * pa
            }
            out[y * sw + x] = if (a <= 0f) Color.TRANSPARENT else Color.argb(clamp(a / n), clamp(r / a), clamp(g / a), clamp(b / a))
        }
        return createBitmap(sw, sh).apply { setPixels(out, 0, sw, 0, 0, sw, sh) }
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** The best JPG quality (from [MAX_QUALITY] down) that fits in [budget] bytes, and that quality. */
    private fun jpgWithin(bitmap: Bitmap, budget: Int): Pair<ByteArray, Int> {
        var quality = MAX_QUALITY
        while (true) {
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (bytes.size <= budget || quality <= MIN_QUALITY) return bytes to quality
            quality -= 2
        }
    }

    companion object {

        /** Pictures are this many times the design size (the design is 400 x 400). */
        const val SCALE = 2f

        /** The size of the face pictures in pixels. */
        const val SIZE = 800

        /** The centre of the tower clock, design coordinates. */
        const val CLOCK_X = 82.6f
        const val CLOCK_Y = 198.8f

        /** The radius of the tower clock face, iron ring included, design coordinates. */
        const val CLOCK_RADIUS = 10f

        /** The size of the hand pictures in pixels: as wide as the clock face. */
        const val HAND_PX = (2 * CLOCK_RADIUS * SCALE).toInt()

        /** The sizes of the crystal pictures in pixels. */
        const val CRYSTAL_SMALL_PX = 48
        const val CRYSTAL_BIG_PX = 96

        /** The moon (centre and size, design coordinates): upper right, away from the text area x 80..320, y 70..250. */
        const val MOON_X = 336f
        const val MOON_Y = 104f
        const val MOON_SIZE = 30f

        /** The byte budgets. */
        const val BACKGROUND_BUDGET = 150_000
        const val AURORA_BUDGET = 40_000

        /**
         * The bulbs of the light strings along the eaves of the chalets, design coordinates. Per
         * string, from the top of the gable down; the strings go from left to right. They were found
         * in the mirrored master as small bright points along each eave (brighter than the ring around
         * them, warm, not inside a window) and checked on the debug picture.
         */
        val EAVE_LIGHTS: List<Pair<Float, Float>> = floatArrayOf(
            51.0f, 219.3f, 49.0f, 222.5f, 46.7f, 222.9f, 47.1f, 225.6f, 44.7f, 225.6f, 44.3f, 227.9f, 42.8f, 230.7f, 40.0f, 229.9f,
            39.3f, 232.6f, 37.3f, 232.6f, 36.9f, 236.9f, 34.6f, 235.4f, 30.3f, 239.3f, 29.1f, 243.2f, 26.8f, 241.6f, 23.6f, 244.3f,
            25.2f, 249.4f, 22.1f, 249.0f, 18.9f, 251.8f, 16.6f, 255.7f, 14.3f, 254.1f, 14.6f, 259.6f, 11.1f, 257.2f, 10.4f, 260.7f,
            52.9f, 220.5f, 52.9f, 223.6f, 54.1f, 226.0f, 53.3f, 227.9f, 56.4f, 229.5f, 56.1f, 233.0f, 59.2f, 236.5f, 61.1f, 239.3f,
            59.2f, 240.8f, 62.7f, 242.4f, 64.6f, 247.1f, 65.8f, 248.6f, 63.9f, 250.2f, 66.6f, 250.6f, 66.2f, 253.7f, 67.8f, 255.7f,
            67.0f, 257.6f, 69.3f, 258.8f, 71.7f, 261.9f, 74.0f, 265.0f, 71.7f, 266.2f, 74.4f, 268.2f, 77.1f, 270.9f, 76.0f, 273.2f,
            77.9f, 276.8f, 115.0f, 248.2f, 114.3f, 250.6f, 113.1f, 252.1f, 110.7f, 254.1f, 110.0f, 256.1f, 108.4f, 258.0f, 106.4f, 257.6f,
            106.8f, 260.4f, 104.9f, 260.7f, 104.5f, 263.1f, 101.8f, 263.9f, 100.6f, 266.6f, 98.2f, 268.9f, 94.3f, 273.2f, 94.7f, 276.0f,
            91.6f, 276.4f, 93.2f, 277.9f, 91.2f, 279.1f, 89.6f, 281.4f, 87.7f, 280.3f, 117.8f, 245.5f, 117.8f, 247.9f, 118.9f, 250.6f,
            119.7f, 253.3f, 122.1f, 256.1f, 122.1f, 258.0f, 123.6f, 260.4f, 125.2f, 261.5f, 124.8f, 264.6f, 127.5f, 266.6f, 127.1f, 269.3f,
            129.1f, 270.5f, 128.7f, 272.5f, 130.7f, 273.2f, 130.7f, 275.2f, 132.6f, 276.4f, 131.1f, 278.3f, 133.0f, 279.5f, 134.2f, 281.8f,
            135.4f, 283.4f, 136.5f, 285.0f, 152.5f, 259.6f, 150.6f, 260.4f, 149.0f, 263.1f, 147.9f, 265.4f, 145.5f, 267.4f, 143.6f, 268.6f,
            142.0f, 270.5f, 140.4f, 272.1f, 139.3f, 274.0f, 154.5f, 261.9f, 154.9f, 263.9f, 156.8f, 266.6f, 158.0f, 269.7f, 159.6f, 271.3f,
            160.4f, 274.0f, 161.9f, 276.0f, 164.6f, 279.9f, 165.4f, 282.6f, 167.0f, 284.6f, 168.2f, 286.1f, 169.3f, 288.9f, 260.4f, 250.2f,
            258.0f, 252.5f, 257.6f, 254.5f, 256.8f, 256.8f, 254.9f, 261.1f, 253.7f, 263.1f, 252.1f, 264.3f, 252.9f, 266.2f, 251.8f, 268.2f,
            249.8f, 271.7f, 248.2f, 274.0f, 247.9f, 276.0f, 246.7f, 277.9f, 245.1f, 281.8f, 243.9f, 283.8f, 244.3f, 286.1f, 261.5f, 252.5f,
            263.5f, 254.5f, 265.8f, 252.5f, 265.0f, 255.7f, 266.6f, 256.8f, 265.0f, 258.4f, 268.2f, 258.8f, 269.7f, 261.1f, 269.7f, 263.1f,
            272.1f, 265.0f, 274.0f, 263.5f, 274.8f, 266.6f, 272.9f, 268.2f, 276.8f, 270.1f, 278.3f, 272.1f, 279.9f, 274.4f, 280.7f, 276.4f,
            282.6f, 276.4f, 282.6f, 278.3f, 285.0f, 278.3f, 284.2f, 281.1f, 285.7f, 282.6f, 332.6f, 216.6f, 332.2f, 220.1f, 329.1f, 221.3f,
            330.3f, 224.0f, 329.1f, 225.6f, 329.1f, 228.3f, 325.6f, 230.3f, 326.8f, 232.6f, 324.4f, 232.6f, 324.8f, 235.4f, 324.0f, 239.3f,
            322.1f, 242.8f, 320.1f, 243.6f, 321.7f, 245.5f, 318.6f, 247.9f, 318.6f, 250.6f, 316.2f, 251.0f, 315.8f, 254.1f, 313.5f, 255.3f,
            311.9f, 258.4f, 313.9f, 260.0f, 311.1f, 261.5f, 311.5f, 264.3f, 308.8f, 263.5f, 309.6f, 266.2f, 309.6f, 268.2f, 307.2f, 268.9f,
            307.6f, 271.7f, 305.7f, 272.1f, 334.6f, 214.6f, 336.9f, 216.6f, 339.3f, 219.3f, 340.0f, 222.5f, 343.2f, 223.2f, 343.6f, 226.4f,
            345.9f, 225.2f, 347.1f, 228.3f, 349.4f, 227.9f, 349.8f, 231.4f, 351.8f, 231.4f, 354.5f, 233.4f, 356.8f, 235.0f, 358.8f, 238.1f,
            363.1f, 241.6f, 366.6f, 244.7f, 370.1f, 249.0f, 370.1f, 252.5f, 373.6f, 252.5f
        ).toList().chunked(2) { it[0] to it[1] }

        /**
         * The outline of the tree in the source photo after the mirror (1024 x 1024): rows of
         * (y, centre x, half width), from the top down.
         */
        private val TREE_OUTLINE: List<Triple<Float, Float, Float>> = listOf(
            Triple(600f, 512f, 4f), Triple(620f, 512f, 16f), Triple(670f, 511f, 42f), Triple(720f, 513f, 62f),
            Triple(770f, 514f, 77f), Triple(820f, 513f, 90f), Triple(855f, 512f, 99f)
        )

        /**
         * The lights on the tree, design coordinates: a garland that winds [TREE_TURNS] times round the
         * tree from the top down. Only the front half is seen; a light about every [TREE_LIGHT_STEP]
         * source pixels along it, a little shaken so it does not look drawn with a ruler.
         */
        val TREE_LIGHTS: List<Pair<Float, Float>> = treeLights()

        /** The colour of tree light [index]: about half warm white, the rest red, gold, blue and green. */
        fun treeLightColor(index: Int): Int = TREE_COLORS[index % TREE_COLORS.size]

        private val TREE_COLORS = intArrayOf(
            Color.rgb(255, 214, 160), Color.rgb(255, 70, 50), Color.rgb(255, 214, 160), Color.rgb(255, 190, 60),
            Color.rgb(255, 214, 160), Color.rgb(80, 140, 255), Color.rgb(255, 214, 160), Color.rgb(90, 230, 110)
        )

        private const val TREE_TURNS = 6
        private const val TREE_TOP = 607f
        private const val TREE_BOTTOM = 846f
        private const val TREE_LIGHT_STEP = 9.5f

        private fun treeOutlineAt(y: Float): Pair<Float, Float> {
            val rows = TREE_OUTLINE
            if (y <= rows.first().first) return rows.first().second to rows.first().third
            for (k in 1 until rows.size) {
                val (y1, c1, h1) = rows[k]
                if (y <= y1) {
                    val (y0, c0, h0) = rows[k - 1]
                    val f = (y - y0) / (y1 - y0)
                    return (c0 + (c1 - c0) * f) to (h0 + (h1 - h0) * f)
                }
            }
            return rows.last().second to rows.last().third
        }

        private fun treeLights(): List<Pair<Float, Float>> {
            val random = Random(SEED_TREE)
            val out = mutableListOf<Pair<Float, Float>>()
            val turns = TREE_TURNS * 2 * PI
            var theta = 0.0
            var travelled = 0f
            var lastX = Float.NaN
            var lastY = Float.NaN
            while (theta < turns) {
                val y = TREE_TOP + (TREE_BOTTOM - TREE_TOP) * (theta / turns).toFloat()
                val (cx, half) = treeOutlineAt(y)
                // The garland hangs a little: lower in the middle of the front
                val front = cos(theta).toFloat()
                val x = cx + half * 0.86f * sin(theta).toFloat()
                val yy = y + 6f * front
                if (!lastX.isNaN()) travelled += hypot(x - lastX, yy - lastY)
                lastX = x
                lastY = yy
                if (front > -0.05f && travelled >= TREE_LIGHT_STEP) {
                    travelled = 0f
                    val jx = (random.nextGaussian() * 1.2).toFloat()
                    val jy = (random.nextGaussian() * 1.2).toFloat()
                    out += ((x + jx + 0.5f) * DESIGN_PER_SOURCE).round1() to ((yy + jy + 0.5f) * DESIGN_PER_SOURCE).round1()
                }
                theta += 0.004
            }
            return out
        }

        private fun Float.round1(): Float = (this * 10).roundToInt() / 10f

        /** Output pixels per source pixel (800 / 1024). */
        private const val OUT_PER_SOURCE = SIZE / 1024f

        /** Design units per source pixel (400 / 1024). */
        private const val DESIGN_PER_SOURCE = 400f / 1024f

        /** Points of the source photo further than this from the centre take the colour at this radius. */
        private const val PHOTO_RIM = 504f

        /** The small clock on the side of the tower, in 800 pixels: centre and half axes. */
        private const val SIDE_CLOCK_X = 263.5f * OUT_PER_SOURCE
        private const val SIDE_CLOCK_Y = 518f * OUT_PER_SOURCE
        private const val SIDE_CLOCK_RX = 4.7f
        private const val SIDE_CLOCK_RY = 18.6f

        /** Clock hands: length, width and the tail behind the pivot, as parts of the clock radius. */
        private const val MINUTE_LENGTH = 0.8f
        private const val MINUTE_WIDTH = 0.11f
        private const val MINUTE_TAIL = 0.16f
        private const val HOUR_LENGTH = 0.5f
        private const val HOUR_WIDTH = 0.16f
        private const val HOUR_TAIL = 0.13f

        /**
         * Sky detection (see `findHorizon`): the texture and the brightness step that end the sky, the
         * height of a star spike, the half width of a dip, and the margin and the soft edge of the mask
         * (source pixels).
         */
        private const val SKY_TEXTURE = 1.6f
        private const val SKY_STEP = 8f
        private const val SKY_SPIKE = 30f
        private const val SKY_DIP = 2
        private const val SKY_MARGIN = 0.5f
        private const val SKY_SOFT = 1.6f

        /** The aurora: vertical stretch, shift (source rows), saturation and gain. */
        private const val AURORA_STRETCH = 1f
        private const val AURORA_SHIFT = 80f
        private const val AURORA_SATURATION = 0.6f
        private const val AURORA_GAIN = 0.46f

        /** The moon: a waxing crescent, about a quarter lit, the lit side to the lower right. */
        private const val MOON_TERMINATOR = 0.5
        private const val MOON_LIMB_ANGLE = 240.0

        /** How much of the app's dark part of the moon is kept (the light of the earth). */
        private const val MOON_EARTH_LIGHT = 0.4f

        private val WARM_WHITE = floatArrayOf(1f, 0.78f, 0.48f)
        private val WARM_CORE = floatArrayOf(1f, 0.95f, 0.85f)

        private const val SNOW_COUNT = 70
        private const val SNOW_SUPER = 2
        private const val SNOW_ALPHA_STEP = 12
        private const val SUPER = 4
        private const val MAX_QUALITY = 92
        private const val MIN_QUALITY = 50
        private const val SEED_GRAIN = 1225L
        private const val SEED_SNOW = 2412L
        private const val SEED_TREE = 1712L
    }
}
