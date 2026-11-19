// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Christmas face "Aurora Town" for plain AAPS, glucose in mg/dL (`Aurora_Town_mgdl_AAPS4.zip`): everything in the background picture, the snow as the second hand. See [AuroraTownDesignBase].
 */
@RunWith(PartRunner::class)
@Part(0, 3)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
open class CwfAuroraTownStockMgdlDesignTest : AuroraTownDesignBase() {

    override val displayName = "Aurora Town mg/dL"
    override val fileName = "Aurora_Town_mgdl_AAPS4"
    override val animated = false
    override val mgdl = true
}

// The other parts of the tests above, so they run side by side: see [PartRunner]
@Part(1, 3)
class CwfAuroraTownStockMgdlDesignTestPart1 : CwfAuroraTownStockMgdlDesignTest()
@Part(2, 3)
class CwfAuroraTownStockMgdlDesignTestPart2 : CwfAuroraTownStockMgdlDesignTest()
