// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/DayOverlay.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.
// Where Kotlin has a `CwfResDataMap` this file has a `Map` from the name of a file (without its ending) to anything.

import type { JsonObject } from "../../json.ts";
import { deepCopy, isObject, keysOf, opt, optObject, optString } from "../../json.ts";

/**
 * The day look and the weather looks of a Custom watch face design. Private patch.
 *
 * A design can have two optional objects:
 * - [JSON_KEY] (`dayLook`): laid over the design from sunrise to sunset,
 * - [WEATHER_KEY] (`weatherLooks`): looks for the weather, by the name [lookName] gives, like
 *   `day_cloudy` or `night_rain`, or by the weather alone (`cloudy`) for every time of day. The
 *   one that fits is laid over the result last.
 *
 * Laying one object over another:
 * - an object is merged key by key into the object of the same key, as deep as it goes,
 * - `null` removes the key (for example `"rain": null` in the animation),
 * - every other value replaces the value of the design.
 *
 * Pictures: in the day a picture whose name ends with [IMAGE_SUFFIX] replaces the picture without
 * it (`BackgroundDay` is shown instead of `Background`). And a look can name pictures to swap
 * under [IMAGES_KEY]: `"images": {"Background": "BackgroundRain"}` shows `BackgroundRain` where
 * the design shows `Background`.
 *
 * Pure functions only; the watch face decides when it is day and what the weather is.
 */
export const DayOverlay = {

    /** The key of the day look in the design json. */
    JSON_KEY: "dayLook",

    /** The key of the weather looks in the design json. */
    WEATHER_KEY: "weatherLooks",

    /** The key in a look that names the pictures to swap. */
    IMAGES_KEY: "images",

    /** The end of the name of a picture that replaces another one in the day. */
    IMAGE_SUFFIX: "Day",

    /** True when [design] has a day look at all. */
    hasDayLook(design: JsonObject): boolean {
        return optObject(design, DayOverlay.JSON_KEY) !== null;
    },

    /** True when [design] has a look for any weather. */
    hasWeatherLooks(design: JsonObject): boolean {
        return optObject(design, DayOverlay.WEATHER_KEY) !== null;
    },

    /** The name of the weather look for [day] or night and [weather], like `day_rain`. */
    lookName(day: boolean, weather: string): string {
        return (day ? "day_" : "night_") + weather;
    },

    /**
     * [design] as it looks in the [day] or at night, in [weather] (null when it is not known), as a
     * new object; [design] itself is not changed. The looks themselves are left out of the result.
     * The pictures to swap are in the result under [IMAGES_KEY], for [resources].
     */
    apply(design: JsonObject, day = true, weather: string | null = null): JsonObject {
        const result = deepCopy(design);
        delete result[DayOverlay.JSON_KEY];
        delete result[DayOverlay.WEATHER_KEY];
        delete result[DayOverlay.IMAGES_KEY];
        if (day) {
            const dayLook = optObject(design, DayOverlay.JSON_KEY);
            if (dayLook !== null) DayOverlay.merge(result, dayLook);
        }
        // The look for day or night in this weather, else the look for this weather at any time
        if (weather !== null) {
            const looks = optObject(design, DayOverlay.WEATHER_KEY);
            const look = optObject(looks, DayOverlay.lookName(day, weather)) ?? optObject(looks, weather);
            if (look !== null) DayOverlay.merge(result, look);
        }
        return result;
    },

    /** [resources] with every `XDay` picture put in the place of `X`. The `XDay` entries stay too. */
    dayResources<T>(resources: ReadonlyMap<string, T>): Map<string, T> {
        const result = new Map(resources);
        resources.forEach((data, name) => {
            if (name.length > DayOverlay.IMAGE_SUFFIX.length && name.endsWith(DayOverlay.IMAGE_SUFFIX))
                result.set(name.substring(0, name.length - DayOverlay.IMAGE_SUFFIX.length), data);
        });
        return result;
    },

    /**
     * The pictures for a [look] made by [apply]: in the [day] the `XDay` pictures in the place of `X`,
     * and then the swaps the look names under [IMAGES_KEY]. A swap to a picture the zip does not
     * have is left out.
     */
    resources<T>(resources: ReadonlyMap<string, T>, look: JsonObject, day: boolean): Map<string, T> {
        const result = day ? DayOverlay.dayResources(resources) : new Map(resources);
        const swaps = optObject(look, DayOverlay.IMAGES_KEY);
        for (const target of keysOf(swaps)) {
            const data = resources.get(optString(swaps, target));
            if (data !== undefined) result.set(target, data);
        }
        return result;
    },

    /** Merges [overlay] into [target], as described above. Also used for the colour looks (`ColourLooks`). */
    merge(target: JsonObject, overlay: JsonObject): void {
        for (const key of keysOf(overlay)) {
            const value = opt(overlay, key);
            const current = optObject(target, key);
            if (value === undefined || value === null) delete target[key];
            else if (isObject(value) && current !== null) DayOverlay.merge(current, value);
            else if (isObject(value)) target[key] = deepCopy(value);
            else target[key] = value;
        }
    }
};
