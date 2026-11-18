package app.aaps.watchfaces.snapshot

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Compares two snapshot runs of [DesignSnapshotTest] and writes a report. It is a report: differences
 * do not fail the test, only a missing folder does.
 *
 * Settings (Gradle properties):
 * - `before`, `after`: the two snapshot folders (each with one folder per design);
 * - `diffOut`: where the report goes, default `build/snapshot-diff`.
 *
 * `./gradlew testDebugUnitTest -Pdesigns=SnapshotCompare -Pbefore=... -Pafter=... -PdiffOut=...`
 *
 * For each PNG in both folders it counts the pixels where a channel (alpha, red, green or blue)
 * differs by more than [THRESHOLD], and the largest difference. A frame with any difference also gets
 * a picture `before | after | difference` (in the difference: blue for a small one, up to [THRESHOLD],
 * red to yellow for a larger one). `report.md` lists the frames by the share of changed pixels, and the
 * frames that are only in one of the two folders.
 */
class SnapshotCompareTest {

    private data class Result(val frame: String, val changed: Int, val total: Int, val anyDiff: Int, val maxDiff: Int, val note: String = "") {

        val percent: Double get() = if (total == 0) 100.0 else 100.0 * changed / total
    }

    @Test
    fun compare() {
        val beforeName = System.getProperty("snapshot.before")
        val afterName = System.getProperty("snapshot.after")
        assumeTrue("Runs only with -Pbefore=... -Pafter=..., see the class doc", beforeName != null && afterName != null)
        val before = File(beforeName!!)
        val after = File(afterName!!)
        require(before.isDirectory) { "No folder 'before': ${before.absolutePath}" }
        require(after.isDirectory) { "No folder 'after': ${after.absolutePath}" }
        val out = File(System.getProperty("snapshot.diffOut") ?: "build/snapshot-diff").apply { deleteRecursively(); mkdirs() }

        val beforeFrames = pngs(before)
        val afterFrames = pngs(after)
        val results = (beforeFrames intersect afterFrames).sorted().map { frame -> compare(frame, File(before, frame), File(after, frame), out) }
        val onlyBefore = (beforeFrames - afterFrames).sorted()
        val onlyAfter = (afterFrames - beforeFrames).sorted()
        val report = report(before, after, results, onlyBefore, onlyAfter)
        File(out, "report.md").writeText(report)
        println(report.lines().take(12).joinToString("\n"))
        println("Report: ${File(out, "report.md").absolutePath}")
    }

    /** The PNG files under [dir], as paths relative to it. */
    private fun pngs(dir: File): Set<String> =
        dir.walkTopDown().filter { it.isFile && it.extension == "png" }.map { it.relativeTo(dir).path }.toSet()

    private fun compare(frame: String, beforeFile: File, afterFile: File, out: File): Result {
        val a = ImageIO.read(beforeFile)
        val b = ImageIO.read(afterFile)
        if (a.width != b.width || a.height != b.height)
            return Result(frame, 0, 0, 0, 255, "size ${a.width}x${a.height} against ${b.width}x${b.height}")
        val diff = BufferedImage(a.width, a.height, BufferedImage.TYPE_INT_RGB)
        var changed = 0
        var anyDiff = 0
        var maxDiff = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val d = channelDiff(a.getRGB(x, y), b.getRGB(x, y))
            if (d > 0) anyDiff++
            if (d > THRESHOLD) changed++
            maxDiff = max(maxDiff, d)
            diff.setRGB(x, y, diffColour(d))
        }
        if (maxDiff > 0) writeSideBySide(a, b, diff, File(out, frame.removeSuffix(".png") + "_diff.png"))
        return Result(frame, changed, a.width * a.height, anyDiff, maxDiff)
    }

    /** The largest difference of one channel of two ARGB pixels. */
    private fun channelDiff(p: Int, q: Int): Int =
        (0 until 4).maxOf { shift -> abs(((p ushr (shift * 8)) and 0xFF) - ((q ushr (shift * 8)) and 0xFF)) }

    /** Black for no difference, blue for a small one, red to yellow for a larger one. */
    private fun diffColour(d: Int): Int = when {
        d == 0         -> 0
        d <= THRESHOLD -> 0x40 + d * 0x60 / THRESHOLD
        else           -> (0xFF shl 16) or (min(255, (d - THRESHOLD) * 2) shl 8)
    }

    /** Writes `before | after | difference` side by side, on a dark grey (see-through pixels show as that grey). */
    private fun writeSideBySide(a: BufferedImage, b: BufferedImage, diff: BufferedImage, file: File) {
        val gap = 8
        val sheet = BufferedImage(a.width * 3 + gap * 2, a.height, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = Color(40, 40, 48)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.drawImage(a, 0, 0, null)
        g.drawImage(b, a.width + gap, 0, null)
        g.drawImage(diff, 2 * (a.width + gap), 0, null)
        g.dispose()
        file.parentFile.mkdirs()
        ImageIO.write(sheet, "png", file)
    }

    private fun report(before: File, after: File, results: List<Result>, onlyBefore: List<String>, onlyAfter: List<String>): String = buildString {
        val differing = results.filter { it.maxDiff > 0 }
        val changed = results.filter { it.changed > 0 }
        appendLine("# Snapshot comparison")
        appendLine()
        appendLine("- before: `${before.absolutePath}`")
        appendLine("- after: `${after.absolutePath}`")
        appendLine("- frames compared: ${results.size}; with any difference: ${differing.size}; with pixels changed by more than $THRESHOLD: ${changed.size}")
        appendLine("- only in before: ${onlyBefore.size}; only in after: ${onlyAfter.size}")
        appendLine()
        appendLine("A pixel counts as changed when one of its channels differs by more than $THRESHOLD (of 255).")
        appendLine("Frames with any difference have a picture `<frame>_diff.png` here: before | after | difference.")
        appendLine()
        appendLine("## Frames with differences")
        appendLine()
        if (differing.isEmpty()) appendLine("None: every frame in both folders is the same, pixel for pixel.")
        else {
            appendLine("| frame | changed pixels | changed % | pixels with any difference | largest difference | note |")
            appendLine("|---|---:|---:|---:|---:|---|")
            differing.sortedWith(compareByDescending<Result> { it.percent }.thenByDescending { it.maxDiff }.thenBy { it.frame }).forEach {
                appendLine("| ${it.frame} | ${it.changed} | ${"%.3f".format(Locale.US, it.percent)} | ${it.anyDiff} | ${it.maxDiff} | ${it.note} |")
            }
        }
        listOf("Only in before" to onlyBefore, "Only in after" to onlyAfter).forEach { (title, frames) ->
            appendLine()
            appendLine("## $title")
            appendLine()
            if (frames.isEmpty()) appendLine("None.") else frames.forEach { appendLine("- $it") }
        }
        listOf("before" to before, "after" to after).forEach { (side, dir) ->
            val errors = File(dir, "errors.txt")
            if (errors.exists() && errors.length() > 0) {
                appendLine()
                appendLine("## Errors of the $side run")
                appendLine()
                appendLine("```")
                appendLine(errors.readText().trim())
                appendLine("```")
            }
        }
    }

    private companion object {

        /** The largest channel difference that does not count as a changed pixel. */
        const val THRESHOLD = 8
    }
}
