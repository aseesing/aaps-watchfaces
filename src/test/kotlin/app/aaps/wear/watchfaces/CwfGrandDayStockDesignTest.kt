// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The day face of "Grand Complication" for plain AAPS 4.0 (`Grand_Complication_Day_AAPS4.zip`): the colours of
 * [CwfGrandDayDesignTest], without the wake-up animation, the sun, the moon, the weather and the watch battery.
 * The disc shows complication 1, or IOB and COB; with all off it is not there. See [GrandComplicationDesignBase].
 */
@RunWith(PartRunner::class)
@Part(0, 2)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
open class CwfGrandDayStockDesignTest : CwfGrandDayDesignTest() {

    override val displayName = "Grand Complication Day (AAPS 4.0)"
    override val fileName = "Grand_Complication_Day_AAPS4"
    override val animated = false
    override val looks = listOf(Look("preview", previewInstant, null))
}

// The other parts of the tests above, so they run side by side: see [PartRunner]
@Part(1, 2)
class CwfGrandDayStockDesignTestPart1 : CwfGrandDayStockDesignTest()
