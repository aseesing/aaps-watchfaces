package app.aaps.watchfaces.snapshot

import android.os.SystemClock
import org.mockito.MockedStatic
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * A frozen clock for the snapshot test, so two runs draw exactly the same pixels, today and next month.
 *
 * The engine reads the time in three ways, and each is fixed here:
 * - `SystemClock` (the wake-up animation): Robolectric's clock. It stands still until [setTo] moves it.
 * - `System.currentTimeMillis()` (ages of values, the graph): Robolectric answers it with its own clock
 *   in the classes it instruments. The test asks Robolectric to instrument the engine (`instrumentedPackages`).
 *   In the paused looper mode that time is the uptime of the clock, which [setTo] sets.
 * - `java.time.Clock.systemDefaultZone()` (behind `DateUtil.now()`: the hour, minute and date fields),
 *   `Instant.now()` and `LocalDateTime.now()` (the values `day`, `month`, `week_number`): replaced on
 *   this thread with the time of the clock.
 *
 * The time zone is Europe/Amsterdam and the language US English while the clock runs.
 * The clock only goes forward: Robolectric does not move it back. Each test starts a new one.
 */
class SnapshotClock {

    private var localNow: MockedStatic<LocalDateTime>? = null
    private var instantNow: MockedStatic<Instant>? = null
    private var javaClock: MockedStatic<Clock>? = null
    private var oldZone: TimeZone? = null
    private var oldLocale: Locale? = null

    /** The time now, as the engine sees it. */
    val now: Long get() = SystemClock.uptimeMillis()

    fun start() {
        oldZone = TimeZone.getDefault()
        oldLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        Locale.setDefault(Locale.US)
        localNow = Mockito.mockStatic(LocalDateTime::class.java, Mockito.CALLS_REAL_METHODS).apply {
            `when`<LocalDateTime> { LocalDateTime.now() }.thenAnswer { LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()) }
        }
        instantNow = Mockito.mockStatic(Instant::class.java, Mockito.CALLS_REAL_METHODS).apply {
            `when`<Instant> { Instant.now() }.thenAnswer { Instant.ofEpochMilli(now) }
        }
        javaClock = Mockito.mockStatic(Clock::class.java, Mockito.CALLS_REAL_METHODS).apply {
            `when`<Clock> { Clock.systemDefaultZone() }.thenAnswer { FrozenJavaClock(ZoneId.systemDefault()) }
            `when`<Clock> { Clock.systemUTC() }.thenAnswer { FrozenJavaClock(ZoneOffset.UTC) }
        }
    }

    /** Moves the clock to [epochMs]. It must not be earlier than [now]. */
    fun setTo(epochMs: Long) {
        check(SystemClock.setCurrentTimeMillis(epochMs)) { "The clock can only go forward: from $now to $epochMs" }
        check(now == epochMs) { "The clock is at $now, not at $epochMs" }
    }

    fun stop() {
        localNow?.close()
        localNow = null
        instantNow?.close()
        instantNow = null
        javaClock?.close()
        javaClock = null
        oldZone?.let { TimeZone.setDefault(it) }
        oldLocale?.let { Locale.setDefault(it) }
    }

    /** A `java.time.Clock` that reads this clock. */
    private inner class FrozenJavaClock(private val zone: ZoneId) : Clock() {

        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = FrozenJavaClock(zone)
        override fun millis(): Long = now
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    companion object {

        const val ZONE = "Europe/Amsterdam"

        /** [time] in the zone of the snapshots, as epoch ms. */
        fun epochMs(time: LocalDateTime): Long = time.atZone(ZoneId.of(ZONE)).toInstant().toEpochMilli()
    }
}
