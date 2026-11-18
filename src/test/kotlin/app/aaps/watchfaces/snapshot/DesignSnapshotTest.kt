package app.aaps.watchfaces.snapshot

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * Draws every design zip of a folder at fixed moments, with fixed values, into PNG files. Two runs give
 * the same files, byte for byte. [SnapshotCompareTest] compares two such runs, for example before and
 * after a change of the engine.
 *
 * The designs come from the zips, not from the design tests, so the snapshots of an old engine with
 * old zips can be compared with those of a new engine with new zips.
 *
 * Settings (Gradle properties):
 * - `snapshotZips`: the folder with the zips, default `cwf-designs`;
 * - `snapshotOut`: where the PNGs go, default `build/snapshots`, one folder per design.
 *
 * It runs only when asked for: `./gradlew testDebugUnitTest -Pdesigns=DesignSnapshot -PsnapshotOut=...`
 * (or `--tests "*DesignSnapshotTest*"`). A design that cannot be drawn is written to `errors.txt` in
 * the output folder, and the others are still drawn.
 *
 * Frames of each design, all 400 x 400:
 * - `still_*`: the face at rest. Day (17 November 2026, about 12:00, first quarter moon) in four kinds of
 *   weather, and with chosen colours when the design has colours; night with the half moon (17 November,
 *   19:30) and with the full moon (24 November, 23:00, clear and rain).
 * - `wake_NNNNNms`: the wake-up animation, that long after the wake, on the day (only up to its end).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The engine is instrumented so that its System.currentTimeMillis() reads the frozen clock, see SnapshotClock
@Config(sdk = [35], application = Application::class, instrumentedPackages = ["app.aaps.wear"])
class DesignSnapshotTest(private val zipName: String) {

    /** A still frame: its name, its moment, its weather, and whether the colours are chosen. */
    private data class Still(val name: String, val at: LocalDateTime, val weather: String, val colours: Boolean = false)

    private val clock = SnapshotClock()

    @Before
    fun setUp() {
        clock.start()
        ShadowSettings.set24HourTimeFormat(true)
        setWatchBattery(WATCH_BATTERY)
    }

    @After
    fun tearDown() = clock.stop()

    @Test
    fun snapshot() {
        assumeTrue("Runs only when asked for, see the class doc", requested() && zipName != "not requested")
        val zip = File(zipDir(), zipName)
        val dir = File(outDir(), zip.nameWithoutExtension).apply { deleteRecursively(); mkdirs() }
        val engine = SnapshotEngine(RuntimeEnvironment.getApplication())
        val design = try {
            engine.loadDesign(zip)
        } catch (e: Throwable) {
            error(zipName, "load", e)
            return
        }
        val stills = listOf(
            Still("day_clear", DAY, "clear"),
            Still("day_partly", DAY.plusMinutes(1), "partly"),
            Still("day_cloudy", DAY.plusMinutes(2), "cloudy"),
            Still("day_rain", DAY.plusMinutes(3), "rain")
        ) + (if (design.colourSlots > 0) listOf(Still("day_colours", DAY.plusMinutes(4), "clear", colours = true)) else emptyList())
        stills.forEach { still -> scene(still.name) { stillFrame(engine, design, still, dir) } }
        scene("wake") { wakeFrames(engine, design, dir) }
        listOf(
            Still("night_halfmoon_clear", HALF_MOON_NIGHT, "clear"),
            Still("night_fullmoon_clear", FULL_MOON_NIGHT, "clear"),
            Still("night_fullmoon_rain", FULL_MOON_NIGHT.plusMinutes(1), "rain")
        ).forEach { still -> scene(still.name) { stillFrame(engine, design, still, dir) } }
    }

    /** Runs [draw]; when it fails, the error goes to `errors.txt` and the next scene is drawn. */
    private fun scene(name: String, draw: () -> Unit) {
        try {
            draw()
        } catch (e: Throwable) {
            error(zipName, name, e)
        }
    }

    /**
     * The face at rest at [still]. A design starts its wake-up animation when it is shown, as on the
     * watch, so the face is shown [LEAD_MS] earlier, draws one frame (the start of the animation), and
     * is drawn at [still] when the animation is over. The first frame has the same size as the picture:
     * after a smaller first frame, the preview renderer kept drawing the face at that small size.
     */
    private fun stillFrame(engine: SnapshotEngine, design: SnapshotEngine.Design, still: Still, dir: File) {
        val at = SnapshotClock.epochMs(still.at)
        clock.setTo(at - LEAD_MS)
        val face = newFace(engine, design, still.name, at, SnapshotData(weather = still.weather), still.colours)
        try {
            face.frame(SIZE, clock.now).recycle()
            clock.setTo(at)
            val frame = face.frame(SIZE, at)
            check(!face.isAnimating) { "The wake-up animation still plays after ${LEAD_MS / 1000} s" }
            frame.writePng(File(dir, "still_${still.name}.png"))
        } finally {
            face.release()
        }
    }

    /** The frames of the wake-up animation at [WAKE_MS] after the design is shown, until it ends. */
    private fun wakeFrames(engine: SnapshotEngine, design: SnapshotEngine.Design, dir: File) {
        val wake = SnapshotClock.epochMs(WAKE)
        clock.setTo(wake)
        val face = newFace(engine, design, "wake", wake, SnapshotData(weather = "clear"), colours = false)
        try {
            for (ms in WAKE_MS) {
                clock.setTo(wake + ms)
                val frame = face.frame(SIZE, clock.now)
                // A frame after the end of the animation is the face at rest: the animation is over
                if (!face.isAnimating) {
                    frame.recycle()
                    break
                }
                frame.writePng(File(dir, "wake_%05dms.png".format(ms)))
            }
        } finally {
            face.release()
        }
    }

    private fun newFace(
        engine: SnapshotEngine, design: SnapshotEngine.Design, scene: String, at: Long, data: SnapshotData, colours: Boolean
    ): SnapshotEngine.Face {
        // Each face has its own files: the name must be new in this run
        val face = engine.Face("snap_${design.name}_$scene")
        face.settingsAtDefaults()
        if (colours) face.chooseColours()
        face.feed(data, at)
        face.show(design)
        check(engine.engineNow() == clock.now) { "The engine does not read the frozen clock: ${engine.engineNow()} against ${clock.now}" }
        return face
    }

    /** Gives the watch battery [percent], as the last battery broadcast that the system keeps. */
    private fun setWatchBattery(percent: Int) {
        RuntimeEnvironment.getApplication().sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, percent).putExtra(BatteryManager.EXTRA_SCALE, 100)
        )
    }

    private fun Bitmap.writePng(file: File) {
        file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
        recycle()
    }

    companion object {

        const val SIZE = 400

        /** Longer than the longest wake-up animation the engine allows (30 s). */
        private const val LEAD_MS = 40_000L

        private const val WATCH_BATTERY = 66

        /** The times of the wake frames, in ms after the wake. */
        private val WAKE_MS = listOf(0L, 100L, 250L, 500L, 1000L, 1500L, 2000L, 3000L, 4500L, 6000L, 9000L, 12000L, 14900L)

        /** Day: the moon is in its first quarter. */
        private val DAY: LocalDateTime = LocalDateTime.of(2026, 11, 17, 12, 0)
        private val WAKE: LocalDateTime = LocalDateTime.of(2026, 11, 17, 12, 10)

        /** Night with the half moon (first quarter) in the sky in the south-west. */
        private val HALF_MOON_NIGHT: LocalDateTime = LocalDateTime.of(2026, 11, 17, 19, 30)

        /** Night with the full moon (24 November 2026) high in the sky. */
        private val FULL_MOON_NIGHT: LocalDateTime = LocalDateTime.of(2026, 11, 24, 23, 0)

        /** True when the build asked for the snapshots (see build.gradle.kts). */
        private fun requested(): Boolean = System.getProperty("snapshot.run") == "true"

        private fun zipDir(): File = File(System.getProperty("snapshot.zips") ?: "cwf-designs")
        private fun outDir(): File = File(System.getProperty("snapshot.out") ?: "build/snapshots")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun zips(): List<Array<Any>> {
            if (!requested()) return listOf(arrayOf("not requested"))
            // One zip per JVM when the build asks for it (see build.gradle.kts); "-" for none here
            val only = System.getProperty("snapshot.only").orEmpty()
            if (only == "-") return listOf(arrayOf("not requested"))
            val dir = zipDir()
            val zips = dir.listFiles { file -> file.extension == "zip" && (only.isEmpty() || file.name == only) }?.sortedBy { it.name }
            require(!zips.isNullOrEmpty()) { "No design zips in ${dir.absolutePath}" }
            outDir().mkdirs()
            if (only.isEmpty()) File(outDir(), ERRORS).delete()
            return zips.map { arrayOf(it.name) }
        }

        private const val ERRORS = "errors.txt"

        @Synchronized
        private fun error(zip: String, scene: String, e: Throwable) {
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            System.err.println("Snapshot failed: $zip $scene: $e")
            File(outDir(), ERRORS).appendText("== $zip, $scene\n$trace\n")
        }
    }
}
