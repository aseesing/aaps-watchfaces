// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/DayNightChoice.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.
// The two rows of the settings screen of the watch (`activeRow`, `alwaysOnRow`) are left out: they are Android only.

const AUTO = "auto";
const DAY = "day";
const NIGHT = "night";
const AS_ACTIVE = "active";

/**
 * The two watch settings of the day look (see [DayOverlay]). Private patch. Pure functions only.
 *
 * - "Day / night look", for the active screen: [AUTO] (from sunrise to sunset, the default), [DAY]
 *   or [NIGHT].
 * - "Always-on look", for the dim always-on screen: [NIGHT] (the default, a light screen costs
 *   battery there), [DAY], or [AS_ACTIVE] to follow the first setting.
 */
export const DayNightChoice = {

    AUTO,
    DAY,
    NIGHT,
    AS_ACTIVE,

    /** The values "Day / night look" can have. */
    ACTIVE_VALUES: new Set([AUTO, DAY, NIGHT]) as ReadonlySet<string>,

    /** The values "Always-on look" can have. */
    ALWAYS_ON_VALUES: new Set([NIGHT, DAY, AS_ACTIVE]) as ReadonlySet<string>,

    /**
     * The value to store for a setting the phone sent (see `CwfNight`), or null to leave the watch
     * setting as it is.
     *
     * A value is taken only when it differs from the one the phone sent last time ([lastSent]), so a
     * change made on the watch stays until the phone setting changes. Null and unknown values are
     * never taken: an older phone sends none.
     */
    fromPhone(sent: string | null, lastSent: string | null, allowed: ReadonlySet<string>): string | null {
        return sent !== null && allowed.has(sent) && sent !== lastSent ? sent : null;
    },

    /**
     * True when the day look shows.
     *
     * @param active the stored "Day / night look"; null or unknown counts as [AUTO]
     * @param alwaysOn the stored "Always-on look"; null or unknown counts as [NIGHT]
     * @param ambient true on the dim always-on screen
     * @param isDayNow whether it is between sunrise and sunset now; only asked for [AUTO]
     */
    showDay(active: string | null, alwaysOn: string | null, ambient: boolean, isDayNow: () => boolean): boolean {
        let chosen: string | null;
        if (!ambient) chosen = active;
        else if (alwaysOn === DAY) chosen = DAY;
        else if (alwaysOn === AS_ACTIVE) chosen = active;
        else chosen = NIGHT;
        if (chosen === DAY) return true;
        if (chosen === NIGHT) return false;
        return isDayNow();
    }
};
