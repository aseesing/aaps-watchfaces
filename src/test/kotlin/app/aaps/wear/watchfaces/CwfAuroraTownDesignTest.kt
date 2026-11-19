// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces

import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The animated Christmas face "Aurora Town" (`Aurora_Town.zip`) for this branch: the aurora waves, the lights blink and snow falls while the wake animation plays. See [AuroraTownDesignBase].
 */
@RunWith(PartRunner::class)
@Part(0, 6)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
open class CwfAuroraTownDesignTest : AuroraTownDesignBase() {

    override val displayName = "Aurora Town"
    override val fileName = "Aurora_Town"
    override val animated = true
}

// The other parts of the tests above, so they run side by side: see [PartRunner]
@Part(1, 6)
class CwfAuroraTownDesignTestPart1 : CwfAuroraTownDesignTest()
@Part(2, 6)
class CwfAuroraTownDesignTestPart2 : CwfAuroraTownDesignTest()
@Part(3, 6)
class CwfAuroraTownDesignTestPart3 : CwfAuroraTownDesignTest()
@Part(4, 6)
class CwfAuroraTownDesignTestPart4 : CwfAuroraTownDesignTest()
@Part(5, 6)
class CwfAuroraTownDesignTestPart5 : CwfAuroraTownDesignTest()
