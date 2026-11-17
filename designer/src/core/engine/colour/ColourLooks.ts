// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/colour/ColourLooks.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.
// The rows of the settings screen and the keys of the stored choices (`rows`, `choices`, `PREF_KEYS`,
// `TITLES`) are left out: they are Android only.

import type { JsonObject, JsonValue } from "../../json.ts";
import { deepCopy, isObject, keysOf, optObject } from "../../json.ts";
import { DayOverlay } from "../daynight/DayOverlay.ts";

/**
 * The colours a wearer can choose for a Custom watch face design: in the settings of the watch face
 * on the watch, or in the AAPS app, which sends them to the watch (`CwfSyncedSettings`). Private patch.
 *
 * A design offers up to two colours, in the object [JSON_KEY] (`colourLooks`):
 *
 * ```
 * "colourLooks": {
 *   "colour1": {"hour": {"fontColor": "@colour"}, "cover_chart": {"color": "@colour"}},
 *   "colour2": {"day": {"fontColor": "@colour"}}
 * }
 * ```
 *
 * Each one is a look like the day look (see [DayOverlay]): it is merged into the design, key by key.
 * Before that every text [PLACEHOLDER] in it becomes the colour the wearer chose, so `@colour` becomes
 * `#00E5FF`. `#80@colour` becomes `#8000E5FF`: the same colour, half see-through.
 *
 * The watch and the app show the row "Main colour" for `colour1` and "Second colour" for `colour2`,
 * only for a design that has them. The first value of each list, [AS_DESIGNED], leaves the design as it is.
 *
 * The colours are laid over the design itself, before the day look and the weather looks, so a day
 * look can still change them.
 *
 * Holds the last result, so the watch does not merge again on every frame: one instance per watch face.
 */
export class ColourLooks {

    private lastSource: JsonObject | null = null;
    private lastChoices: readonly string[] | null = null;
    private lastResult: JsonObject | null = null;

    /**
     * [design] with the [choices] (one per slot, see [SLOTS]) laid over it. The same object as last time
     * while [design] and [choices] stay the same, and [design] itself when nothing is chosen.
     */
    apply(design: JsonObject, choices: readonly string[]): JsonObject {
        if (this.lastResult !== null && this.lastSource === design && sameList(this.lastChoices, choices)) return this.lastResult;
        const result = ColourLooks.lookOf(design, choices);
        this.lastSource = design;
        // A copy, so a later change of the list of the caller is seen
        this.lastChoices = choices.slice();
        this.lastResult = result;
        return result;
    }

    /** The key of the colour choices in the design json. */
    static readonly JSON_KEY = "colourLooks";

    /** The text in a look that becomes the chosen colour. */
    static readonly PLACEHOLDER = "@colour";

    /** The value that leaves the design as it is. */
    static readonly AS_DESIGNED = "design";

    /** The colour slots a design can offer, in the order of the choices. */
    static readonly SLOTS: readonly string[] = ["colour1", "colour2"];

    /** True when [design] offers the colour [slot]. */
    static has(design: JsonObject, slot: string): boolean {
        return optObject(optObject(design, ColourLooks.JSON_KEY), slot) !== null;
    }

    /**
     * [design] with [choices] laid over it, as a new object; [design] itself is not changed. [design]
     * itself when no slot it offers has a colour chosen. A choice that is not a colour (`#RRGGBB`)
     * counts as [AS_DESIGNED].
     */
    static lookOf(design: JsonObject, choices: readonly string[]): JsonObject {
        const looks = optObject(design, ColourLooks.JSON_KEY);
        if (looks === null) return design;
        const chosen: Array<[JsonObject, string]> = [];
        ColourLooks.SLOTS.forEach((slot, i) => {
            const colour = choices[i];
            if (typeof colour !== "string" || !COLOUR.test(colour)) return;
            const look = optObject(looks, slot);
            if (look !== null) chosen.push([look, colour]);
        });
        if (chosen.length === 0) return design;
        const result = deepCopy(design);
        for (const [look, colour] of chosen) DayOverlay.merge(result, filledIn(look, colour.substring(1)) as JsonObject);
        return result;
    }
}

const COLOUR = /^#[0-9A-Fa-f]{6}$/;

/** A copy of [value] with every [PLACEHOLDER] in its texts replaced by [rgb] (`RRGGBB`, the `#` is in the text). */
function filledIn(value: JsonValue, rgb: string): JsonValue {
    if (isObject(value)) {
        const copy: JsonObject = {};
        for (const key of keysOf(value)) copy[key] = filledIn(value[key], rgb);
        return copy;
    }
    if (Array.isArray(value)) return value.map((item) => filledIn(item, rgb));
    if (typeof value === "string") {
        if (!value.endsWith(ColourLooks.PLACEHOLDER)) return value;
        const before = value.substring(0, value.length - ColourLooks.PLACEHOLDER.length);
        return (before.length === 0 ? "#" : before) + rgb;
    }
    return value;
}

/** True when [a] and [b] hold the same texts in the same order (the `==` of two Kotlin lists). */
function sameList(a: readonly string[] | null, b: readonly string[]): boolean {
    return a !== null && a.length === b.length && a.every((item, i) => item === b[i]);
}
