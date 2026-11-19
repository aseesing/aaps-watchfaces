// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.RoundRectangle2D
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two small fonts for snow-covered digits, made from `cwf-designs/fonts/FredokaOne.ttf` (SIL Open Font
 * License, see `FredokaOne-OFL.txt`).
 *
 * A watch face text view draws in one colour only. To show a coloured digit with white snow on it, the
 * face puts two text views with the same text on top of each other:
 * - [baseFont] ("Aurora Digits"): the digits as they are, drawn in the colour of the value.
 * - [capFont] ("Aurora Snow"): only the snow caps, drawn white on top.
 *
 * A face draws its views in a fixed order. Where the coloured view is drawn above the white one (the
 * glucose value `sgv` is drawn after every free text), the coloured view uses [cutFont] ("Aurora Cut"):
 * the digits with the snow cut out, so the cap below shows through. The cut is a little smaller than
 * the cap, so the soft edge of the cap lies under the colour and no dark seam shows.
 *
 * Both fonts have the same glyphs ([CHARS]), the same advance widths and the same vertical metrics,
 * so the two texts line up to the pixel.
 *
 * The licence reserves the name "Fredoka", so the fonts made here have other names. The copyright
 * notice of the source font and the licence reference stay in the name table.
 *
 * The outlines are read straight from the `glyf` table of the source (it has quadratic outlines, so the
 * base glyphs are copied exactly). The snow caps are made with `java.awt.geom.Area` and written back as
 * quadratic outlines. The TrueType files are written here too, no external tool is needed.
 */
object SnowCapFont {

    /** Key of the base font in the watch face zip. */
    const val BASE_NAME = "AuroraDigits"

    /** Key of the snow cap font in the watch face zip. */
    const val CAP_NAME = "AuroraSnow"

    /** Key of the font of the digits with the snow cut out, in the watch face zip. */
    const val CUT_NAME = "AuroraCut"

    /** The characters in both fonts. Nothing else, to keep the files small. The comma is the decimal sign of mmol/L in many languages ("6,7"). */
    const val CHARS = " +,-.0123456789:"

    /** The digits. They all get the same advance (tabular), so a number does not move when one digit changes. */
    const val DIGITS = "0123456789"

    /**
     * The decimal signs. They get the same advance too, so a value lines up the same with a comma or a
     * point: the phone and the watch may format it in different languages.
     */
    const val DECIMAL_SIGNS = ",."

    /** The source font, relative to the folder of this project (the working folder of the tests). */
    val SOURCE = File("cwf-designs/fonts/FredokaOne.ttf")

    private val source by lazy { TtfSource(SOURCE.readBytes()) }
    private val fonts by lazy { SnowCapFontBuilder(source, SnowCapShaper(source.unitsPerEm.toDouble())).build() }

    /** The digits as they are (TrueType file). */
    fun baseFont(): ByteArray = fonts.base.copyOf()

    /** Only the snow caps, with the same metrics as [baseFont] (TrueType file). */
    fun capFont(): ByteArray = fonts.cap.copyOf()

    /** The digits without their snow cap, to draw above [capFont]; the same metrics as [baseFont] (TrueType file). */
    fun cutFont(): ByteArray = fonts.cut.copyOf()

    /** Units per em of both fonts. */
    val unitsPerEm: Int get() = source.unitsPerEm

    /** Advance width of [char] in font units, the same in all fonts. All [DIGITS] have the same one, and so have the [DECIMAL_SIGNS]. */
    fun advance(char: Char): Int = SnowCapFontBuilder.advance(source, char.code)
}

/** One point of a TrueType outline, in font units, y up. */
class TtfPoint(val x: Int, val y: Int, val onCurve: Boolean)

/** One closed TrueType contour. */
typealias TtfContour = List<TtfPoint>

/** A glyph to write: its contours and its advance width. */
class TtfGlyph(val contours: List<TtfContour>, val advance: Int)

/** The three font files. */
class SnowCapFonts(val base: ByteArray, val cap: ByteArray, val cut: ByteArray)

/** Makes the fonts from [source]: copies the glyphs for the base font and lets [shaper] make the caps and the cut digits. */
class SnowCapFontBuilder(private val source: TtfSource, private val shaper: SnowCapShaper) {

    fun build(): SnowCapFonts {
        // Glyph 0 is .notdef (empty), then the characters in code order (the cmap needs them sorted)
        val codes = listOf(0) + SnowCapFont.CHARS.map { it.code }.sorted()
        val base = codes.map { code ->
            if (code == 0) TtfGlyph(emptyList(), source.advance(0))
            else source.glyphId(code).let { gid ->
                require(gid != 0) { "Character ${code.toChar()} is not in the source font" }
                // A digit or decimal sign is moved to the middle of its (wider) shared advance
                val shift = (advance(source, code) - source.advance(gid)) / 2
                TtfGlyph(source.contours(gid).map { contour -> contour.map { TtfPoint(it.x + shift, it.y, it.onCurve) } }, advance(source, code))
            }
        }
        val cap = base.mapIndexed { index, glyph ->
            // The decimal comma and point stay bare: on such a small mark the snow would hide it
            val bare = glyph.contours.isEmpty() || codes[index].toChar() in BARE
            val contours = if (bare) emptyList() else shaper.cap(glyph.contours, codes[index].toLong())
            TtfGlyph(contours, glyph.advance)
        }
        // Only the digits are cut: the other marks have no cap above them in a glucose value
        val cut = base.mapIndexed { index, glyph ->
            val contours = if (codes[index].toChar() in SnowCapFont.DIGITS) shaper.cut(glyph.contours, cap[index].contours) else glyph.contours
            TtfGlyph(contours, glyph.advance)
        }
        // The same box in all heads: Android uses it for the font padding of a text view (the top and
        // the bottom of its font metrics). It reaches at least to the ascent and the descent of the
        // font: else a one-line text lies higher on the watch than a text of two lines and than in
        // the phone preview. The watch app targets Android 11, where a one-line text (BoringLayout)
        // starts at the top of the box, but a longer one (StaticLayout) at the ascent when that is
        // higher; apps for Android 13 and newer (the phone, these tests) use the ascent for both.
        val all = (base + cap).flatMap { it.contours }.flatten()
        val hhea = source.table("hhea")
        val ascent = source.s16(hhea + 4)
        val descent = source.s16(hhea + 6) // negative: below the baseline
        val box = intArrayOf(all.minOf { it.x }, min(all.minOf { it.y }, descent), all.maxOf { it.x }, max(all.maxOf { it.y }, ascent))
        val writer = TtfWriter(source, codes, box)
        return SnowCapFonts(
            base = writer.write(base, family = "Aurora Digits", postScriptName = "AuroraDigits-Regular"),
            cap = writer.write(cap, family = "Aurora Snow", postScriptName = "AuroraSnow-Regular"),
            cut = writer.write(cut, family = "Aurora Cut", postScriptName = "AuroraCut-Regular")
        )
    }

    companion object {

        /**
         * The characters without a snow cap. The colon too: a face draws it in the text of the time, which
         * has no cap view over it, so with snow cut out it would lose its top.
         */
        const val BARE = ",.:"

        /**
         * The advance of the character [code] in the fonts made from [source]: a digit gets the advance of
         * the widest digit, a decimal sign that of the widest decimal sign.
         */
        fun advance(source: TtfSource, code: Int): Int {
            val group = listOf(SnowCapFont.DIGITS, SnowCapFont.DECIMAL_SIGNS).firstOrNull { code.toChar() in it } ?: return source.advance(source.glyphId(code))
            return group.maxOf { source.advance(source.glyphId(it.code)) }
        }
    }
}

/**
 * Makes the snow cap of one glyph: the glyph grown a little (the snow hangs over the edges and lies a
 * little above the top), cut off below a soft wavy line, with a few drips hanging down further. Each
 * part of the glyph (each dot of the colon) gets its own cap.
 */
class SnowCapShaper(private val em: Double) {

    fun cap(contours: List<TtfContour>, seed: Long): List<TtfContour> {
        val result = Area()
        TtfOutlines.components(contours).forEachIndexed { index, part -> result.add(partCap(part, seed * 31 + index)) }
        // Tiny bits (where a low part of the glyph just reaches over the wave) look like dirt, not snow
        val minArea = MIN_PIECE * MIN_PIECE * em * em
        return TtfOutlines.fromShape(result).filter { abs(TtfOutlines.signedArea(it)) >= minArea }
    }

    /**
     * The glyph of [contours] without the part under its cap [cap], so the cap shows when it is drawn
     * below. The cap is first moved [CUT_OVERLAP] up, so the glyph still covers the soft lower edge of
     * the cap. (Made smaller on all sides, the glyph showed a thin edge of colour beside the cap.)
     */
    fun cut(contours: List<TtfContour>, cap: List<TtfContour>): List<TtfContour> {
        val glyph = Area(Path2D.Double(Path2D.WIND_NON_ZERO).apply { contours.forEach { TtfOutlines.addContour(this, it) } })
        if (cap.isNotEmpty()) {
            val snow = Area(Path2D.Double(Path2D.WIND_NON_ZERO).apply { cap.forEach { TtfOutlines.addContour(this, it) } })
            // The snow moved up: the cut digit reaches under the soft lower edge of the cap, but not out beside it
            glyph.subtract(snow.createTransformedArea(AffineTransform.getTranslateInstance(0.0, CUT_OVERLAP * em)))
        }
        // Only crumbs are left out: a small hole (of a 4 or an 8) must stay
        val minArea = CUT_MIN_PIECE * CUT_MIN_PIECE * em * em
        return TtfOutlines.fromShape(glyph).filter { abs(TtfOutlines.signedArea(it)) >= minArea }
    }

    private fun partCap(contours: List<TtfContour>, seed: Long): Area {
        // The first contour is the outer one, the others are holes
        val outer = Path2D.Double().apply { TtfOutlines.addContour(this, contours[0]) }
        val holes = Area(Path2D.Double().apply { contours.drop(1).forEach { TtfOutlines.addContour(this, it) } })
        val glyph = Area(outer).apply { subtract(holes) }
        val box = glyph.bounds2D
        val height = box.height
        val tall = height > 0.4 * em
        // Grown to the outside only, the holes stay as they are. The snow lies on top ([GROW] up) and
        // makes the digit a little bigger on every side ([SIDE_GROW], about a pixel), the same all round:
        // 2 px stuck out beside a digit where its edge turns in below the snow, and much less looked
        // smaller than the digit where it bulges out below the snow (the right of a 2).
        val grown = Area(outer).apply {
            add(Area(outer).createTransformedArea(AffineTransform.getTranslateInstance(0.0, GROW * em)))
            add(Area(roundStroke(2 * SIDE_GROW * em).createStrokedShape(outer)))
            subtract(holes)
        }

        val random = Random(seed)
        val lineY = box.maxY - height * (if (tall) DEPTH else SMALL_DEPTH)
        val amplitude = em * (if (tall) WAVE_AMPLITUDE else WAVE_AMPLITUDE / 2)
        val length = em * WAVE_LENGTH
        val phase = random.nextDouble() * 2 * PI
        val waveY = { x: Double -> lineY + amplitude * sin(2 * PI * x / length + phase) }

        val region = Area(waveRegion(box.minX - 0.2 * em, box.maxX + 0.2 * em, box.maxY + em, waveY, length / 8))
        if (tall) addDrips(region, glyph, box.minX, box.width, height, waveY, amplitude, random)

        val cap = Area(grown).apply { intersect(region) }
        // Thick snow piles up a little higher than the overhang at the sides (none since 2026-11-19: it made the snow look too high)
        if (PILE > 0.0) cap.add(cap.createTransformedArea(AffineTransform.getTranslateInstance(0.0, PILE * em)).apply { subtract(holes) })
        // Soft edges: fill the inner corners (where a drip leaves the wave), then round the outer ones
        return open(close(cap, CLOSE_RADIUS * em), OPEN_RADIUS * em)
    }

    /** Everything above the wave: the wave is a smooth chain of quadratic curves through midpoints. */
    private fun waveRegion(left: Double, right: Double, high: Double, waveY: (Double) -> Double, step: Double): Path2D.Double {
        val count = ((right - left) / step).toInt() + 1
        val xs = DoubleArray(count + 1) { min(left + it * step, right) }
        val ys = DoubleArray(xs.size) { waveY(xs[it]) }
        return Path2D.Double().apply {
            moveTo(left, high)
            lineTo(xs[0], ys[0])
            lineTo((xs[0] + xs[1]) / 2, (ys[0] + ys[1]) / 2)
            for (i in 1 until xs.size - 1) quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2, (ys[i] + ys[i + 1]) / 2)
            lineTo(xs.last(), ys.last())
            lineTo(right, high)
            closePath()
        }
    }

    /** 1 to 3 drips at pseudo random places, only where they hang over ink (not over a hole). */
    private fun addDrips(region: Area, glyph: Area, left: Double, width: Double, height: Double, waveY: (Double) -> Double, amplitude: Double, random: Random) {
        val wanted = 1 + random.nextInt(3)
        val placed = mutableListOf<Double>()
        repeat(DRIP_TRIES) {
            if (placed.size >= wanted) return
            val x = left + (0.1 + 0.8 * random.nextDouble()) * width
            val dripWidth = em * (DRIP_WIDTH + 0.02 * random.nextDouble())
            val dripLength = height * (DRIP_LENGTH + 0.05 * random.nextDouble())
            if (placed.any { abs(it - x) < 2.5 * dripWidth }) return@repeat
            val top = waveY(x)
            val bottom = top - dripLength
            val overInk = (0..8).all { step ->
                val y = top - (dripLength + dripWidth * 0.3) * step / 8
                glyph.contains(x, y) && glyph.contains(x - dripWidth * 0.45, y) && glyph.contains(x + dripWidth * 0.45, y)
            }
            if (!overInk) return@repeat
            placed += x
            region.add(Area(RoundRectangle2D.Double(x - dripWidth / 2, bottom, dripWidth, top + 2 * amplitude + dripWidth - bottom, dripWidth, dripWidth)))
        }
    }

    private fun roundStroke(width: Double) = BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    private fun dilate(area: Area, radius: Double) = Area(area).apply { add(Area(roundStroke(2 * radius).createStrokedShape(area))) }
    private fun erode(area: Area, radius: Double) = Area(area).apply { subtract(Area(roundStroke(2 * radius).createStrokedShape(area))) }
    private fun close(area: Area, radius: Double) = erode(dilate(area, radius), radius)
    private fun open(area: Area, radius: Double) = dilate(erode(area, radius), radius)

    companion object {

        /** How far the snow lies above the top, in em. */
        const val GROW = 0.035

        /** How far the snow hangs over the edges at the sides, in em (stroke width / 2): about a pixel at the size of the time. */
        const val SIDE_GROW = 0.02

        /** Depth of the snow line below the top of a digit, as part of its height. */
        const val DEPTH = 0.27

        /** The same for small parts (the dots of the colon, the minus). */
        const val SMALL_DEPTH = 0.42

        /** How much higher the snow lies on the top than at the sides, in em. 0: the snow lay too high on the digits. */
        const val PILE = 0.0

        const val WAVE_AMPLITUDE = 0.02
        const val WAVE_LENGTH = 0.24
        const val DRIP_WIDTH = 0.07
        const val DRIP_LENGTH = 0.10
        const val DRIP_TRIES = 80
        const val CLOSE_RADIUS = 0.02
        const val OPEN_RADIUS = 0.014

        /** Pieces of snow smaller than a square of this size (in em) are left out. */
        const val MIN_PIECE = 0.08

        /** How far the cut digit reaches under its cap, in em: more than a screen pixel at the sizes of a face. */
        const val CUT_OVERLAP = 0.018

        /** Pieces of a cut digit smaller than a square of this size (in em) are left out. */
        const val CUT_MIN_PIECE = 0.02
    }
}

/** Converting between TrueType contours and AWT shapes. */
object TtfOutlines {

    /** Adds a TrueType contour (quadratic, with implied on-curve points between two off-curve points) to [path]. */
    fun addContour(path: Path2D, contour: TtfContour) {
        val n = contour.size
        if (n == 0) return
        val first = contour.indexOfFirst { it.onCurve }
        val order: List<TtfPoint>
        val startX: Double
        val startY: Double
        if (first < 0) {
            order = contour
            startX = (contour[n - 1].x + contour[0].x) / 2.0
            startY = (contour[n - 1].y + contour[0].y) / 2.0
        } else {
            order = List(n - 1) { contour[(first + 1 + it) % n] }
            startX = contour[first].x.toDouble()
            startY = contour[first].y.toDouble()
        }
        path.moveTo(startX, startY)
        var cx = 0.0
        var cy = 0.0
        var control = false
        for (p in order) {
            if (p.onCurve) {
                if (control) path.quadTo(cx, cy, p.x.toDouble(), p.y.toDouble()) else path.lineTo(p.x.toDouble(), p.y.toDouble())
                control = false
            } else {
                if (control) path.quadTo(cx, cy, (cx + p.x) / 2, (cy + p.y) / 2)
                cx = p.x.toDouble()
                cy = p.y.toDouble()
                control = true
            }
        }
        if (control) path.quadTo(cx, cy, startX, startY)
        path.closePath()
    }

    /** Splits a glyph into parts: each outer contour with the holes inside it. */
    fun components(contours: List<TtfContour>): List<List<TtfContour>> {
        val depth = contours.map { c -> contours.count { other -> other !== c && polygon(other).contains(c[0].x.toDouble(), c[0].y.toDouble()) } }
        val outer = contours.filterIndexed { i, _ -> depth[i] % 2 == 0 }
        val holes = contours.filterIndexed { i, _ -> depth[i] % 2 == 1 }
        return outer.map { o -> listOf(o) + holes.filter { h -> polygon(o).contains(h[0].x.toDouble(), h[0].y.toDouble()) } }
    }

    /** The shape as TrueType contours: cubic curves become quadratic ones, points are rounded, outer contours run clockwise. */
    fun fromShape(shape: Shape): List<TtfContour> {
        val raw = mutableListOf<MutableList<RawPoint>>()
        var current = mutableListOf<RawPoint>()
        val c = DoubleArray(6)
        var lastX = 0.0
        var lastY = 0.0
        val iterator = shape.getPathIterator(null)
        while (!iterator.isDone) {
            when (iterator.currentSegment(c)) {
                PathIterator.SEG_MOVETO  -> {
                    current = mutableListOf(RawPoint(c[0], c[1], true))
                    raw += current
                    lastX = c[0]; lastY = c[1]
                }

                PathIterator.SEG_LINETO  -> {
                    current += RawPoint(c[0], c[1], true)
                    lastX = c[0]; lastY = c[1]
                }

                PathIterator.SEG_QUADTO  -> {
                    current += RawPoint(c[0], c[1], false)
                    current += RawPoint(c[2], c[3], true)
                    lastX = c[2]; lastY = c[3]
                }

                PathIterator.SEG_CUBICTO -> {
                    cubicToQuads(lastX, lastY, c[0], c[1], c[2], c[3], c[4], c[5], current, 0)
                    lastX = c[4]; lastY = c[5]
                }
            }
            iterator.next()
        }
        val contours = raw.mapNotNull { clean(it) }
        return fixWinding(contours)
    }

    private class RawPoint(val x: Double, val y: Double, val onCurve: Boolean)

    /** Splits a cubic curve in halves until one quadratic curve is closer than 1 unit to it. */
    private fun cubicToQuads(
        x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double,
        out: MutableList<RawPoint>, level: Int
    ) {
        val error = sqrt(3.0) / 36 * hypot(x3 - 3 * x2 + 3 * x1 - x0, y3 - 3 * y2 + 3 * y1 - y0)
        if (error < 1.0 || level >= 10) {
            out += RawPoint((3 * (x1 + x2) - x0 - x3) / 4, (3 * (y1 + y2) - y0 - y3) / 4, false)
            out += RawPoint(x3, y3, true)
            return
        }
        val ax = (x0 + x1) / 2; val ay = (y0 + y1) / 2
        val bx = (x1 + x2) / 2; val by = (y1 + y2) / 2
        val cx = (x2 + x3) / 2; val cy = (y2 + y3) / 2
        val abx = (ax + bx) / 2; val aby = (ay + by) / 2
        val bcx = (bx + cx) / 2; val bcy = (by + cy) / 2
        val mx = (abx + bcx) / 2; val my = (aby + bcy) / 2
        cubicToQuads(x0, y0, ax, ay, abx, aby, mx, my, out, level + 1)
        cubicToQuads(mx, my, bcx, bcy, cx, cy, x3, y3, out, level + 1)
    }

    /** Rounds the points and removes what is not needed: double points, straight-line middle points, tiny contours. */
    private fun clean(raw: List<RawPoint>): TtfContour? {
        var points = raw.map { TtfPoint(it.x.roundToInt(), it.y.roundToInt(), it.onCurve) }.toMutableList()
        var changed = true
        while (changed && points.size >= 3) {
            changed = false
            val n = points.size
            for (i in 0 until n) {
                val p = points[i]
                val prev = points[(i - 1 + n) % n]
                val next = points[(i + 1) % n]
                val same = { a: TtfPoint, b: TtfPoint -> a.x == b.x && a.y == b.y }
                val drop = when {
                    // Two equal on-curve points, or an off-curve point on top of its neighbour: the curve is the same without it
                    same(p, prev) && (p.onCurve == prev.onCurve || !p.onCurve)     -> true
                    !p.onCurve && same(p, next) && next.onCurve                      -> true
                    // An on-curve point (almost) in the middle of a straight line
                    p.onCurve && prev.onCurve && next.onCurve &&
                        abs(cross(prev, p, next)) <= 0.5 * hypot((next.x - prev.x).toDouble(), (next.y - prev.y).toDouble()) &&
                        (p.x - prev.x).toLong() * (next.x - p.x) + (p.y - prev.y).toLong() * (next.y - p.y) >= 0 -> true
                    // An on-curve point (almost) half way between two off-curve points: TrueType adds it by itself
                    p.onCurve && !prev.onCurve && !next.onCurve &&
                        abs(2 * p.x - prev.x - next.x) <= 2 && abs(2 * p.y - prev.y - next.y) <= 2 -> true

                    else                                                             -> false
                }
                if (drop) {
                    points.removeAt(i)
                    changed = true
                    break
                }
            }
        }
        if (points.size < 3 || abs(signedArea(points)) < 20.0) return null
        return points
    }

    private fun cross(a: TtfPoint, b: TtfPoint, c: TtfPoint): Long =
        (b.x - a.x).toLong() * (c.y - a.y) - (b.y - a.y).toLong() * (c.x - a.x)

    /** Shoelace area over all points; positive is counter clockwise (y up). */
    fun signedArea(points: List<TtfPoint>): Double {
        var sum = 0L
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x.toLong() * b.y - b.x.toLong() * a.y
        }
        return sum / 2.0
    }

    private fun polygon(contour: TtfContour) = Path2D.Double().apply {
        moveTo(contour[0].x.toDouble(), contour[0].y.toDouble())
        for (p in contour.drop(1)) lineTo(p.x.toDouble(), p.y.toDouble())
        closePath()
    }

    /** TrueType wants outer contours clockwise and holes counter clockwise (y up). */
    private fun fixWinding(contours: List<TtfContour>): List<TtfContour> = contours.map { c ->
        val depth = contours.count { other -> other !== c && polygon(other).contains(c[0].x.toDouble(), c[0].y.toDouble()) }
        val clockwise = signedArea(c) < 0
        val wantClockwise = depth % 2 == 0
        if (clockwise == wantClockwise) c else c.reversed()
    }
}

/** Reads the few things we need from a TrueType file. */
class TtfSource(private val data: ByteArray) {

    private val tables: Map<String, Int> = (0 until u16(4)).associate { i ->
        String(data, 12 + 16 * i, 4, Charsets.US_ASCII) to u32(12 + 16 * i + 8).toInt()
    }

    fun table(tag: String): Int = tables[tag] ?: error("No $tag table")

    fun u16(pos: Int): Int = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
    fun s16(pos: Int): Int = u16(pos).toShort().toInt()
    fun u32(pos: Int): Long = (u16(pos).toLong() shl 16) or u16(pos + 2).toLong()
    fun bytes(pos: Int, length: Int): ByteArray = data.copyOfRange(pos, pos + length)

    val unitsPerEm = u16(table("head") + 18)
    private val longLoca = s16(table("head") + 50) == 1
    private val numberOfHMetrics = u16(table("hhea") + 34)

    fun advance(gid: Int): Int = u16(table("hmtx") + 4 * min(gid, numberOfHMetrics - 1))

    /** Glyph id of a character, from the Windows Unicode (3, 1) format 4 cmap. 0 when missing. */
    fun glyphId(code: Int): Int {
        val cmap = table("cmap")
        val sub = (0 until u16(cmap + 2)).map { cmap + 4 + 8 * it }
            .first { u16(it) == 3 && u16(it + 2) == 1 }
            .let { cmap + u32(it + 4).toInt() }
        require(u16(sub) == 4) { "Only cmap format 4 is supported" }
        val segments = u16(sub + 6) / 2
        val ends = sub + 14
        val starts = ends + 2 * segments + 2
        val deltas = starts + 2 * segments
        val rangeOffsets = deltas + 2 * segments
        for (i in 0 until segments) {
            if (code > u16(ends + 2 * i) || code < u16(starts + 2 * i)) continue
            val rangeOffset = u16(rangeOffsets + 2 * i)
            if (rangeOffset == 0) return (code + s16(deltas + 2 * i)) and 0xFFFF
            val glyph = u16(rangeOffsets + 2 * i + rangeOffset + 2 * (code - u16(starts + 2 * i)))
            return if (glyph == 0) 0 else (glyph + s16(deltas + 2 * i)) and 0xFFFF
        }
        return 0
    }

    private fun glyphOffset(gid: Int): Int {
        val loca = table("loca")
        return if (longLoca) u32(loca + 4 * gid).toInt() else 2 * u16(loca + 2 * gid)
    }

    /** The contours of a simple glyph (composite glyphs are not supported, the digits do not use them). */
    fun contours(gid: Int): List<TtfContour> {
        val start = glyphOffset(gid)
        if (glyphOffset(gid + 1) == start) return emptyList()
        var pos = table("glyf") + start
        val count = s16(pos)
        require(count >= 0) { "Glyph $gid is a composite glyph" }
        pos += 10
        val ends = IntArray(count) { u16(pos + 2 * it) }
        pos += 2 * count
        pos += 2 + u16(pos)
        val total = if (count == 0) 0 else ends.last() + 1
        val flags = IntArray(total)
        var i = 0
        while (i < total) {
            val flag = data[pos++].toInt() and 0xFF
            flags[i++] = flag
            if (flag and 0x08 != 0) repeat(data[pos++].toInt() and 0xFF) { flags[i++] = flag }
        }
        val xs = IntArray(total)
        val ys = IntArray(total)
        for (axis in 0..1) {
            val shortBit = if (axis == 0) 0x02 else 0x04
            val sameBit = if (axis == 0) 0x10 else 0x20
            val out = if (axis == 0) xs else ys
            var value = 0
            for (k in 0 until total) {
                val f = flags[k]
                value += when {
                    f and shortBit != 0 -> (data[pos++].toInt() and 0xFF).let { if (f and sameBit != 0) it else -it }
                    f and sameBit != 0  -> 0
                    else                -> s16(pos).also { pos += 2 }
                }
                out[k] = value
            }
        }
        var first = 0
        return ends.map { end ->
            (first..end).map { TtfPoint(xs[it], ys[it], flags[it] and 0x01 != 0) }.also { first = end + 1 }
        }
    }

    /** A string from the name table (Windows, Unicode), or null. */
    fun name(nameId: Int): String? {
        val name = table("name")
        val strings = name + u16(name + 4)
        for (i in 0 until u16(name + 2)) {
            val record = name + 6 + 12 * i
            if (u16(record) == 3 && u16(record + 2) == 1 && u16(record + 6) == nameId)
                return String(data, strings + u16(record + 10), u16(record + 8), Charsets.UTF_16BE)
        }
        return null
    }
}

/**
 * Writes a TrueType file with the tables head, hhea, maxp, OS/2, hmtx, cmap (format 4), loca, glyf, name
 * and post (format 3). The vertical metrics and the [box] are the same for every font written by one
 * writer, so fonts from it line up.
 */
class TtfWriter(private val source: TtfSource, private val codes: List<Int>, private val box: IntArray) {

    fun write(glyphs: List<TtfGlyph>, family: String, postScriptName: String): ByteArray {
        require(glyphs.size == codes.size)
        val encoded = glyphs.map { encodeGlyph(it.contours) }
        val glyf = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        for (bytes in encoded) {
            offsets += glyf.size()
            glyf.write(bytes)
            while (glyf.size() % 4 != 0) glyf.write(0)
        }
        offsets += glyf.size()
        val tables = sortedMapOf(
            "OS/2" to os2(glyphs),
            "cmap" to cmap(),
            "glyf" to glyf.toByteArray(),
            "head" to head(),
            "hhea" to hhea(glyphs),
            "hmtx" to hmtx(glyphs),
            "loca" to bytes { offsets.forEach { o -> writeInt(o) } },
            "maxp" to maxp(glyphs),
            "name" to name(family, postScriptName),
            "post" to post()
        )
        return assemble(tables)
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply(block).flush()
        return out.toByteArray()
    }

    private fun xMin(contours: List<TtfContour>) = contours.flatten().minOfOrNull { it.x } ?: 0
    private fun xMax(contours: List<TtfContour>) = contours.flatten().maxOfOrNull { it.x } ?: 0

    private fun encodeGlyph(contours: List<TtfContour>): ByteArray {
        if (contours.isEmpty()) return ByteArray(0)
        val points = contours.flatten()
        val flags = IntArray(points.size)
        val xs = ByteArrayOutputStream()
        val ys = ByteArrayOutputStream()
        var lastX = 0
        var lastY = 0
        points.forEachIndexed { i, p ->
            var flag = if (p.onCurve) 0x01 else 0
            flag = flag or delta(p.x - lastX, 0x02, 0x10, xs)
            flag = flag or delta(p.y - lastY, 0x04, 0x20, ys)
            flags[i] = flag
            lastX = p.x
            lastY = p.y
        }
        return bytes {
            writeShort(contours.size)
            writeShort(points.minOf { it.x }); writeShort(points.minOf { it.y })
            writeShort(points.maxOf { it.x }); writeShort(points.maxOf { it.y })
            var end = -1
            for (c in contours) {
                end += c.size
                writeShort(end)
            }
            writeShort(0) // no instructions
            var i = 0
            while (i < flags.size) {
                var run = 1
                while (i + run < flags.size && flags[i + run] == flags[i] && run < 256) run++
                if (run > 2) {
                    writeByte(flags[i] or 0x08)
                    writeByte(run - 1)
                    i += run
                } else {
                    writeByte(flags[i])
                    i++
                }
            }
            write(xs.toByteArray())
            write(ys.toByteArray())
        }
    }

    /** Writes one coordinate delta in the shortest form and returns its flag bits. */
    private fun delta(d: Int, shortBit: Int, sameBit: Int, out: ByteArrayOutputStream): Int = when {
        d == 0       -> sameBit
        abs(d) < 256 -> {
            out.write(abs(d))
            shortBit or (if (d > 0) sameBit else 0)
        }

        else         -> {
            out.write((d shr 8) and 0xFF)
            out.write(d and 0xFF)
            0
        }
    }

    private fun head(): ByteArray {
        val src = source.table("head")
        return bytes {
            writeInt(0x00010000)
            write(source.bytes(src + 4, 4)) // font revision
            writeInt(0) // checkSumAdjustment, set at the end
            writeInt(0x5F0F3CF5)
            writeShort(0x000B) // baseline at 0, lsb at 0, integer ppem
            writeShort(source.unitsPerEm)
            write(source.bytes(src + 20, 16)) // created, modified
            box.forEach { writeShort(it) }
            write(source.bytes(src + 44, 4)) // macStyle, lowestRecPPEM
            writeShort(2) // fontDirectionHint
            writeShort(1) // long loca
            writeShort(0)
        }
    }

    private fun hhea(glyphs: List<TtfGlyph>): ByteArray {
        val src = source.table("hhea")
        val inked = glyphs.filter { it.contours.isNotEmpty() }
        return bytes {
            writeInt(0x00010000)
            write(source.bytes(src + 4, 6)) // ascender, descender, line gap
            writeShort(glyphs.maxOf { it.advance })
            writeShort(inked.minOf { xMin(it.contours) })
            writeShort(inked.minOf { it.advance - xMax(it.contours) })
            writeShort(inked.maxOf { xMax(it.contours) })
            write(source.bytes(src + 18, 6)) // caret slope rise, run, offset
            repeat(4) { writeShort(0) }
            writeShort(0)
            writeShort(glyphs.size)
        }
    }

    private fun maxp(glyphs: List<TtfGlyph>): ByteArray = bytes {
        writeInt(0x00010000)
        writeShort(glyphs.size)
        writeShort(glyphs.maxOf { g -> g.contours.sumOf { it.size } })
        writeShort(glyphs.maxOf { it.contours.size })
        writeShort(0); writeShort(0) // composite points, contours
        writeShort(2) // zones
        repeat(8) { writeShort(0) }
    }

    private fun os2(glyphs: List<TtfGlyph>): ByteArray {
        val src = source.table("OS/2")
        val table = source.bytes(src, 96)
        val put = { pos: Int, value: Int ->
            table[pos] = (value shr 8).toByte()
            table[pos + 1] = value.toByte()
        }
        val inked = glyphs.filter { it.advance > 0 && it.contours.isNotEmpty() }
        put(2, inked.sumOf { it.advance } / inked.size) // xAvgCharWidth
        put(64, codes.filter { it > 0 }.min()) // first char
        put(66, codes.max()) // last char
        put(74, max(source.u16(src + 74), box[3])) // win ascent
        put(76, max(source.u16(src + 76), -box[1])) // win descent
        return table
    }

    private fun hmtx(glyphs: List<TtfGlyph>): ByteArray = bytes {
        for (g in glyphs) {
            writeShort(g.advance)
            writeShort(xMin(g.contours))
        }
    }

    private fun cmap(): ByteArray {
        // Runs of characters with following glyph ids share one segment
        val segments = mutableListOf<IntArray>() // start, end, delta
        codes.forEachIndexed { gid, code ->
            if (code == 0) return@forEachIndexed
            val last = segments.lastOrNull()
            if (last != null && last[1] == code - 1 && last[2] == gid - code) last[1] = code
            else segments += intArrayOf(code, code, gid - code)
        }
        segments += intArrayOf(0xFFFF, 0xFFFF, 1)
        val count = segments.size
        var power = 1
        var selector = 0
        while (power * 2 <= count) {
            power *= 2
            selector++
        }
        val subtable = bytes {
            writeShort(4)
            writeShort(16 + 8 * count)
            writeShort(0)
            writeShort(2 * count)
            writeShort(2 * power)
            writeShort(selector)
            writeShort(2 * count - 2 * power)
            segments.forEach { writeShort(it[1]) }
            writeShort(0)
            segments.forEach { writeShort(it[0]) }
            segments.forEach { writeShort(it[2] and 0xFFFF) }
            segments.forEach { _ -> writeShort(0) }
        }
        return bytes {
            writeShort(0)
            writeShort(2)
            // Unicode BMP and Windows Unicode, both point to the same subtable
            writeShort(0); writeShort(3); writeInt(20)
            writeShort(3); writeShort(1); writeInt(20)
            write(subtable)
        }
    }

    private fun name(family: String, postScriptName: String): ByteArray {
        val copyright = source.name(0) ?: "Copyright (c) 2011, Milena Brandao, with Reserved Font Name Fredoka."
        val names = sortedMapOf(
            0 to copyright,
            1 to family,
            2 to "Regular",
            3 to "$family Regular; 1.000",
            4 to family,
            5 to "Version 1.000",
            6 to postScriptName,
            10 to "Digits made from the font Fredoka One by Milena Brandao, for the AAPS Aurora Town watch face.",
            13 to "This Font Software is licensed under the SIL Open Font License, Version 1.1. " +
                "This license is available with a FAQ at: https://openfontlicense.org",
            14 to "https://openfontlicense.org"
        )
        val strings = ByteArrayOutputStream()
        val records = bytes {
            for ((id, text) in names) {
                val encoded = text.toByteArray(Charsets.UTF_16BE)
                writeShort(3); writeShort(1); writeShort(0x409); writeShort(id)
                writeShort(encoded.size)
                writeShort(strings.size())
                strings.write(encoded)
            }
        }
        return bytes {
            writeShort(0)
            writeShort(names.size)
            writeShort(6 + records.size)
            write(records)
            write(strings.toByteArray())
        }
    }

    private fun post(): ByteArray = bytes {
        writeInt(0x00030000)
        write(source.bytes(source.table("post") + 4, 28)) // italic angle, underline, fixed pitch, memory
    }

    private fun assemble(tables: Map<String, ByteArray>): ByteArray {
        val count = tables.size
        var power = 1
        var selector = 0
        while (power * 2 <= count) {
            power *= 2
            selector++
        }
        val padded = { size: Int -> (size + 3) and 3.inv() }
        var offset = 12 + 16 * count
        val file = ByteArray(offset + tables.values.sumOf { padded(it.size) })
        val header = bytes {
            writeInt(0x00010000)
            writeShort(count)
            writeShort(16 * power)
            writeShort(selector)
            writeShort(16 * count - 16 * power)
            for ((tag, table) in tables) {
                write(tag.toByteArray(Charsets.US_ASCII))
                writeInt(checksum(table).toInt())
                writeInt(offset)
                writeInt(table.size)
                table.copyInto(file, offset)
                offset += padded(table.size)
            }
        }
        header.copyInto(file, 0)
        // checkSumAdjustment in head: makes the checksum of the whole file 0xB1B0AFBA
        var headOffset = 12 + 16 * count
        for ((tag, table) in tables) {
            if (tag == "head") break
            headOffset += padded(table.size)
        }
        val adjustment = (0xB1B0AFBAL - checksum(file)) and 0xFFFFFFFFL
        for (k in 0..3) file[headOffset + 8 + k] = (adjustment shr (24 - 8 * k)).toByte()
        return file
    }

    private fun checksum(bytes: ByteArray): Long {
        var sum = 0L
        for (i in bytes.indices step 4) {
            var word = 0L
            for (k in 0..3) word = (word shl 8) or (if (i + k < bytes.size) bytes[i + k].toLong() and 0xFF else 0L)
            sum = (sum + word) and 0xFFFFFFFFL
        }
        return sum
    }
}
