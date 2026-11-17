// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/MoonPicture.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.
// Where Kotlin has a `Bitmap` this file has a `Surface` (the picture that is made) or a `Picture` (the photo).

import type { JsonObject } from "../../json.ts";
import { optBoolean } from "../../json.ts";
import type { Picture, Platform, Surface } from "../../platform.ts";
import type { GeoPoint } from "./DayNightClock.ts";
import { sameGeoPoint } from "./DayNightClock.ts";
import type { Look } from "./MoonLight.ts";
import { MoonLight } from "./MoonLight.ts";

/** What the last picture was made for: the Kotlin `Triple` of the time (in steps of `REDRAW_MS`), the size and the place. */
interface MadeFor {
    readonly step: number;
    readonly size: number;
    readonly place: GeoPoint | null;
}

/**
 * The moon as you see it now, drawn from one photo of the full moon: the dark part is
 * made dark, and the moon is turned the way it stands in your sky (see [MoonLight]).
 *
 * A view of a Custom watch face design asks for it with `"moonLight": true`. The photo comes with the
 * watch app (NASA's Scientific Visualization Studio, public domain), so a design does not need any
 * moon pictures, and it fits a view of any size. Here the photo is `assets/drawable/personal_moon_full.jpg`.
 *
 * The picture is made again every [REDRAW_MS], or when the size or the place changes. In between the
 * same picture is used, so this costs nearly nothing.
 */
export class MoonPicture {

    private readonly platform: Platform;
    private readonly photo: Picture | null;
    private madeFor: MadeFor | null = null;
    private made: Surface | null = null;

    /**
     * @param platform makes the surface the moon is drawn on
     * @param photo the photo of the full moon, already decoded. Null if it could not be read: then there is no moon.
     */
    constructor(platform: Platform, photo: Picture | null) {
        this.platform = platform;
        this.photo = photo;
    }

    /** The moon at [epochMs] seen from [place] (north up without one), [size] pixels wide; null if it cannot be made. */
    bitmap(epochMs: number, place: GeoPoint | null, size: number): Surface | null {
        // Whole pixels, as the Kotlin `Int`
        size = Math.trunc(size);
        if (!(size > 0)) return null;
        const key: MadeFor = { step: Math.trunc(epochMs / MoonPicture.REDRAW_MS), size, place };
        const last = this.madeFor;
        if (last === null || key.step !== last.step || key.size !== last.size || !sameGeoPoint(key.place, last.place)) {
            this.made = null;
            if (this.photo !== null) {
                try {
                    this.made = MoonPicture.draw(this.platform, this.photo, size, MoonLight.at(epochMs, place));
                } catch {
                    // No moon, as on the watch
                }
            }
            this.madeFor = key;
        }
        return this.made;
    }

    /** The key of a view in the design json that shows the moon. */
    static readonly JSON_KEY = "moonLight";

    /** How often the moon is drawn again. The tilt changes the most: about 15 degrees an hour. */
    static readonly REDRAW_MS = 15 * 60_000;

    /** How bright the dark part is, so the moon can still be found at new moon (the light of the earth). */
    static readonly DARK = 0.3;

    /**
     * How much the dark part covers what is behind it. Partly see-through, as in the real sky: by day
     * the blue shows through it, at night it is a faint grey (about [DARK] x [DARK_COVER] on black).
     */
    static readonly DARK_COVER = 0.45;

    /** True when [view] asks for the moon. */
    static isOn(view: JsonObject | null | undefined): boolean {
        return optBoolean(view, MoonPicture.JSON_KEY) === true;
    }

    /**
     * The [photo] (the full moon, north up, the disc filling the square) as it looks with [look],
     * in a square of [size] pixels: round, with soft edges, the rest see-through.
     */
    static draw(platform: Platform, photo: Picture, size: number, look: Look): Surface {
        const image = platform.createSurface(size, size);
        const canvas = image.getContext("2d");
        const c = size / 2;
        canvas.save();
        // The canvas turns with the clock, the angles of the look against it
        canvas.translate(c, c);
        canvas.rotate(toRadians(-look.northAngle));
        canvas.translate(-c, -c);
        // A little bigger than the square, so the dark edge around the disc of the photo is cut off
        const over = size * MoonPicture.PHOTO_OVERSIZE;
        canvas.imageSmoothingEnabled = true;
        canvas.drawImage(photo.source, -over, -over, size + 2 * over, size + 2 * over);
        canvas.restore();
        const pixels = canvas.getImageData(0, 0, size, size);
        MoonPicture.darken(pixels.data, size, look);
        canvas.putImageData(pixels, 0, 0);
        return image;
    }

    /**
     * The part of [draw] that works on the pixels: makes the dark part of the moon dark and all that
     * is outside the disc see-through. In Kotlin this is the loop inside `draw`.
     *
     * @param pixels the picture of [size] x [size] pixels, row by row from the top, four numbers
     *   (0..255) for each pixel: red, green, blue, alpha. This is the `data` of an `ImageData`. Changed in place.
     */
    static darken(pixels: Uint8ClampedArray, size: number, look: Look): void {
        const r = size / 2.0;
        // The lit side on the screen: x to the right, y up
        const angle = toRadians(look.limbAngle);
        const litX = -Math.sin(angle);
        const litY = Math.cos(angle);
        for (let row = 0; row < size; row++) for (let column = 0; column < size; column++) {
            const x = (column + 0.5 - r) / r;
            const y = (r - row - 0.5) / r;
            const distance = Math.sqrt(x * x + y * y);
            const index = (row * size + column) * 4;
            // Soft over one pixel at the rim and at the line between light and dark
            const rim = coerceIn((1 - distance) * r + 0.5, 0.0, 1.0);
            if (rim <= 0.0) {
                pixels[index] = 0;
                pixels[index + 1] = 0;
                pixels[index + 2] = 0;
                pixels[index + 3] = 0;
                continue;
            }
            const along = x * litX + y * litY;
            const across = x * litY - y * litX;
            const halfWidth = Math.sqrt(Math.max(1 - across * across, 0.0));
            const light = coerceIn((along - look.terminator * halfWidth) * r + 0.5, 0.0, 1.0);
            const shade = MoonPicture.DARK + (1 - MoonPicture.DARK) * light;
            const cover = MoonPicture.DARK_COVER + (1 - MoonPicture.DARK_COVER) * light;
            // Cut off, not rounded, as the Kotlin `toInt()`
            pixels[index] = Math.trunc(pixels[index] * shade);
            pixels[index + 1] = Math.trunc(pixels[index + 1] * shade);
            pixels[index + 2] = Math.trunc(pixels[index + 2] * shade);
            pixels[index + 3] = Math.trunc(rim * cover * 255);
        }
    }

    /** How much the photo is drawn bigger than the square, on each side. */
    private static readonly PHOTO_OVERSIZE = 0.015;
}

/** Java's `Math.toRadians`. */
function toRadians(degrees: number): number {
    return degrees * 0.017453292519943295;
}

/** Kotlin's `coerceIn`. */
function coerceIn(value: number, min: number, max: number): number {
    return value < min ? min : value > max ? max : value;
}
