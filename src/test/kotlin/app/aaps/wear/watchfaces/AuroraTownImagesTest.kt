// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/**
 * Makes all pictures of "Aurora Town" ([AuroraTownImages]) and writes them, with debug pictures to
 * look at, to `build/cwf-design/aurora/`. Checks the sizes against the zip budget and a few points.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AuroraTownImagesTest {

    private val out = File("build/cwf-design/aurora").apply { mkdirs() }

    @Test
    fun makesAllPictures() {
        val images = AuroraTownImages()
        val files = linkedMapOf(
            "AnimatedBackground.jpg" to images.animatedBackground(),
            "StockBackground.jpg" to images.stockBackground(),
            "SkyMask.png" to images.skyMask(),
            "Aurora.jpg" to images.aurora(),
            "AuroraMirrored.jpg" to images.auroraMirrored(),
            "CrystalSmall.png" to images.crystalSmall(),
            "CrystalBig.png" to images.crystalBig(),
            "SecondHandSnow.png" to images.secondHandSnow(),
            "HourHand.png" to images.hourHand(),
            "MinuteHand.png" to images.minuteHand(),
            "StarGlow.png" to images.starGlow(),
            "debug-mask.png" to images.debugMask(),
            "debug-lights.png" to images.debugLights()
        )
        for ((name, bytes) in files) File(out, name).writeBytes(bytes)
        val report = buildString {
            for ((name, bytes) in files) appendLine("%-24s %8d bytes".format(name, bytes.size))
            appendLine("jpg quality: ${images.jpgQualities()}")
            appendLine("clock: x=${AuroraTownImages.CLOCK_X} y=${AuroraTownImages.CLOCK_Y} r=${AuroraTownImages.CLOCK_RADIUS}")
            appendLine("eave lights: ${AuroraTownImages.EAVE_LIGHTS.size}, tree lights: ${AuroraTownImages.TREE_LIGHTS.size}")
            appendLine("star: x=${AuroraTownImages.STAR_X} y=${AuroraTownImages.STAR_Y} size=${AuroraTownImages.STAR_SIZE} top=${AuroraTownImages.STAR_TOP}")
        }
        File(out, "sizes.txt").writeText(report)
        println(report)

        assertWithMessage("animated background").that(files.getValue("AnimatedBackground.jpg").size).isAtMost(150_000)
        assertWithMessage("stock background").that(files.getValue("StockBackground.jpg").size).isAtMost(150_000)
        assertWithMessage("aurora").that(files.getValue("Aurora.jpg").size).isAtMost(40_000)
        assertWithMessage("aurora mirrored").that(files.getValue("AuroraMirrored.jpg").size).isAtMost(40_000)
        assertWithMessage("sky mask").that(files.getValue("SkyMask.png").size).isAtMost(15_000)
        assertWithMessage("snow").that(files.getValue("SecondHandSnow.png").size).isAtMost(40_000)
        assertWithMessage("star glow").that(files.getValue("StarGlow.png").size).isAtMost(25_000)

        // The sky mask: full high in the middle, empty on the ground
        val mask = BitmapFactory.decodeByteArray(files.getValue("SkyMask.png"), 0, files.getValue("SkyMask.png").size)
        assertThat(mask.width).isEqualTo(800)
        assertThat(Color.alpha(mask.getPixel(400, 120))).isEqualTo(255)
        assertThat(Color.alpha(mask.getPixel(400, 700))).isEqualTo(0)
        // Not on the tower clock
        assertThat(Color.alpha(mask.getPixel((AuroraTownImages.CLOCK_X * 2).toInt(), (AuroraTownImages.CLOCK_Y * 2).toInt()))).isEqualTo(0)
        // Not on the star: its middle, and a little below its top point
        assertThat(Color.alpha(mask.getPixel((AuroraTownImages.STAR_X * 2).toInt(), (AuroraTownImages.STAR_Y * 2).toInt()))).isEqualTo(0)
        assertThat(Color.alpha(mask.getPixel((AuroraTownImages.STAR_X * 2).toInt(), ((AuroraTownImages.STAR_TOP + 4) * 2).toInt()))).isEqualTo(0)

        // The light points lie on the face and below the sky
        for ((x, y) in AuroraTownImages.EAVE_LIGHTS + AuroraTownImages.TREE_LIGHTS) {
            assertThat(hypot(x - 200f, y - 200f)).isLessThan(200f)
            assertThat(Color.alpha(mask.getPixel((x * 2).toInt(), (y * 2).toInt()))).isEqualTo(0)
        }
        assertThat(AuroraTownImages.TREE_LIGHTS.size).isAtLeast(40)
        assertThat(AuroraTownImages.EAVE_LIGHTS.size).isAtLeast(150)

        // Deterministic: a second set gives the same bytes
        val again = AuroraTownImages()
        assertThat(again.stockBackground()).isEqualTo(files.getValue("StockBackground.jpg"))
        assertThat(again.secondHandSnow()).isEqualTo(files.getValue("SecondHandSnow.png"))
    }
}
