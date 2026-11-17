// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/MoonLight.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.

import type { GeoPoint } from "./DayNightClock.ts";
import { MoonPosition } from "./MoonPosition.ts";

/**
 * The moon on the screen (`MoonLight.Look`). Angles are in degrees, counted from straight up and
 * against the clock (90 is to the left), like a picture of the sky.
 */
export class Look {

    /**
     * Where the line between light and dark lies: 1 at new moon (all dark), 0 at a quarter (half
     * lit), -1 at full moon. The part that is lit is (1 - terminator) / 2.
     */
    readonly terminator: number;

    /** The direction the lit side points to. */
    readonly limbAngle: number;

    /** The direction of the moon's north. Without a place this is 0: north up. */
    readonly northAngle: number;

    constructor(terminator: number, limbAngle: number, northAngle: number) {
        this.terminator = terminator;
        this.limbAngle = limbAngle;
        this.northAngle = northAngle;
    }

    /** The part of the moon that is lit, 0 to 1. */
    get litFraction(): number {
        return (1 - this.terminator) / 2;
    }
}

/**
 * How the moon looks at a moment: how much of it is lit, where the lit side points and how the moon
 * is turned, as you see it in your sky. No phone and no internet needed, only the time and the place.
 *
 * Low precision (about a degree), which is plenty for a small picture. Pure functions only.
 * The formulas are from Meeus, Astronomical Algorithms, chapters 14 and 48.
 */
export const MoonLight = {

    /**
     * The moon at [epochMs]. With a [place] the picture is turned the way the moon stands in the sky
     * there (up is straight up from the ground). Without one, north is up.
     */
    at(epochMs: number, place: GeoPoint | null): Look {
        const moon = MoonPosition.equatorial(epochMs);
        const [sunRa, sunDec] = MoonLight.sun(epochMs);
        const ra = toRadians(moon.rightAscension);
        const dec = toRadians(moon.declination);
        const ra0 = toRadians(sunRa);
        const dec0 = toRadians(sunDec);
        // The angle between the sun and the moon, seen from the earth
        const elongationCos = Math.sin(dec0) * Math.sin(dec) + Math.cos(dec0) * Math.cos(dec) * Math.cos(ra0 - ra);
        // Where the lit side points, from the north of the sky over the east (Meeus 48.5)
        const limb = toDegrees(Math.atan2(Math.cos(dec0) * Math.sin(ra0 - ra), Math.sin(dec0) * Math.cos(dec) - Math.cos(dec0) * Math.sin(dec) * Math.cos(ra0 - ra)));
        // How far the north of the sky is turned away from straight up (Meeus 14.1)
        let parallactic = 0.0;
        if (place !== null) {
            const hourAngle = toRadians(siderealDegrees(epochMs) + place.longitude - moon.rightAscension);
            const phi = toRadians(place.latitude);
            parallactic = toDegrees(Math.atan2(Math.sin(hourAngle), Math.tan(phi) * Math.cos(dec) - Math.sin(dec) * Math.cos(hourAngle)));
        }
        return new Look(elongationCos, normalize(limb - parallactic), normalize(-parallactic));
    },

    /** The sun's right ascension and declination at [epochMs], in degrees (good to about 0.01 degree). */
    sun(epochMs: number): [number, number] {
        const n = julianDay(epochMs) - J2000;
        const meanLongitude = 280.460 + 0.9856474 * n;
        const anomaly = toRadians(357.528 + 0.9856003 * n);
        const longitude = toRadians(meanLongitude + 1.915 * Math.sin(anomaly) + 0.020 * Math.sin(2 * anomaly));
        const tilt = toRadians(23.439 - 0.0000004 * n);
        const ra = toDegrees(Math.atan2(Math.cos(tilt) * Math.sin(longitude), Math.cos(longitude)));
        const dec = toDegrees(Math.asin(Math.sin(tilt) * Math.sin(longitude)));
        return [normalize(ra), dec];
    }
};

const J2000 = 2451545.0;

function julianDay(epochMs: number): number {
    return epochMs / 86_400_000.0 + 2440587.5;
}

/** The sidereal time at Greenwich, in degrees. */
function siderealDegrees(epochMs: number): number {
    return 280.46061837 + 360.98564736629 * (julianDay(epochMs) - J2000);
}

function normalize(degrees: number): number {
    return ((degrees % 360.0) + 360.0) % 360.0;
}

/** Java's `Math.toRadians`. */
function toRadians(degrees: number): number {
    return degrees * 0.017453292519943295;
}

/** Java's `Math.toDegrees`. */
function toDegrees(radians: number): number {
    return radians * 57.29577951308232;
}
