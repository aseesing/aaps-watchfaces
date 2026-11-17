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
 * The Christmas face "Aurora Town" for plain AAPS (`Aurora_Town_Stock.zip`): everything in the background picture, the snow as the second hand. See [AuroraTownDesignBase].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfAuroraTownStockDesignTest : AuroraTownDesignBase() {

    override val displayName = "Aurora Town Stock"
    override val fileName = "Aurora_Town_Stock"
    override val animated = false
}
