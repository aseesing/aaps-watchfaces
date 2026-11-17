// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/Orbit.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.

import type { JsonObject } from "../../json.ts";
import { optDouble, optObject, optString } from "../../json.ts";

/**
 * A view of a Custom watch face design that moves along an arc, like the sun along the edge of the
 * screen. Read from the optional `orbit` key of a view in the design json:
 *
 * `"orbit": {"valueKey": "sun_progress", "centerX": 200, "centerY": 200, "radius": 188, "startAngle": 200, "endAngle": 340}`
 *
 * The value (0..1) of [valueKey] places the middle of the view on the arc: 0 at [startAngle], 1 at
 * [endAngle]. Angles are in degrees on the screen: 0 is to the right, 90 down, 180 left, 270 up, so
 * 200 to 340 goes from the left over the top to the right. All in the 400 x 400 design space.
 */
export class Orbit {

    readonly valueKey: string;
    readonly centerX: number;
    readonly centerY: number;
    readonly radius: number;
    readonly startAngle: number;
    readonly endAngle: number;

    constructor(valueKey: string, centerX = 200, centerY = 200, radius = 188, startAngle = 200, endAngle = 340) {
        this.valueKey = valueKey;
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        this.startAngle = startAngle;
        this.endAngle = endAngle;
    }

    /** The point on the arc for [value] (cut to 0..1), in the design space: x and y. */
    point(value: number): [number, number] {
        const angle = toRadians(this.startAngle + (this.endAngle - this.startAngle) * coerceIn(value, 0.0, 1.0));
        return [this.centerX + this.radius * Math.cos(angle), this.centerY + this.radius * Math.sin(angle)];
    }

    /** The key of an orbit in a view of the design json. */
    static readonly JSON_KEY = "orbit";

    /** The orbit of a view, or null when it has none or no value key. */
    static read(view: JsonObject | null | undefined): Orbit | null {
        const json = optObject(view, Orbit.JSON_KEY);
        if (json === null) return null;
        const key = optString(json, "valueKey");
        if (key.length === 0) return null;
        const defaults = new Orbit(key);
        const float = (name: string, fallback: number): number => optDouble(json, name, fallback);
        return new Orbit(
            key,
            float("centerX", defaults.centerX),
            float("centerY", defaults.centerY),
            float("radius", defaults.radius),
            float("startAngle", defaults.startAngle),
            float("endAngle", defaults.endAngle)
        );
    }
}

/** Java's `Math.toRadians`. */
function toRadians(degrees: number): number {
    return degrees * 0.017453292519943295;
}

/** Kotlin's `coerceIn`. */
function coerceIn(value: number, min: number, max: number): number {
    return value < min ? min : value > max ? max : value;
}
