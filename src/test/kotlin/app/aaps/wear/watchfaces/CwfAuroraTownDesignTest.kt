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
 * The animated Christmas face "Aurora Town" (`Aurora_Town.zip`) for this branch: the aurora waves, the lights blink and snow falls while the wake animation plays. See [AuroraTownDesignBase].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CwfAuroraTownDesignTest : AuroraTownDesignBase() {

    override val displayName = "Aurora Town"
    override val fileName = "Aurora_Town"
    override val animated = true
}
