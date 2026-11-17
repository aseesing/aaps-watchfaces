// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.graphics.createBitmap
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.ResFormat
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.shared.impl.weardata.JsonKeys
import app.aaps.shared.impl.weardata.ResFileMap
import app.aaps.shared.impl.weardata.ViewKeys
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import app.aaps.wear.phonepreview.DesignAccess
import app.aaps.wear.watchfaces.animation.LayerAnimator
import app.aaps.wear.watchfaces.animation.WakeAnimationSpec
import app.aaps.wear.watchfaces.animation.WakeAnimator
import app.aaps.wear.watchfaces.animation.WakeTimer
import app.aaps.wear.watchfaces.animation.allLayers
import app.aaps.wear.watchfaces.daynight.DayOverlay
import app.aaps.wear.watchfaces.daynight.MoonPicture
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * "Aurora Town": a snowy village at night under the northern lights, for Christmas. The pictures come
 * from [AuroraTownImages], the snow-capped digits from [SnowCapFont].
 *
 * - The **time** (24 hours, no seconds) at the top in Christmas red, with snow on the digits.
 * - The **glucose value** below it in the colour of its level, with snow on the digits, and its arrow.
 *   It shows in mmol/L, with the decimal sign of the watch language ("6,7" in Dutch).
 * - The **church clock** on the tower shows the time with its own small hands.
 *
 * A text view draws in one colour only, so each number is drawn twice in the same place, a coloured
 * digit and a white snow cap, in fonts with the same widths ([SnowCapFont]):
 *
 * - **Time**: the `time` view has the coloured digits. The `hour` and `minute` views, drawn after it,
 *   have the caps; the colon gets none. When the watch shows seconds, `time` is "18:24:37": the view
 *   is exactly as wide as "88:88", so ":37" breaks to a second line, and the view is one line high,
 *   so that line is cut off. The digits are tabular, so every time has the width of "88:88".
 * - **Glucose**: the `sgv` view is drawn after every free text, so here the cap lies below: `freetext1`
 *   has the white cap (the value worked out from mg/dL, with the decimal sign of the watch), and `sgv`
 *   above it has the digits with the snow cut out ([SnowCapFont.cutFont]), in the level colour.
 *
 * A soft dark shade lies behind the texts (the picture of `cover_chart`, the only picture view drawn
 * below the free texts), so they can be read on the bright aurora.
 *
 * Two designs share all of this (see [animated]):
 * - [CwfAuroraTownDesignTest] (`Aurora_Town.zip`) for this branch: the aurora waves, the lights blink
 *   and snow falls while the wake animation plays; the aurora and the lights stay on afterwards. The
 *   watch draws the moon in today's phase.
 * - [CwfAuroraTownStockDesignTest] (`Aurora_Town_Stock.zip`) for plain AAPS: the aurora, the moon and
 *   the lights are in the background picture, and the snow is the second hand, so it moves once a
 *   second. Only what plain AAPS knows is in the json.
 */
abstract class AuroraTownDesignBase : CwfDesignBase() {

    /** True for the animated design of this branch, false for the one of plain AAPS. */
    protected abstract val animated: Boolean

    /** The pictures, made once for all tests of a class: making them takes a few seconds. */
    private val pictures: AuroraTownImages get() = PICTURES

    private val baseFace by lazy { typeface(SnowCapFont.baseFont(), BASE_FONT) }
    private val capFace by lazy { typeface(SnowCapFont.capFont(), CAP_FONT) }
    private val cutFace by lazy { typeface(SnowCapFont.cutFont(), CUT_FONT) }

    private fun typeface(bytes: ByteArray, name: String): Typeface {
        val file = File.createTempFile(name, ".ttf").apply { deleteOnExit() }
        file.writeBytes(bytes)
        return Typeface.Builder(file).build()
    }

    override fun fonts(): Map<String, ByteArray> =
        mapOf(BASE_FONT to SnowCapFont.baseFont(), CAP_FONT to SnowCapFont.capFont(), CUT_FONT to SnowCapFont.cutFont())

    // ------------------------------------------------------------------------------------------
    // The layout, worked out from the fonts
    // ------------------------------------------------------------------------------------------

    /** The advance of [char] at [size] in pixels, as Android draws it: rounded to whole pixels. */
    protected fun advance(char: Char, size: Int): Int = (SnowCapFont.advance(char) * size.toFloat() / SnowCapFont.unitsPerEm).roundToInt()

    /**
     * One line of digits at [size], laid out by a text view as the face has them. Not worked out from
     * the font metrics: a text view also makes room for the ascent and descent of the font, which here
     * reach further than the font box.
     */
    private fun lineLayout(size: Int, text: String = "0"): Layout {
        val view = TextView(RuntimeEnvironment.getApplication())
        view.typeface = baseFace
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size.toFloat())
        view.text = text
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view.layout
    }

    /** The room a text view gives one line at [size] above its baseline. */
    private fun lineAbove(size: Int): Int = lineLayout(size).getLineBaseline(0)

    /** The height of one line at [size], as a text view lays it out. */
    private fun lineHeight(size: Int): Int = lineLayout(size).getLineBottom(0)

    /**
     * The height of the first of two lines at [size]: a little less than one line alone, which also has
     * the padding below. The time view is this high, so not one pixel of a second line shows.
     */
    private fun firstOfTwoLines(size: Int): Int = lineLayout(size, "0\n0").getLineBottom(0)

    /**
     * The top of a text view at [size] whose first line is at its top (gravity left or right) so that
     * the middle of its digits lies at [middle].
     */
    private fun topFor(middle: Float, size: Int): Int {
        val paint = Paint().apply { textSize = size.toFloat(); typeface = baseFace }
        val glyphs = Rect().also { paint.getTextBounds("0", 0, 1, it) }
        return (middle - lineAbove(size) - (glyphs.top + glyphs.bottom) / 2f).roundToInt()
    }

    /** The box of the time digits "88:88" (left, top, width, height): exactly as wide as the text, one line high. */
    protected fun timeBox(): Rect {
        val width = 4 * advance('0', TIME_SIZE) + advance(':', TIME_SIZE)
        val left = 200 - width / 2
        val top = topFor(TIME_MIDDLE, TIME_SIZE)
        return Rect(left, top, left + width, top + firstOfTwoLines(TIME_SIZE))
    }

    /** The box of the glucose value and its cap: the value ends at [SGV_RIGHT], one line high. */
    protected fun sgvBox(): Rect {
        val top = topFor(BG_MIDDLE, SGV_SIZE)
        return Rect(SGV_LEFT, top, SGV_RIGHT, top + lineHeight(SGV_SIZE))
    }

    protected fun arrowBox(): Rect {
        val top = (BG_MIDDLE - ARROW_SIZE / 2f).roundToInt()
        return Rect(SGV_RIGHT + ARROW_GAP, top, SGV_RIGHT + ARROW_GAP + ARROW_SIZE, top + ARROW_SIZE)
    }

    /** The square of a clock hand, its middle as near as whole pixels allow to the middle of the church clock. */
    protected fun handBox(): Rect {
        val left = (AuroraTownImages.CLOCK_X - HAND_BOX / 2f).roundToInt()
        val top = (AuroraTownImages.CLOCK_Y - HAND_BOX / 2f).roundToInt()
        return Rect(left, top, left + HAND_BOX, top + HAND_BOX)
    }

    private fun JSONObject.place(key: String, r: Rect): JSONObject = place(key, r.left, r.top, r.width(), r.height())

    private fun JSONObject.text(key: String, r: Rect, size: Int, gravity: String, font: String, color: String): JSONObject =
        place(key, r).put("textsize", size).put("gravity", gravity).put("font", font).put("fontStyle", "normal").put("fontColor", color)

    // ------------------------------------------------------------------------------------------
    // The design json
    // ------------------------------------------------------------------------------------------

    override fun design(default: CwfData): JSONObject = JSONObject(default.json).apply {
        getJSONObject("metadata")
            .put(CwfMetadataKey.CWF_NAME.key, displayName)
            .put(CwfMetadataKey.CWF_FILENAME.key, fileName)
            .put(CwfMetadataKey.CWF_AUTHOR.key, "AAPS (local)")
            .put(CwfMetadataKey.CWF_CREATED_AT.key, "17/11/26")
            .put(
                CwfMetadataKey.CWF_COMMENT.key,
                if (animated) "A snowy village under the northern lights. The aurora waves, the lights blink and snow falls when you raise your wrist. 24 hour time"
                else "A snowy village under the northern lights, with snow that moves every second. 24 hour time"
            )
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_BG.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DIRECTION.key, "true")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_SECONDS.key, (!animated).toString())
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_DATE.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION1.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION2.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION3.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION4.key, "false")
            .put(CwfMetadataKey.CWF_PREF_WATCH_SHOW_COMPLICATION5.key, "false")

        // The stock design moves its snow (the second hand) every second; the animated one draws once a minute
        put("enableSecond", !animated)
        put("highColor", HIGH_COLOR).put("midColor", MID_COLOR).put("lowColor", LOW_COLOR)

        // Every view this design does not use is off
        val used = mutableSetOf("background", "time", "hour", "minute", "sgv", CAP_VIEW, "direction", "hour_hand", "minute_hand", SHADE_VIEW)
        if (animated) used += MOON_VIEW else used += "second_hand"
        keys().asSequence().toList().forEach { key ->
            val view = optJSONObject(key) ?: return@forEach
            if (view.has("visibility") && key !in used) view.put("visibility", "gone")
        }
        put("background", JSONObject().put("width", 400).put("height", 400).put("topmargin", 0).put("leftmargin", 0).put("visibility", "visible"))

        // A soft dark shade behind the texts, so they can be read on the bright aurora. The cover of the
        // graph is the only picture view drawn below the free texts (the snow of the glucose value).
        place(SHADE_VIEW, SHADE_BOX)

        // The time: the red digits, then the snow on the hours and on the minutes
        val time = timeBox()
        val digit = advance('0', TIME_SIZE)
        val colon = advance(':', TIME_SIZE)
        text("time", Rect(time.left, time.top, time.right + TIME_SLACK, time.bottom), TIME_SIZE, "left", BASE_FONT, TIME_COLOR)
        val hoursRight = time.left + 2 * digit
        text("hour", Rect(hoursRight - 2 * digit - CAP_ROOM, time.top, hoursRight, time.bottom), TIME_SIZE, "right", CAP_FONT, SNOW_COLOR)
        val minutesLeft = hoursRight + colon
        text("minute", Rect(minutesLeft, time.top, minutesLeft + 2 * digit + CAP_ROOM, time.bottom), TIME_SIZE, "left", CAP_FONT, SNOW_COLOR)

        // The glucose value: the cap below (worked out from mg/dL on the watch), the cut digits above in the level colour
        val sgv = sgvBox()
        text(CAP_VIEW, sgv, SGV_SIZE, "right", CAP_FONT, SNOW_COLOR).put("dynValue", true).put("textvalue", MMOL_FORMAT).put("dynData", MMOL_DYN)
        text("sgv", sgv, SGV_SIZE, "right", CUT_FONT, MID_COLOR)
        place("direction", arrowBox())

        // The church clock
        place("hour_hand", handBox())
        place("minute_hand", handBox())
        if (animated) {
            // The watch draws the moon itself, lit as it is in the sky tonight (see MoonPicture)
            val half = AuroraTownImages.MOON_SIZE / 2f
            place(MOON_VIEW, (AuroraTownImages.MOON_X - half).roundToInt(), (AuroraTownImages.MOON_Y - half).roundToInt(), AuroraTownImages.MOON_SIZE.roundToInt())
                .put("textvalue", "").put(MoonPicture.JSON_KEY, true)
            put(WakeAnimationSpec.JSON_KEY, animationJson())
        } else {
            place("second_hand", 0, 0, 400)
        }

        (optJSONObject("dynData") ?: JSONObject().also { put("dynData", it) }).put(MMOL_DYN, mmolDynData())
    }

    /**
     * The glucose value in mmol/L from the mg/dL the watch gets: 0 to 1800 mg/dL is 0 to 100 mmol/L, so
     * the value is mg/dL / 18. Formatted by the watch, with its decimal sign. Empty without a value.
     */
    private fun mmolDynData(): JSONObject = JSONObject().put("valueKey", ViewKeys.SGV.key).put("minData", 0).put("maxData", 1800)
        .put("dynValue", JSONObject().put("minValue", 0).put("maxValue", 100))
        .put("textvalue1", MMOL_FORMAT).put("invalidTextvalue", "")

    /** The wake animation: the aurora waves, the lights twinkle and chase, snow blows from left to right. */
    private fun animationJson(): JSONObject {
        val layers = JSONArray()
        // Two curtains of northern lights, only in the sky. Their motion is scaled by env, so they lie
        // still at rest, the same as at the start and the end of a wake.
        layers.put(
            auroraLayer(
                AURORA, alpha = "0.85 + env*0.15*(noise(u*2.5 - t*0.2)*2-1)",
                x = "env*sin(t*0.25)*10", y = "env*(sin(u*11 - t*1.3)*5 + (noise(u*3, t*0.3)-0.5)*12)", scaleY = "1 + env*0.06*sin(t*0.4)"
            )
        )
        layers.put(
            auroraLayer(
                AURORA_MIRRORED, alpha = "0.4 + env*0.12*(noise(u*3.1 + t*0.25)*2-1)",
                x = "-env*sin(t*0.21 + 1.3)*12", y = "env*(sin(u*8 + t*1.05 + 2)*6 + (noise(u*2.2 + 5, t*0.35)-0.5)*10)",
                scaleY = "1 + env*0.05*sin(t*0.33 + 1)"
            )
        )
        // The lights on the eaves: a gentle twinkle while it plays, steady at rest
        layers.put(lightsLayer(AuroraTownImages.EAVE_LIGHTS, EAVE_COLOR, TWINKLE))
        // The tree: one layer per colour; the warm white ones twinkle, the coloured ones chase each other
        val groups = AuroraTownImages.TREE_LIGHTS.indices.groupBy { AuroraTownImages.treeLightColor(it) }
        var chase = 0
        for ((color, indices) in groups) {
            val warm = color == WARM_WHITE
            val alpha = if (warm) TWINKLE else "if(env > 0.01, mix(1, step(0.5, fract(t*0.7 + ${chase++}*0.25)), env), 1)"
            layers.put(lightsLayer(indices.map { AuroraTownImages.TREE_LIGHTS[it] }, hex(color), alpha))
        }
        // Snow, only while it plays: soft flakes and small crystals blown from left to right, and one big crystal
        layers.put(
            JSONObject().put("shape", "dot").put("colors", JSONArray().put(SNOW_COLOR)).put("slot", "front")
                .put("count", FLAKE_COUNT).put("seed", 5)
                .put("x", "mod(r1*480 + t*(22+r2*20) + sin(t*1.1+r1*7)*6, 480) - 40")
                .put("y", "mod(r3*440 + t*(26+r4*24), 440) - 20")
                .put("radius", "1.6 + r5*1.8").put("alpha", "env*(0.6+0.4*r6)")
        )
        layers.put(
            JSONObject().put("image", CRYSTAL_SMALL).put("rect", rect(0f, 0f, SMALL_CRYSTAL, SMALL_CRYSTAL)).put("slot", "front")
                .put("count", SNOW_COUNT).put("seed", 3)
                .put("x", "mod(r1*480 + t*(28+r2*22) + sin(t*1.3+r1*9)*8, 480) - 40")
                .put("y", "mod(r3*440 + t*(35+r4*30), 440) - 20")
                .put("scale", "0.5 + r5*0.9").put("rotation", "t*(r6*120-60)").put("alpha", "env*(0.55+0.45*r7)").put("maxScale", 1.4)
        )
        layers.put(
            JSONObject().put("image", CRYSTAL_BIG).put("rect", rect(0f, 0f, BIG_CRYSTAL, BIG_CRYSTAL)).put("slot", "front")
                .put("count", 1).put("seed", 7)
                .put("x", "mod(40 + t*15 + sin(t*0.8)*12, 480) - 40")
                .put("y", "mod(20 + t*19, 440) - 20")
                .put("rotation", "t*14").put("alpha", "env*0.95")
        )
        return JSONObject().put("durationMs", ANIMATION_MS).put("fps", 24).put("fadeMs", FADE_MS).put("layers", layers)
    }

    private fun auroraLayer(image: String, alpha: String, x: String, y: String, scaleY: String): JSONObject = JSONObject()
        .put("image", image).put("rect", rect(0f, 0f, 400f, 400f)).put("slot", "back").put("blend", "screen")
        .put("slices", AURORA_SLICES).put("clip", JSONObject().put("mask", SKY_MASK)).put("rest", 0)
        .put("x", x).put("y", y).put("scaleY", scaleY).put("pivotY", AURORA_BOTTOM).put("alpha", alpha)

    private fun lightsLayer(points: List<Pair<Float, Float>>, color: String, alpha: String): JSONObject = JSONObject()
        .put("shape", "dot").put("colors", JSONArray().put(color)).put("slot", "back").put("blend", "add").put("rest", 0)
        .put("points", JSONArray().apply { points.forEach { (x, y) -> put(JSONArray().put(round1(x)).put(round1(y))) } })
        .put("x", "px").put("y", "py").put("radius", LIGHT_RADIUS).put("alpha", alpha)

    private fun rect(left: Float, top: Float, width: Float, height: Float) = JSONObject().put("left", left).put("top", top).put("width", width).put("height", height)

    /** One decimal is enough for a light, and keeps the json short. */
    private fun round1(v: Float): Double = (v * 10f).roundToInt() / 10.0

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    // ------------------------------------------------------------------------------------------
    // The pictures
    // ------------------------------------------------------------------------------------------

    override fun images(): Map<String, ByteArray> {
        val common = mapOf(
            ResFileMap.HOUR_HAND.fileName to pictures.hourHand(),
            ResFileMap.MINUTE_HAND.fileName to pictures.minuteHand(),
            ResFileMap.COVER_CHART.fileName to shadePicture()
        ) + arrowImages()
        return if (animated) common + mapOf(
            ResFileMap.BACKGROUND.fileName to pictures.animatedBackground(),
            AURORA to pictures.aurora(),
            AURORA_MIRRORED to pictures.auroraMirrored(),
            SKY_MASK to pictures.skyMask(),
            CRYSTAL_SMALL to pictures.crystalSmall(),
            CRYSTAL_BIG to pictures.crystalBig()
        ) else common + mapOf(
            ResFileMap.BACKGROUND.fileName to pictures.stockBackground(),
            ResFileMap.SECOND_HAND.fileName to pictures.secondHandSnow()
        )
    }

    /**
     * The shade behind the texts: black, darkest in the middle ([SHADE_ALPHA]) and clear at the edge of
     * its oval. Small (a quarter of the design size): the watch scales it up, and a soft picture stays soft.
     */
    private fun shadePicture(): ByteArray {
        val w = SHADE_BOX.width() / 2
        val h = SHADE_BOX.height() / 2
        val image = createBitmap(w, h)
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x + 0.5f - w / 2f) / (w / 2f)
            val dy = (y + 0.5f - h / 2f) / (h / 2f)
            val d = sqrt(dx * dx + dy * dy)
            val u = ((1f - d) / (1f - SHADE_CORE)).coerceIn(0f, 1f)
            val a = (SHADE_ALPHA * u * u * (3 - 2 * u)).roundToInt()
            pixels[y * w + x] = Color.argb(a, 0, 0, 0)
        }
        image.setPixels(pixels, 0, w, 0, 0, w, h)
        return pngBytes(image)
    }

    /**
     * The arrows of the glucose value: white with a soft dark edge, so they can be read on the aurora.
     * The watch colours the white like the value; the dark edge stays dark.
     */
    private fun arrowImages(): Map<String, ByteArray> = mapOf(
        ResFileMap.ARROW_NONE.fileName to blankPng(),
        ResFileMap.ARROW_DOUBLE_UP.fileName to arrow(-90f, 2),
        ResFileMap.ARROW_SINGLE_UP.fileName to arrow(-90f, 1),
        ResFileMap.ARROW_FORTY_FIVE_UP.fileName to arrow(-45f, 1),
        ResFileMap.ARROW_FLAT.fileName to arrow(0f, 1),
        ResFileMap.ARROW_FORTY_FIVE_DOWN.fileName to arrow(45f, 1),
        ResFileMap.ARROW_SINGLE_DOWN.fileName to arrow(90f, 1),
        ResFileMap.ARROW_DOUBLE_DOWN.fileName to arrow(90f, 2)
    )

    private fun arrow(angle: Float, heads: Int): ByteArray {
        val size = (ARROW_SIZE * SCALE).toInt()
        val k = size / 100f
        val image = createBitmap(size, size).apply { eraseColor(Color.TRANSPARENT) }
        val canvas = Canvas(image)
        canvas.rotate(angle, size / 2f, size / 2f)
        val shape = Path().apply {
            for (h in 0 until heads) {
                val tip = 88f - h * 22f
                moveTo(tip * k, 50f * k)
                lineTo((tip - 30f) * k, 31f * k)
                lineTo((tip - 23f) * k, 50f * k)
                lineTo((tip - 30f) * k, 69f * k)
                close()
            }
        }
        fun draw(color: Int, extra: Float) {
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; strokeWidth = (11f + extra) * k; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
            }
            canvas.drawLine(16f * k, 50f * k, 70f * k, 50f * k, line)
            canvas.drawPath(shape, line.apply { strokeWidth = (2f + extra) * k })
            canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        // The soft edge: wider strokes, more see-through the further out
        draw(Color.argb(40, 0, 0, 0), 12f)
        draw(Color.argb(60, 0, 0, 0), 8f)
        draw(Color.argb(90, 0, 0, 0), 4f)
        draw(Color.WHITE, 0f)
        return pngBytes(image)
    }

    // ------------------------------------------------------------------------------------------
    // Rendering helpers of the tests
    // ------------------------------------------------------------------------------------------

    private val default by lazy { defaultDesign() }

    /** The locale the watch formats with in these tests. */
    private val locale: Locale get() = RuntimeEnvironment.getApplication().resources.configuration.locales[0]

    /** A glucose sample of [mgdl], its text as the phone sends it, in the decimal sign of the test locale. */
    protected fun sample(mgdl: Double = 120.0, level: Long = 0, arrow: String = "→"): Sample =
        Sample(text = String.format(locale, "%.1f", mgdl / MGDL_PER_MMOL), arrow = arrow, mgdl = mgdl, level = level)

    protected fun at(hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime.of(2026, 12, 24, hour, minute, second).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** A face that shows the design at [instant] with [sample], drawn once, so its views are laid out. */
    private fun face(instant: Long, sample: Sample = sample(), change: (JSONObject) -> Unit = {}): Pair<CustomWatchface, Bitmap> {
        val design = design(default).also(change)
        val face = storedFace(design, default, images(), instant)
        // A face for a picture fills the hour and minute views with the time of now, not with the time
        // it is drawn for: here every time text is for [instant], as on the watch at that moment
        val real = face.dateUtil
        face.dateUtil = object : DateUtil by real {
            override fun now(): Long = instant
            override fun timeString(): String = real.timeString(instant)
            override fun hourString(): String = real.hourString(instant)
            override fun minuteString(): String = real.minuteString(instant)
            override fun secondString(): String = real.secondString(instant)
        }
        storeSampleData(face, sample)
        Thread.sleep(600)   // past the render data share window
        face.refreshRenderData()
        return face to face.renderToBitmap(400, 400)
    }

    /** The design without the snow of the second hand (stock), which would lie over the digits by chance. */
    private fun withoutSnow(design: JSONObject) {
        design.optJSONObject("second_hand")?.put("visibility", "gone")
    }

    /** Where the ink of [text] at [size] lies, digits and snow, in a view whose first line is at [top] and ends at [right]. */
    private fun inkRect(text: String, size: Int, right: Int, top: Int): Rect {
        val result = Rect()
        for (typeface in listOf(baseFace, capFace)) {
            val paint = Paint().apply { textSize = size.toFloat(); this.typeface = typeface }
            val r = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
            r.offset((right - paint.measureText(text)).roundToInt(), top + lineAbove(size))
            result.union(r)
        }
        return result
    }

    private fun CustomWatchface.textOf(id: Int): TextView = layoutView!!.findViewById(id)

    /**
     * The frames of the wake animation at [atMs] (in order), drawn by the views of a sample face and a
     * [WakeAnimator] with a test clock; null where the face is drawn the normal way again.
     */
    private fun wakeFrames(atMs: List<Long>, instant: Long = PREVIEW_MS): Map<Long, Bitmap?> {
        val design = design(default)
        val (face, _) = face(instant)
        var clock = 0L
        val animator = WakeAnimator(heartRate = { 72 }, timer = WakeTimer { clock })
        animator.setDesign(readAnimation(design))
        return atMs.associateWith { ms ->
            clock = ms
            val frame = createBitmap(400, 400).apply { eraseColor(Color.BLACK) }
            if (animator.drawFrame(Canvas(frame), 400, 400, instant, 0, emptyList(), DesignAccess.wakeParts(face), allowed = true, ready = true)) frame else null
        }
    }

    /** The middle (x) of the pixels in [r] of [bitmap] that [match]; null when there are none. */
    private fun middleX(bitmap: Bitmap, r: Rect, match: (Int) -> Boolean): Float? {
        var sum = 0.0
        var n = 0
        for (y in r.top until r.bottom) for (x in r.left until r.right)
            if (x in 0 until bitmap.width && y in 0 until bitmap.height && match(bitmap.getPixel(x, y))) {
                sum += x
                n++
            }
        return if (n < MIN_INK) null else (sum / n).toFloat()
    }

    private fun near(pixel: Int, hex: String, tolerance: Int = 60): Boolean {
        val c = Color.parseColor(hex)
        return abs(Color.red(pixel) - Color.red(c)) <= tolerance && abs(Color.green(pixel) - Color.green(c)) <= tolerance && abs(Color.blue(pixel) - Color.blue(c)) <= tolerance
    }

    private fun isSnow(pixel: Int) = Color.red(pixel) > 225 && Color.green(pixel) > 225 && Color.blue(pixel) > 225

    private fun count(bitmap: Bitmap, r: Rect, match: (Int) -> Boolean): Int {
        var n = 0
        for (y in r.top until r.bottom) for (x in r.left until r.right)
            if (x in 0 until bitmap.width && y in 0 until bitmap.height && match(bitmap.getPixel(x, y))) n++
        return n
    }

    /**
     * For each character of [text] at [size] in [typeface], drawn alone on clear: the middle (x) of its
     * ink in its own cell, from the start of the text. Null for a cell without ink.
     */
    private fun inkMiddles(text: String, size: Int, typeface: Typeface): List<Float?> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size.toFloat(); this.typeface = typeface; color = Color.WHITE }
        val bitmap = createBitmap((paint.measureText(text) + 2 * size).toInt(), 2 * size)
        Canvas(bitmap).drawText(text, size.toFloat(), size * 1.4f, paint)
        var x = size
        return text.map { char ->
            val width = advance(char, size)
            val middle = middleX(bitmap, Rect(x, 0, x + width, bitmap.height)) { Color.alpha(it) > INK_ALPHA }?.minus(size)
            x += width
            middle
        }
    }

    /**
     * Checks that the digits of [text] (drawn from [left], in [color]) and the snow on them lie where the
     * fonts put them: per digit, the middle of the coloured ink and of the white ink are where they are
     * when the cut digit and the cap are drawn on their own. So both views draw the same text at the
     * same place, and the snow sits on its digit.
     */
    private fun assertCapsOnDigits(name: String, picture: Bitmap, text: String, left: Int, top: Int, bottom: Int, size: Int, color: String) {
        val digits = inkMiddles(text, size, cutFace)
        val caps = inkMiddles(text, size, capFace)
        var x = left
        for ((index, char) in text.withIndex()) {
            val width = advance(char, size)
            if (char.isDigit()) {
                val cell = Rect(x, top, x + width, bottom)
                val digit = middleX(picture, cell) { near(it, color) }
                val snow = middleX(picture, cell) { isSnow(it) }
                assertWithMessage("$name: digit $char").that(digit).isNotNull()
                assertWithMessage("$name: snow on $char").that(snow).isNotNull()
                assertWithMessage("$name: digit $char at ${digit!! - left}, font ${digits[index]}").that(digit - left).isWithin(CAP_TOLERANCE).of(digits[index]!!)
                assertWithMessage("$name: snow on $char at ${snow!! - left}, font ${caps[index]}").that(snow - left).isWithin(CAP_TOLERANCE).of(caps[index]!!)
            }
            x += width
        }
    }

    // ------------------------------------------------------------------------------------------
    // The tests
    // ------------------------------------------------------------------------------------------

    @Test
    fun buildsTheZipAndItsPreview() {
        val design = design(default)
        val json = design.toString(4)
        val images = images()
        // A wake always starts with the frame at 0 ms
        val previewImage = if (animated) wakeFrames(listOf(0L, PREVIEW_WAKE_MS)).getValue(PREVIEW_WAKE_MS)!! else preview(design, default, images, PREVIEW_MS)
        val jpg = ByteArrayOutputStream().also { previewImage.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, it) }.toByteArray()
        val zip = zip(json, jpg, images)
        File(out, "$fileName.zip").writeBytes(zip)
        File(out, "$fileName.png").writeBytes(pngBytes(previewImage))
        println("$fileName.zip: ${zip.size} bytes (preview ${jpg.size}, json ${json.length})")
        assertThat(zip.size).isAtMost(MAX_ZIP_BYTES)
        for (name in images.keys) assertWithMessage(name).that(name.endsWith(DayOverlay.IMAGE_SUFFIX)).isFalse()

        val loaded = ZipWatchfaceFormat.loadCustomWatchface(zip, "$fileName.zip", authorization = true)
        assertThat(loaded).isNotNull()
        val metadata = loaded!!.cwfData.metadata
        assertThat(metadata[CwfMetadataKey.CWF_NAME]).isEqualTo(displayName)
        assertThat(metadata[CwfMetadataKey.CWF_FILENAME]).isEqualTo(fileName)
        assertThat(metadata[CwfMetadataKey.CWF_PREF_WATCH_SHOW_SECONDS]).isEqualTo((!animated).toString())
        // The three fonts are in the zip, and the texts use them
        for (font in listOf(BASE_FONT, CAP_FONT, CUT_FONT)) assertWithMessage(font).that(loaded.cwfData.resData[font]?.format).isEqualTo(ResFormat.TTF)
        assertThat(design.getJSONObject("time").getString("font")).isEqualTo(BASE_FONT)
        for (key in listOf("hour", "minute", CAP_VIEW)) assertWithMessage(key).that(design.getJSONObject(key).getString("font")).isEqualTo(CAP_FONT)
        assertThat(design.getJSONObject("sgv").getString("font")).isEqualTo(CUT_FONT)
        // Every picture is in the zip
        assertThat(loaded.cwfData.resData.keys).containsAtLeastElementsIn(images.keys)

        // The copy kept in the repository is this design
        val kept = File("cwf-designs/$fileName.zip")
        assertWithMessage("$kept").that(kept.exists()).isTrue()
        assertThat(JSONObject(jsonInZip(kept.readBytes())).toString(4)).isEqualTo(json)
    }

    @Test
    fun theStockDesignOnlyUsesWhatPlainAapsKnows() {
        assumeTrue(!animated)
        val design = design(default)
        val text = design.toString()
        assertThat(design.has(WakeAnimationSpec.JSON_KEY)).isFalse()
        assertThat(text).doesNotContain(MoonPicture.JSON_KEY)
        assertThat(design.has(DayOverlay.WEATHER_KEY)).isFalse()
        assertThat(design.has(DayOverlay.JSON_KEY)).isFalse()
        val views = ViewKeys.entries.map { it.key }.toSet()
        val keys = JsonKeys.entries.map { it.key }.toSet()
        for (key in design.keys()) {
            val value = design.get(key)
            if (key == "metadata" || key == "dynData") continue
            if (key in views) {
                for (property in (value as JSONObject).keys()) assertWithMessage("$key.$property").that(keys).contains(property)
            } else assertWithMessage("key $key").that(keys).contains(key)
        }
        for (entry in design.getJSONObject("dynData").keys()) {
            val dyn = design.getJSONObject("dynData").getJSONObject(entry)
            for (property in dyn.keys()) assertWithMessage("dynData $entry.$property").that(keys.any { property.startsWith(it) }).isTrue()
        }
        // The snow moves as the second hand, so the watch must draw every second
        assertThat(design.getBoolean("enableSecond")).isTrue()
        assertThat(design.getJSONObject("second_hand").getString("visibility")).isEqualTo("visible")
    }

    @Test
    fun theSnowCapsSitOnTheDigitsOfTheTime() {
        val time = timeBox()
        val pictures = mutableListOf<Pair<String, Bitmap>>()
        for ((h, m, s) in listOf(Triple(8, 5, 12), Triple(18, 24, 37), Triple(23, 59, 59), Triple(0, 0, 0))) {
            val (face, picture) = face(at(h, m, s), change = ::withoutSnow)
            val expected = String.format(Locale.ROOT, "%02d:%02d", h, m)
            pictures += expected to picture
            // The views show what the layout counts on
            val timeView = face.textOf(R.id.time)
            assertWithMessage("time text").that(timeView.text.toString()).startsWith(expected)
            assertThat(face.textOf(R.id.hour).text.toString()).isEqualTo(expected.substring(0, 2))
            assertThat(face.textOf(R.id.minute).text.toString()).isEqualTo(expected.substring(3, 5))
            // With seconds the time breaks after the minutes: the first line is the time without seconds
            val layout = timeView.layout
            assertWithMessage("first line of ${timeView.text}").that(timeView.text.subSequence(0, layout.getLineEnd(0)).toString()).isEqualTo(expected)
            if (!animated) assertWithMessage("seconds on a second line").that(layout.lineCount).isEqualTo(2)
            // The view is one line high: the first line shows whole (only its empty padding below may be
            // cut), the second not at all
            assertWithMessage("first line bottom").that(layout.getLineBottom(0)).isIn(timeView.height..timeView.height + 1)
            assertWithMessage("first line ink").that(timeView.baseline + layout.paint.fontMetricsInt.bottom).isAtMost(timeView.height)
            assertWithMessage("baseline").that(timeView.baseline).isEqualTo(lineAbove(TIME_SIZE))
            assertCapsOnDigits(expected, picture, expected, time.left, time.top, time.bottom, TIME_SIZE, TIME_COLOR)
            // Nothing of the seconds shows below the time
            val below = Rect(time.left - 10, time.bottom, time.right + 40, time.bottom + time.height())
            assertWithMessage("$expected: red below the time").that(count(picture, below) { near(it, TIME_COLOR, 50) }).isEqualTo(0)
            // And nothing right of the minutes
            val right = Rect(time.right + 2, time.top, time.right + 60, time.bottom)
            assertWithMessage("$expected: red right of the time").that(count(picture, right) { near(it, TIME_COLOR, 50) }).isEqualTo(0)
        }
        sheet(pictures, "${fileName}_times.png")
        // The church clock, five times bigger
        val clock = handBox().let { Rect(it.left - 12, it.top - 12, it.right + 12, it.bottom + 12) }
        sheet(pictures.map { (name, picture) -> name to Bitmap.createBitmap(picture, clock.left, clock.top, clock.width(), clock.height()) }, "${fileName}_clock.png")
        // The time view is as wide as the time, and too narrow for one more character
        assertThat(TIME_SLACK).isLessThan(advance(':', TIME_SIZE))
        for (digit in "0123456789") assertThat(advance(digit, TIME_SIZE)).isEqualTo(advance('0', TIME_SIZE))
    }

    @Test
    fun theGlucoseCapIsTheValueInMmol() {
        val sgv = sgvBox()
        val pictures = mutableListOf<Pair<String, Bitmap>>()
        for ((mgdl, level, color) in listOf(Triple(120.0, 0L, MID_COLOR), Triple(65.0, -1L, LOW_COLOR), Triple(187.0, 1L, HIGH_COLOR), Triple(400.0, 1L, HIGH_COLOR))) {
            val sample = sample(mgdl, level)
            val (face, picture) = face(PREVIEW_MS, sample, ::withoutSnow)
            pictures += sample.text to picture
            val cap = face.textOf(R.id.freetext1).text.toString()
            val value = face.textOf(R.id.sgv).text.toString()
            assertWithMessage("cap of $mgdl").that(cap).isEqualTo(sample.text)
            assertWithMessage("value of $mgdl").that(value).isEqualTo(sample.text)
            // Both views have the whole line, so nothing of the value or its snow is cut
            for (id in listOf(R.id.sgv, R.id.freetext1)) {
                val view = face.textOf(id)
                assertWithMessage("line of $value").that(view.layout.getLineBottom(0)).isEqualTo(view.height)
                assertWithMessage("baseline of $value").that(view.baseline).isEqualTo(lineAbove(SGV_SIZE))
            }
            val paint = Paint().apply { textSize = SGV_SIZE.toFloat(); typeface = baseFace }
            val left = sgv.right - paint.measureText(value).roundToInt()
            assertWithMessage("$value fits").that(left).isAtLeast(sgv.left)
            assertCapsOnDigits(value, picture, value, left, sgv.top, sgv.bottom, SGV_SIZE, color)
        }
        sheet(pictures, "${fileName}_glucose.png")
        // The decimal sign is the one of the watch: "6,7" in Dutch
        assertThat(String.format(Locale.forLanguageTag("nl-NL"), MMOL_FORMAT, 120.0 * 100 / 1800)).isEqualTo("6,7")
        // Without a value the cap is empty
        val (face, _) = face(PREVIEW_MS, Sample(text = "---", arrow = "--", mgdl = 0.0, level = 0))
        assertThat(face.textOf(R.id.freetext1).text.toString()).isEmpty()
        storeSampleData(newFace())
    }

    @Test
    @Config(qualifiers = "nl-rNL")
    fun theGlucoseCapHasTheDecimalCommaInDutch() {
        assertThat(locale.language).isEqualTo("nl")
        val sample = sample(120.0)
        assertThat(sample.text).isEqualTo("6,7")
        val (face, picture) = face(PREVIEW_MS, sample, ::withoutSnow)
        assertThat(face.textOf(R.id.freetext1).text.toString()).isEqualTo("6,7")
        val sgv = sgvBox()
        val left = sgv.right - Paint().apply { textSize = SGV_SIZE.toFloat(); typeface = baseFace }.measureText("6,7").roundToInt()
        assertCapsOnDigits("6,7", picture, "6,7", left, sgv.top, sgv.bottom, SGV_SIZE, MID_COLOR)
        // The value and its arrow, four times bigger
        val crop = Bitmap.createBitmap(picture, sgv.left, sgv.top, arrowBox().right + 4 - sgv.left, sgv.height())
        File(out, "${fileName}_dutch.png").writeBytes(pngBytes(Bitmap.createScaledBitmap(crop, crop.width * 4, crop.height * 4, false)))
    }

    @Test
    fun theTextsStayInTheSkyClearOfTowerAndMoon() {
        val design = design(default)
        val sgv = sgvBox()
        // What is drawn: the time, the widest glucose values with their snow, and the arrow
        val time = inkRect("88:88", TIME_SIZE, timeBox().right, timeBox().top)
        val inks = mapOf("time" to time, "arrow" to arrowBox()) + listOf("22,2", "400", "10,4").associate { "glucose $it" to inkRect(it, SGV_SIZE, sgv.right, sgv.top) }
        for ((name, r) in inks) {
            println("$fileName $name: $r")
            insideRound(name, RectF(r), 12f)
            for ((part, box) in listOf("spire" to SPIRE, "tower" to TOWER, "moon" to MOON)) assertWithMessage("$name / $part").that(Rect.intersects(r, box)).isFalse()
            assertWithMessage("$name in its view").that(r.left >= 0 && r.top >= 0).isTrue()
        }
        // The longest values fit their view: a high value in mmol/L and in mg/dL
        val paint = Paint().apply { textSize = SGV_SIZE.toFloat(); typeface = baseFace }
        for (value in listOf("22,2", "22.2", "10,4", "400")) assertWithMessage(value).that(paint.measureText(value)).isAtMost(sgv.width().toFloat())
        // The time and the glucose with its snow do not touch
        assertThat(time.bottom).isLessThan(inkRect("88", SGV_SIZE, sgv.right, sgv.top).top - MIN_GAP)
        // The hands turn about the middle of the church clock
        val hand = rectOf(design.getJSONObject("hour_hand"))
        assertThat(hand.centerX()).isWithin(0.5f).of(AuroraTownImages.CLOCK_X)
        assertThat(hand.centerY()).isWithin(0.5f).of(AuroraTownImages.CLOCK_Y)
        assertThat(rectOf(design.getJSONObject("minute_hand"))).isEqualTo(hand)
    }

    @Test
    fun theColoursCanBeRead() {
        // The colours against the sky behind the texts, as the watch shows it (with the shade)
        val (_, picture) = face(PREVIEW_MS) { design ->
            for (key in listOf("time", "hour", "minute", "sgv", CAP_VIEW, "direction")) design.getJSONObject(key).put("visibility", "gone")
        }
        val sky = listOf(timeBox(), sgvBox()).flatMap { r -> around(picture, Pair(r.exactCenterX(), r.exactCenterY()), r.height() / 2) }
        val sorted = sky.map { luminance(it) }.sorted()
        val bright = sorted[(sorted.size * 0.9).toInt()]
        println("$fileName sky behind the texts: median ${sorted[sorted.size / 2]}, 90 % $bright")
        fun contrastOn(hex: String) = (maxOf(luminance(Color.parseColor(hex)), bright) + 0.05) / (minOf(luminance(Color.parseColor(hex)), bright) + 0.05)
        for (hex in listOf(TIME_COLOR, MID_COLOR, HIGH_COLOR, LOW_COLOR)) {
            println("$fileName $hex on the sky: ${contrastOn(hex)}")
            assertWithMessage(hex).that(contrastOn(hex)).isAtLeast(MIN_CONTRAST)
        }
        assertThat(contrastOn(SNOW_COLOR)).isAtLeast(7.0)
    }

    @Test
    fun theWakeAnimationPlays() {
        assumeTrue(animated)
        val design = design(default)
        val spec = readAnimation(design).normalized()
        val animator = LayerAnimator(spec.allLayers())
        assertWithMessage("${animator.errors}").that(animator.errors).isEmpty()
        assertThat(animator.size).isEqualTo(design.getJSONObject(WakeAnimationSpec.JSON_KEY).getJSONArray("layers").length())
        assertThat(animator.hasRest).isTrue()

        val times = listOf(0L, 1_500L, 4_000L, 8_000L, 13_500L, 14_960L, 15_000L)
        val frames = wakeFrames(times)
        val (_, rest) = face(PREVIEW_MS)
        val aurora = File(out, "aurora").apply { mkdirs() }
        val cells = mutableListOf<Pair<String, Bitmap>>()
        for ((ms, frame) in frames) {
            val picture = frame ?: rest
            File(aurora, "anim-$ms.png").writeBytes(pngBytes(picture))
            cells += (if (frame == null) "$ms ms (rest)" else "$ms ms") to picture
        }
        File(aurora, "anim-rest.png").writeBytes(pngBytes(rest))
        cells += "rest" to rest
        sheet(cells, "${fileName}_wake.png")
        // The tree and a house, bigger, to see the lights blink
        sheet(cells.map { (name, picture) -> name to Bitmap.createBitmap(picture, 150, 200, 110, 110) }, "${fileName}_tree.png")
        // It plays for its whole length, then the face is drawn the normal way
        for (ms in times.dropLast(1)) assertWithMessage("$ms ms").that(frames[ms]).isNotNull()
        assertThat(frames[15_000L]).isNull()
        // The aurora is on the face at rest too
        val skyPoint = Pair(200f, 60f)
        assertWithMessage("aurora at rest").that(brightestAround(rest, Pair(120f, 50f), 6)).isGreaterThan(0.02)
        // Snow falls while it plays: the frame in the middle has white crystals over the village
        val middle = frames.getValue(8_000L)!!
        var snow = 0
        for (y in 0 until 400) for (x in 0 until 400) if (luminance(middle.getPixel(x, y)) > luminance(rest.getPixel(x, y)) + 0.15) snow++
        assertWithMessage("snow pixels").that(snow).isGreaterThan(MIN_SNOW_PIXELS)
        // The first and the last frame look like the rest frame: nothing jumps at the start or the end
        for (ms in listOf(0L, 14_960L)) {
            val frame = frames.getValue(ms)!!
            val diff = around(frame, skyPoint, 30).zip(around(rest, skyPoint, 30)).count { (a, b) -> abs(luminance(a) - luminance(b)) > 0.03 }
            assertWithMessage("$ms ms differs from rest in $diff pixels").that(diff).isAtMost(60)
        }
    }

    protected companion object {

        private val PICTURES by lazy { AuroraTownImages() }

        /** The fonts in the zip: the digits, their snow caps, and the digits with the snow cut out. */
        const val BASE_FONT = SnowCapFont.BASE_NAME
        const val CAP_FONT = SnowCapFont.CAP_NAME
        const val CUT_FONT = SnowCapFont.CUT_NAME

        /** The time: its size, where the middle of its digits lies, the extra width of the view and the extra room of the cap views. */
        const val TIME_SIZE = 48
        const val TIME_MIDDLE = 62f
        const val TIME_SLACK = 2
        const val CAP_ROOM = 12

        /** The glucose value: its size, the middle of its digits, and where it may lie (it ends at [SGV_RIGHT]). */
        const val SGV_SIZE = 72
        const val BG_MIDDLE = 127f
        const val SGV_LEFT = 104
        const val SGV_RIGHT = 254
        const val ARROW_SIZE = 44
        const val ARROW_GAP = 4
        const val MGDL_PER_MMOL = 18.0
        const val MMOL_FORMAT = "%.1f"
        const val MMOL_DYN = "sgvMmol"

        /** The square of a clock hand: 21, so its middle lies within 0.3 of the middle of the clock. */
        const val HAND_BOX = 21

        /** The colours. */
        const val TIME_COLOR = "#F2334A"
        const val SNOW_COLOR = "#FFFFFF"
        const val MID_COLOR = "#4FD98A"
        const val HIGH_COLOR = "#FFB23F"
        const val LOW_COLOR = "#FF5A5A"
        const val EAVE_COLOR = "#FFE2A6"
        val WARM_WHITE = Color.rgb(255, 214, 160)

        /**
         * The least contrast of a coloured text on the brightest sky behind it (with the shade). Low for
         * text, but the snow on the digits draws their shape in white; the red of the time is the lowest.
         */
        const val MIN_CONTRAST = 2.0

        /** The views: the white cap of the glucose value, the moon, and the shade behind the texts. */
        const val CAP_VIEW = "freetext1"
        const val MOON_VIEW = "freetext2"
        const val SHADE_VIEW = "cover_chart"

        /** The shade: where, how dark in its middle (0 to 255), and how much of it is fully dark (0 to 1 of its radius). */
        val SHADE_BOX = Rect(76, 20, 324, 178)
        const val SHADE_ALPHA = 110f
        const val SHADE_CORE = 0.45f

        /** The spire and the tower of the church, and the moon, in design coordinates: no text there. */
        val SPIRE = Rect(72, 128, 104, 172)
        val TOWER = Rect(60, 170, 112, 260)
        val MOON = Rect(319, 87, 353, 121)

        /** The least room between the time and the snow on the glucose value. */
        const val MIN_GAP = 4

        /** The pictures in the zip. */
        const val AURORA = "Aurora"
        const val AURORA_MIRRORED = "AuroraMirrored"
        const val SKY_MASK = "SkyMask"
        const val CRYSTAL_SMALL = "CrystalSmall"
        const val CRYSTAL_BIG = "CrystalBig"

        /** The wake animation. */
        const val ANIMATION_MS = 15_000L
        const val FADE_MS = 1_500L
        const val AURORA_SLICES = 100
        const val AURORA_BOTTOM = 230
        const val LIGHT_RADIUS = 2.4
        const val TWINKLE = "0.9 - env*0.35*(0.5+0.5*sin(t*2.2 + i*0.9))"
        const val SNOW_COUNT = 70
        const val FLAKE_COUNT = 120
        const val SMALL_CRYSTAL = 10f
        const val BIG_CRYSTAL = 22f

        /** How far the middle of a cap may be from the middle of its digit, in pixels. */
        const val CAP_TOLERANCE = 1.5f

        /** The fewest pixels that count as a digit or a cap in a cell. */
        const val MIN_INK = 8

        /** The alpha from which a pixel of a glyph drawn alone counts as ink: about where a colour stops matching. */
        const val INK_ALPHA = 150

        /** The fewest white pixels the snow adds to a frame in the middle of the wake. */
        const val MIN_SNOW_PIXELS = 150

        const val PREVIEW_QUALITY = 88

        /** The moment the previews show: Christmas Eve at 18:24, and the wake frame of the animated preview. */
        val PREVIEW_MS: Long = LocalDateTime.of(2026, 12, 24, 18, 24, 37).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        const val PREVIEW_WAKE_MS = 6_000L
    }
}
