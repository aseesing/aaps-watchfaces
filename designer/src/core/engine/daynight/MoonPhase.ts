// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/MoonPhase.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.

/**
 * Where the moon is in its cycle, from the date alone: no phone and no internet needed.
 *
 * Counts whole synodic months from a known new moon. The real cycle is a few hours longer or
 * shorter than the mean one, so the result can be off by up to about half a day. That is fine for
 * a picture of the moon.
 */
export const MoonPhase = {

    /** The mean time from one new moon to the next, in days. */
    SYNODIC_DAYS: 29.530588853,

    /**
     * The phase at [epochMs]: 0 is new moon, 0.25 first quarter, 0.5 full moon, 0.75 last
     * quarter, and back to 1 = 0. Always in 0 until 1.
     */
    at(epochMs: number): number {
        const cycles = (epochMs - NEW_MOON_MS) / DAY_MS / MoonPhase.SYNODIC_DAYS;
        const phase = cycles - Math.floor(cycles);
        return phase >= 1.0 ? 0.0 : phase;
    }
};

/** A known new moon: 6 January 2000, 18:14 UTC. */
const NEW_MOON_MS = 947_182_440_000;

const DAY_MS = 86_400_000.0;
