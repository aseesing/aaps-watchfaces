package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

/**
 * The small picture of Santa in his sleigh with two reindeer, for the easter egg of "Aurora Town".
 * Drawn here with plain shapes, facing right: the sleigh flies from left to right. [WIDTH] x [HEIGHT]
 * design pixels, drawn at twice that size so it stays sharp on a big watch.
 */
object AuroraTownSanta {

    /** The size in design pixels. */
    const val WIDTH = 96f
    const val HEIGHT = 40f

    private const val SCALE = 2f

    private const val RED = "#D7263D"
    private const val DARK_RED = "#8E1426"
    private const val GOLD = "#F4C542"
    private const val WHITE = "#F6F6F2"
    private const val SKIN = "#F2C7A5"
    private const val SACK = "#7A4E2A"
    private const val DEER = "#8B5A2B"
    private const val DEER_DARK = "#5E3B1C"
    private const val ANTLER = "#E2C9A0"
    private const val NOSE = "#FF3B3B"

    /** The picture as a PNG. */
    fun png(): ByteArray {
        val bitmap = draw()
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    /** The picture, [WIDTH] x [HEIGHT] times [SCALE] pixels, clear around the shapes. */
    fun draw(): Bitmap {
        val bitmap = createBitmap((WIDTH * SCALE).toInt(), (HEIGHT * SCALE).toInt()).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun color(p: Paint, hex: String, width: Float = 1f) = p.apply { color = Color.parseColor(hex); strokeWidth = width }

        // The reins, from the front of the sleigh to both reindeer
        color(line, GOLD, 0.8f)
        canvas.drawLine(37f, 19f, 60f, 20f, line)
        canvas.drawLine(37f, 19f, 80f, 18f, line)

        // Two reindeer, the front one with a red nose
        reindeer(canvas, fill, line, 58f, ::color, nose = false)
        reindeer(canvas, fill, line, 76f, ::color, nose = true)

        // The runner under the sleigh, curled up at the front
        color(line, GOLD, 1.6f)
        canvas.drawPath(Path().apply {
            moveTo(3f, 32f)
            lineTo(36f, 32f)
            quadTo(43f, 32f, 42f, 26f)
            quadTo(41f, 23f, 38f, 25f)
        }, line)
        canvas.drawLine(10f, 29f, 10f, 32f, line)
        canvas.drawLine(30f, 29f, 30f, 32f, line)

        // The sack of presents behind Santa
        color(fill, SACK)
        canvas.drawCircle(11f, 15f, 6f, fill)
        color(line, GOLD, 0.8f)
        canvas.drawLine(9f, 10f, 13f, 10f, line)

        // Santa: body, face, beard and hat
        color(fill, RED)
        canvas.drawCircle(21f, 16f, 6.5f, fill)
        color(fill, SKIN)
        canvas.drawCircle(23f, 8f, 3.3f, fill)
        color(fill, WHITE)
        canvas.drawCircle(24f, 11f, 3.2f, fill)
        color(fill, RED)
        canvas.drawPath(Path().apply { moveTo(19.5f, 6.5f); lineTo(26.5f, 6f); lineTo(17f, 0.8f); close() }, fill)
        color(fill, WHITE)
        canvas.drawRoundRect(RectF(19f, 5.2f, 27f, 7.4f), 1.1f, 1.1f, fill)
        canvas.drawCircle(17f, 1.4f, 1.4f, fill)
        // His arm with the reins
        color(line, RED, 2.2f)
        canvas.drawLine(23f, 15f, 30f, 17f, line)
        color(fill, WHITE)
        canvas.drawCircle(30.5f, 17.2f, 1.3f, fill)

        // The sleigh, in front of Santa: red with a gold edge, a curl at the front
        val body = Path().apply {
            moveTo(4f, 18f)
            lineTo(30f, 21f)
            quadTo(38f, 21f, 38f, 15f)
            quadTo(39f, 12f, 41f, 13f)
            quadTo(41f, 27f, 32f, 29f)
            lineTo(8f, 29f)
            quadTo(3f, 28f, 4f, 18f)
            close()
        }
        color(fill, RED)
        canvas.drawPath(body, fill)
        color(line, GOLD, 1f)
        canvas.drawPath(body, line)
        color(line, DARK_RED, 0.8f)
        canvas.drawLine(8f, 25f, 32f, 25f, line)
        return bitmap
    }

    /** One reindeer, galloping to the right, with its back legs at [x]. */
    private fun reindeer(canvas: Canvas, fill: Paint, line: Paint, x: Float, color: (Paint, String, Float) -> Paint, nose: Boolean) {
        // Legs, stretched out as in a jump
        color(line, DEER_DARK, 1.3f)
        canvas.drawLine(x + 2f, 23f, x - 2f, 29f, line)
        canvas.drawLine(x + 4f, 23f, x + 1f, 30f, line)
        canvas.drawLine(x + 12f, 23f, x + 17f, 28f, line)
        canvas.drawLine(x + 13f, 22f, x + 18f, 26f, line)
        // Body, neck and head
        color(fill, DEER, 1f)
        canvas.drawOval(RectF(x, 17f, x + 15f, 25f), fill)
        canvas.drawPath(Path().apply { moveTo(x + 11f, 19f); lineTo(x + 15f, 11f); lineTo(x + 17f, 13f); lineTo(x + 15f, 21f); close() }, fill)
        canvas.drawOval(RectF(x + 13.5f, 9.5f, x + 20f, 13.5f), fill)
        // A white tail and a dark eye
        color(fill, WHITE, 1f)
        canvas.drawCircle(x + 0.5f, 18.5f, 1.2f, fill)
        color(fill, DEER_DARK, 1f)
        canvas.drawCircle(x + 16.5f, 10.8f, 0.6f, fill)
        // Antlers
        color(line, ANTLER, 0.9f)
        canvas.drawLine(x + 15f, 10f, x + 13f, 5f, line)
        canvas.drawLine(x + 13.8f, 7f, x + 11.5f, 6f, line)
        canvas.drawLine(x + 16f, 10f, x + 17f, 4.5f, line)
        canvas.drawLine(x + 16.6f, 6.5f, x + 19f, 5.5f, line)
        if (nose) {
            color(fill, NOSE, 1f)
            canvas.drawCircle(x + 20f, 11.8f, 1.3f, fill)
        }
    }
}
