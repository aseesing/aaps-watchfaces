// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Checks the snow cap fonts of [SnowCapFont]: Android loads both, they have the same widths, the cap
 * lies on the top of the digits. Writes `build/cwf-design/aurora/snowcap-sample.png` to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SnowCapFontTest {

    private val out = File("build/cwf-design/aurora").apply { mkdirs() }
    private val base by lazy { typeface(SnowCapFont.baseFont(), SnowCapFont.BASE_NAME) }
    private val cap by lazy { typeface(SnowCapFont.capFont(), SnowCapFont.CAP_NAME) }
    private val cut by lazy { typeface(SnowCapFont.cutFont(), SnowCapFont.CUT_NAME) }

    private fun typeface(bytes: ByteArray, name: String): Typeface {
        val file = File.createTempFile(name, ".ttf").apply { deleteOnExit() }
        file.writeBytes(bytes)
        return Typeface.Builder(file).build()
    }

    private fun paint(typeface: Typeface, size: Float, color: Int = Color.WHITE) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = size
        this.color = color
    }

    @Test
    fun fontsAreSmall() {
        assertThat(SnowCapFont.baseFont().size).isLessThan(10 * 1024)
        assertThat(SnowCapFont.capFont().size).isLessThan(10 * 1024)
        assertThat(SnowCapFont.cutFont().size).isLessThan(10 * 1024)
    }

    @Test
    fun theCutDigitsOverTheCapLookLikeTheCapOverTheDigits() {
        val text = "0123456789,"
        val size = 120f
        val green = Color.rgb(0x4F, 0xD9, 0x8A)
        val width = (paint(base, size).measureText(text) + size).toInt()
        fun picture(first: Typeface, firstColor: Int, second: Typeface, secondColor: Int): Bitmap = createBitmap(width, (size * 1.6f).toInt()).also {
            val canvas = Canvas(it)
            canvas.drawColor(Color.rgb(0x0B, 0x16, 0x38))
            canvas.drawText(text, size / 2, size * 1.2f, paint(first, size, firstColor))
            canvas.drawText(text, size / 2, size * 1.2f, paint(second, size, secondColor))
        }
        // As the time is drawn (the cap above) and as the glucose value is drawn (the cap below)
        val above = picture(base, green, cap, Color.WHITE)
        val below = picture(cap, Color.WHITE, cut, green)
        File(out, "snowcap-cut.png").outputStream().use { stack(above, below).compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        var different = 0
        val background = Color.rgb(0x0B, 0x16, 0x38)
        for (y in 0 until above.height) for (x in 0 until above.width) {
            val a = above.getPixel(x, y)
            val b = below.getPixel(x, y)
            if (a != background || b != background) inked++
            if (maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b))) > 60) different++
        }
        // Only the thin band where the cut digit reaches under the soft edge of its cap differs
        assertWithMessage("$different of $inked pixels differ").that(different.toDouble() / inked).isAtMost(0.05)
        // The cut font has the same widths
        for (t in listOf(text, "6,7", "22,2", "---")) assertWithMessage(t).that(paint(cut, 100f).measureText(t)).isEqualTo(paint(base, 100f).measureText(t))
        assertThat(paint(cut, 100f).fontMetrics.top).isEqualTo(paint(base, 100f).fontMetrics.top)
        assertThat(paint(cut, 100f).fontMetrics.bottom).isEqualTo(paint(base, 100f).fontMetrics.bottom)
    }

    private fun stack(top: Bitmap, bottom: Bitmap): Bitmap = createBitmap(top.width, top.height + bottom.height).also {
        val canvas = Canvas(it)
        canvas.drawBitmap(top, 0f, 0f, null)
        canvas.drawBitmap(bottom, 0f, top.height.toFloat(), null)
    }

    @Test
    fun bothFontsLoadWithTheirOwnWidths() {
        val text = "12:34"
        // Android rounds each advance to whole pixels
        val expected = text.sumOf { (SnowCapFont.advance(it) * 100f / SnowCapFont.unitsPerEm).roundToInt() }.toFloat()
        val fallback = paint(Typeface.DEFAULT, 100f).measureText(text)
        assertThat(fallback).isNotWithin(1f).of(expected)
        assertThat(paint(base, 100f).measureText(text)).isWithin(0.01f).of(expected)
        assertThat(paint(cap, 100f).measureText(text)).isWithin(0.01f).of(expected)
    }

    @Test
    fun theDigitsAllHaveTheSameWidth() {
        val width = SnowCapFont.advance('0')
        for (digit in SnowCapFont.DIGITS) assertWithMessage("$digit").that(SnowCapFont.advance(digit)).isEqualTo(width)
        for (typeface in listOf(base, cap)) {
            val paint = paint(typeface, 100f)
            for (digit in SnowCapFont.DIGITS) assertWithMessage("$digit").that(paint.measureText(digit.toString())).isEqualTo(paint.measureText("0"))
            // "18:24" and "88:88" are as wide: the time does not move when the digits change
            assertThat(paint.measureText("18:24")).isEqualTo(paint.measureText("88:88"))
        }
    }

    @Test
    fun theCommaIsInBothFonts() {
        val fallback = paint(Typeface.DEFAULT, 100f).measureText(",")
        val expected = (SnowCapFont.advance(',') * 100f / SnowCapFont.unitsPerEm).roundToInt().toFloat()
        assertThat(paint(base, 100f).measureText("6,7")).isEqualTo(paint(cap, 100f).measureText("6,7"))
        // A comma and a point are as wide, so "6,7" and "6.7" line up
        assertThat(SnowCapFont.advance(',')).isEqualTo(SnowCapFont.advance('.'))
        assertThat(paint(base, 100f).measureText("6,7")).isEqualTo(paint(base, 100f).measureText("6.7"))
        assertThat(paint(base, 100f).measureText(",")).isWithin(0.01f).of(expected)
        assertThat(Ink(mask(base, ",", 200f)).count).isGreaterThan(0)
        assertThat(expected).isNotEqualTo(fallback)
    }

    @Test
    fun capAndBaseHaveTheSameMetrics() {
        val basePaint = paint(base, 100f)
        val capPaint = paint(cap, 100f)
        for (text in listOf("12:34", "+0.5", "-12", " 9", SnowCapFont.CHARS)) assertWithMessage(text).that(capPaint.measureText(text)).isEqualTo(basePaint.measureText(text))
        val baseMetrics = basePaint.fontMetrics
        val capMetrics = capPaint.fontMetrics
        assertThat(capMetrics.ascent).isEqualTo(baseMetrics.ascent)
        assertThat(capMetrics.descent).isEqualTo(baseMetrics.descent)
        assertThat(capMetrics.top).isEqualTo(baseMetrics.top)
        assertThat(capMetrics.bottom).isEqualTo(baseMetrics.bottom)
    }

    @Test
    fun capOfEightLiesOnTopAndSticksOut() {
        val baseInk = Ink(mask(base, "8", 300f))
        val capInk = Ink(mask(cap, "8", 300f))
        val height = baseInk.bottom - baseInk.top
        assertThat(capInk.count).isGreaterThan(0)
        // Higher than the digit, and a little wider on both sides
        assertThat(capInk.top).isLessThan(baseInk.top - 3)
        val band = baseInk.top..baseInk.top + (0.25 * height).toInt()
        assertThat(capInk.left).isLessThan(baseInk.leftIn(band) - 3)
        assertThat(capInk.right).isGreaterThan(baseInk.rightIn(band) + 3)
        // Only in the top part (drips included)
        assertThat(capInk.bottom).isLessThan(baseInk.top + (0.6 * height).toInt())
        assertThat(capInk.countAbove(baseInk.top + (0.45 * height).toInt()).toDouble() / capInk.count).isAtLeast(0.95)
    }

    @Test
    fun capCoversTheTopEdgeOfEveryDigit() {
        for (digit in "0123456789") {
            val baseMask = mask(base, digit.toString(), 200f)
            val capMask = mask(cap, digit.toString(), 200f)
            val baseInk = Ink(baseMask)
            val limit = baseInk.top + (0.2 * (baseInk.bottom - baseInk.top)).toInt()
            var columns = 0
            var covered = 0
            for (x in 0 until baseMask.width) {
                val top = (0 until baseMask.height).firstOrNull { y -> alpha(baseMask, x, y) > 127 } ?: continue
                if (top > limit) continue
                columns++
                if (alpha(capMask, x, top) > 127) covered++
            }
            assertWithMessage("columns of $digit").that(columns).isGreaterThan(10)
            assertWithMessage("top edge of $digit covered").that(covered.toDouble() / columns).isAtLeast(0.9)
        }
    }

    @Test
    fun writeSample() {
        val bitmap = createBitmap(460, 300)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x0B, 0x16, 0x38))
        drawSnowText(canvas, "18:24", 230f, 125f, 100f, Color.rgb(0xE5, 0x39, 0x35))
        drawSnowText(canvas, "6,7", 230f, 255f, 100f, Color.rgb(0x43, 0xA0, 0x47))
        File(out, "snowcap-sample.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val zoom = createBitmap(1300, 820)
        val zoomCanvas = Canvas(zoom)
        zoomCanvas.drawColor(Color.rgb(0x0B, 0x16, 0x38))
        drawSnowText(zoomCanvas, "0123456789", 650f, 330f, 190f, Color.rgb(0xE5, 0x39, 0x35))
        drawSnowText(zoomCanvas, "18:24 +0.5 -3", 650f, 700f, 170f, Color.rgb(0xFF, 0xB3, 0x00))
        File(out, "snowcap-zoom.png").outputStream().use { zoom.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "${SnowCapFont.BASE_NAME}.ttf").writeBytes(SnowCapFont.baseFont())
        File(out, "${SnowCapFont.CAP_NAME}.ttf").writeBytes(SnowCapFont.capFont())
    }

    /** The two texts on top of each other, as the watch face does it. */
    private fun drawSnowText(canvas: Canvas, text: String, centerX: Float, baseline: Float, size: Float, color: Int) {
        val basePaint = paint(base, size, color).apply { textAlign = Paint.Align.CENTER }
        val capPaint = paint(cap, size, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(text, centerX, baseline, basePaint)
        canvas.drawText(text, centerX, baseline, capPaint)
    }

    private fun mask(typeface: Typeface, text: String, size: Float): Bitmap {
        val paint = paint(typeface, size)
        val bitmap = createBitmap((paint.measureText(text) + size).toInt(), (size * 1.6f).toInt())
        Canvas(bitmap).drawText(text, size / 2, size * 1.2f, paint)
        return bitmap
    }

    private fun alpha(bitmap: Bitmap, x: Int, y: Int) = bitmap.getPixel(x, y) ushr 24

    /** Where the ink of a mask is. */
    private inner class Ink(private val bitmap: Bitmap) {

        private val points = buildList {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (alpha(bitmap, x, y) > 127) add(x to y)
        }
        val count = points.size
        val top = points.minOf { it.second }
        val bottom = points.maxOf { it.second }
        val left = points.minOf { it.first }
        val right = points.maxOf { it.first }
        fun countAbove(y: Int) = points.count { it.second <= y }
        fun leftIn(rows: IntRange) = points.filter { it.second in rows }.minOf { it.first }
        fun rightIn(rows: IntRange) = points.filter { it.second in rows }.maxOf { it.first }
    }
}
