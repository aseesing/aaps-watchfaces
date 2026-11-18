package app.aaps.watchfaces.snapshot

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import app.aaps.wear.phonepreview.CwfPreviewRenderer
import app.aaps.wear.watchfaces.settingsync.CwfSettingNames
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSetting
import app.aaps.wear.watchfaces.settingsync.CwfSyncedSettings
import app.aaps.wear.watchfaces.utils.WatchFaceTime
import org.json.JSONObject
import org.mockito.kotlin.mock
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The only file of the snapshot harness that uses the watch face engine of AAPS.
 *
 * When the engine changes its API, port this file. [DesignSnapshotTest] and [SnapshotCompareTest]
 * do not use the engine and stay as they are.
 *
 * A design is drawn the way the phone preview of AAPS draws it ([CwfPreviewRenderer]): a still frame
 * goes through the watch's own frame pipeline, a frame of the wake-up animation through the watch's
 * own wake animator. The values come in as the same messages the phone sends to the watch.
 */
class SnapshotEngine(private val app: Application) {

    /** A design, read from its zip. */
    class Design(val name: String, internal val data: CwfData) {

        private val json = JSONObject(data.json)

        /** How many colours the wearer can choose for this design (0, 1 or 2). */
        val colourSlots: Int = json.optJSONObject("colourLooks")?.let { looks -> listOf("colour1", "colour2").count { looks.has(it) } } ?: 0
    }

    /** Reads the design in [zip], as the phone reads a zip the user picks (the design may set watch settings). */
    fun loadDesign(zip: File): Design {
        val file = ZipWatchfaceFormat.loadCustomWatchface(zip.readBytes(), zip.name, authorization = true)
            ?: error("${zip.name} is not a design zip")
        return Design(zip.nameWithoutExtension, file.cwfData)
    }

    /**
     * The time the engine reads as "now" (`System.currentTimeMillis()` in the engine code). The test
     * checks it, to be sure the clock of the engine is frozen.
     */
    fun engineNow(): Long = WatchFaceTime().apply { setToNow() }.millis

    /**
     * One watch face that draws a design. [name] must be new for each face in the test run: the face
     * keeps its files and preferences under this name.
     */
    inner class Face(private val name: String) {

        private val renderer = CwfPreviewRenderer(app, mock<AAPSLogger>(), name)

        /** True while the wake-up animation plays (the last frame was a frame of it). */
        val isAnimating: Boolean get() = renderer.isAnimating

        /**
         * Writes every watch setting the phone can set at its default value, as a new watch has them.
         * The design itself can change some of them when it is shown (its metadata).
         */
        fun settingsAtDefaults() {
            val names = CwfSettingNames(app.resources)
            val edit = renderer.watchSettings.edit()
            CwfSyncedSettings.all.forEach { setting ->
                when (setting) {
                    is CwfSyncedSetting.Toggle -> edit.putBoolean(names.nameOf(setting), setting.default)
                    is CwfSyncedSetting.Choice -> edit.putString(names.nameOf(setting), setting.defaultValue)
                }
            }
            edit.commit()
            renderer.settingsChanged()
        }

        /** Chooses a colour other than the design's own for each colour slot (the watch setting "Main colour", "Second colour"). */
        fun chooseColours() {
            val values = app.resources.getStringArray(R.array.cwf_colour_values)
            renderer.applyWatchSettings(
                mapOf(
                    app.getString(R.string.key_cwf_colour_1) to values[4],   // lime: not the cyan that D2 has as its own
                    app.getString(R.string.key_cwf_colour_2) to values[6]    // orange
                )
            )
        }

        /** Gives the face the fixed values of [data], as they are at [nowMs]. */
        fun feed(data: SnapshotData, nowMs: Long) {
            app.getSharedPreferences("${name}_aaps_health_values", Context.MODE_PRIVATE).edit()
                .putInt("heart_rate", data.heartRate).putLong("heart_rate_at", nowMs - 60_000L)
                .putLong("steps", data.steps).putLong("steps_at", nowMs - 60_000L)
                .commit()
            messages(data, nowMs).forEach(renderer::apply)
        }

        /** Shows [design] from the next frame on. Its wake-up animation starts with the next frame. */
        fun show(design: Design) = renderer.setDesign(design.data)

        /**
         * The face at [nowMs], [size] pixels wide and high: a frame of the animation while it plays. A
         * copy that the caller owns: the renderer draws into its own frames again two frames later.
         */
        fun frame(size: Int, nowMs: Long): Bitmap = renderer.frame(size, nowMs).copy(Bitmap.Config.ARGB_8888, false)

        fun release() = renderer.release()
    }

    /** The messages the phone sends to the watch for [data] at [now]. */
    private fun messages(data: SnapshotData, now: Long): List<EventData> = listOf(
        EventData.Preferences(
            timeStamp = now, wearControl = false, unitsMgdl = false, bolusPercentage = 100, maxCarbs = 100, maxBolus = 10.0,
            insulinButtonIncrement1 = 0.5, insulinButtonIncrement2 = 1.0, carbsButtonIncrement1 = 5, carbsButtonIncrement2 = 10,
            latitude = data.latitude, longitude = data.longitude
        ),
        EventData.SingleBg(
            dataset = 0, timeStamp = now - 2 * MINUTE, sgvString = "6,7", glucoseUnits = "mmol", slopeArrow = "→",
            delta = "+0,1", deltaDetailed = "+0,12", avgDelta = "+0,1", avgDeltaDetailed = "+0,08", sgvLevel = 0, sgv = 121.0,
            high = 180.0, low = 70.0, color = 0, deltaMgdl = 1.8, avgDeltaMgdl = 1.4
        ),
        EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "1.25", iobDetail = "(0.90|0.35)", cob = "12g", currentBasal = "0.85U/h",
            battery = data.phoneBattery.toString(), rigBattery = "--", openApsStatus = now - 3 * MINUTE, bgi = "-0.4", batteryLevel = 1,
            tempTarget = "100", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0,
            sensorEndsAt = now + data.sensorHoursLeft * HOUR, pumpEndsAt = now + data.pumpHoursLeft * HOUR
        ),
        EventData.GraphData(ArrayList(graphAges().map { age ->
            val hours = age / 3_600_000.0
            EventData.SingleBg(
                dataset = 0, timeStamp = now - age, sgv = 125.0 + 40.0 * sin(hours * PI / 4.0) + 15.0 * exp(-hours / 2.0) * sin(hours * PI / 0.8),
                high = 180.0, low = 70.0
            )
        })),
        EventData.TreatmentData(
            temps = arrayListOf(EventData.TreatmentData.TempBasal(now - 90 * MINUTE, 0.85, now - 60 * MINUTE, 0.85, 1.2)),
            basals = arrayListOf(EventData.TreatmentData.Basal(now - 6 * HOUR, now, 0.85)),
            boluses = arrayListOf(
                EventData.TreatmentData.Treatment(now - 150 * MINUTE, 2.5, 30.0, isSMB = false, isValid = true),
                EventData.TreatmentData.Treatment(now - 40 * MINUTE, 0.3, 0.0, isSMB = true, isValid = true),
                EventData.TreatmentData.Treatment(now - 20 * MINUTE, 0.2, 0.0, isSMB = true, isValid = true)
            ),
            predictions = ArrayList((1..12).map { i ->
                EventData.SingleBg(dataset = 0, timeStamp = now + i * 5 * MINUTE, sgv = 121.0 + 2.0 * i, high = 180.0, low = 70.0, color = 0)
            })
        ),
        EventData.Weather(now - 10 * MINUTE, data.weather, data.temperature)
    )

    /** The ages of the graph points, oldest first: every 5 minutes for the last 6 hours, then thinner, as the phone sends them. */
    private fun graphAges(): List<Long> = ((0L until 6 * 12L).map { it * 5 * MINUTE } + (12L..48L).map { it * 30 * MINUTE }).sortedDescending()

    private companion object {

        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}

/**
 * The fixed values the face shows. [weather] is one of `clear`, `partly`, `cloudy` and `rain`, as the
 * phone sends it.
 */
data class SnapshotData(
    val weather: String,
    val temperature: Double = 7.5,
    val latitude: Double = 52.1,
    val longitude: Double = 5.1,
    val phoneBattery: Int = 78,
    val heartRate: Int = 72,
    val steps: Long = 6482,
    val sensorHoursLeft: Long = 60,
    val pumpHoursLeft: Long = 10
)
