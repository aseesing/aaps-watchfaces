package app.aaps.wear.watchfaces

import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import org.junit.runner.manipulation.NoTestsRemainException
import org.robolectric.RobolectricTestRunner

/**
 * One part of a long design test class: the tests whose place in the list of test names, sorted,
 * gives [index] when divided by [of]. The class itself is part 0, and small subclasses with no body
 * of their own are the other parts. Gradle runs each class in one test JVM, so the parts of one
 * design run side by side in different JVMs.
 *
 * Only when the system property `test.parts` is `on`: the build sets it for a whole run. Without it
 * (a run with `--tests`, or from the IDE) part 0 runs all tests and the other parts run none.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Part(val index: Int, val of: Int)

/** [RobolectricTestRunner] that runs only the tests of the [Part] of its class. */
class PartRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

    init {
        val part = requireNotNull(testClass.getAnnotation(Part::class.java)) { "$testClass has no @Part" }
        val partsOn = System.getProperty("test.parts") == "on"
        if (partsOn || part.index != 0) {
            val names = children.map { it.name }.distinct().sorted()
            val mine = if (partsOn) names.filterIndexed { i, _ -> i % part.of == part.index }.toSet() else emptySet()
            try {
                filter(object : Filter() {
                    override fun shouldRun(description: Description) = description.methodName == null || description.methodName in mine
                    override fun describe() = "part ${part.index} of ${part.of}"
                })
            } catch (_: NoTestsRemainException) {
                // A part with no tests in this run: it runs nothing
            }
        }
    }
}
