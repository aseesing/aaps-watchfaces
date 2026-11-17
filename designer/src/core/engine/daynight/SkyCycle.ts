// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/SkyCycle.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.

/**
 * Where the sun or the moon is in its round from rising to rising, as one number from 0 to 1:
 *
 * - 0 when it rises, 0.25 halfway its time in the sky, 0.5 when it sets,
 * - 0.75 halfway its time below the horizon, and 1 when it rises again.
 *
 * So a design can move a picture once around a circle: the half above the horizon by day (for the sun)
 * and the half below it when the body is down. The time up and the time down each get one half of the
 * circle, however long they are.
 *
 * Pure functions only: no clock, no state.
 */
export const SkyCycle = {

    /** The value when the body does not set within [SEARCH_MS]: high in the sky. */
    ALWAYS_UP: 0.25,

    /** The value when the body does not rise within [SEARCH_MS]: deep below the horizon. */
    ALWAYS_DOWN: 0.75,

    /** How far before and after the moment to look for the last and the next rising or setting. */
    SEARCH_MS: 2 * 24 * 60 * 60_000,

    /**
     * The place of a body in its round at [epochMs]. [height] says how high the body is above the
     * height where it rises and sets, at a moment: above 0 it is up.
     */
    at(epochMs: number, height: (epochMs: number) => number): number {
        const up = height(epochMs) > 0;
        const last = crossing(epochMs, -STEP_MS, up, height);
        const next = crossing(epochMs, STEP_MS, up, height);
        if (last === null || next === null) return up ? SkyCycle.ALWAYS_UP : SkyCycle.ALWAYS_DOWN;
        const part = coerceIn((epochMs - last) / (next - last), 0.0, 1.0);
        return up ? part / 2 : 0.5 + part / 2;
    }
};

/** The steps of the search. A body is never up or down for a shorter time than this (moon and sun: hours). */
const STEP_MS = 10 * 60_000;

/** How exact a rising or setting is found: half a minute. */
const EXACT_MS = 30_000;

/**
 * The first moment from [epochMs] in the direction of [step] where the body is no longer [up],
 * or null when it stays so within [SEARCH_MS]. Found in steps, then made exact by halving.
 */
function crossing(epochMs: number, step: number, up: boolean, height: (epochMs: number) => number): number | null {
    let inside = epochMs;
    let t = epochMs + step;
    while (Math.abs(t - epochMs) <= SkyCycle.SEARCH_MS) {
        if (height(t) > 0 !== up) {
            let outside = t;
            while (Math.abs(outside - inside) > EXACT_MS) {
                // Whole ms, as the Kotlin `Long`
                const middle = Math.trunc((inside + outside) / 2);
                if (height(middle) > 0 === up) inside = middle; else outside = middle;
            }
            return Math.trunc((inside + outside) / 2);
        }
        inside = t;
        t += step;
    }
    return null;
}

/** Kotlin's `coerceIn`. */
function coerceIn(value: number, min: number, max: number): number {
    return value < min ? min : value > max ? max : value;
}
