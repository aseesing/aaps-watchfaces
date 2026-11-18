// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The night face of "Grand Complication" for plain AAPS 4.0 (`Grand_Complication_Night_AAPS4.zip`): the colours of
 * [CwfGrandNightDesignTest], without the wake-up animation, the sun, the moon, the weather and the watch battery.
 * The disc shows IOB and COB; with both off it is not there. See [GrandComplicationDesignBase].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfGrandNightStockDesignTest : CwfGrandNightDesignTest() {

    override val displayName = "Grand Complication Night (AAPS 4.0)"
    override val fileName = "Grand_Complication_Night_AAPS4"
    override val animated = false
    override val previewInstant = LATE_EVENING_MS
    override val looks = listOf(Look("preview", previewInstant, null))
}
