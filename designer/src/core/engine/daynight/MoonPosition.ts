// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/MoonPosition.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.

/** The moon's place on the sky: [longitude] and [latitude] on the ecliptic, and its [parallax], all in degrees. */
export interface Ecliptic {
    readonly longitude: number;
    readonly latitude: number;
    readonly parallax: number;
}

/** The moon's place on the sky: right ascension and declination, in degrees. */
export interface Equatorial {
    readonly rightAscension: number;
    readonly declination: number;
}

/**
 * Where the moon is in the sky, from the time and the place alone: no phone and no internet needed.
 *
 * The low precision formulas of the Astronomical Almanac (good to about 0.3 degree between 1900
 * and 2100). That is a few minutes for moonrise and moonset, which is fine for a picture of the sky.
 * Pure functions only: no clock, no state.
 */
export const MoonPosition = {

    /** The moon at [epochMs], seen from the middle of the earth. */
    ecliptic(epochMs: number): Ecliptic {
        const t = (julianDay(epochMs) - J2000) / 36525.0;
        const s = (a: number, b: number): number => Math.sin(toRadians(a + b * t));
        const c = (a: number, b: number): number => Math.cos(toRadians(a + b * t));
        const longitude = 218.32 + 481267.881 * t +
            6.29 * s(135.0, 477198.87) - 1.27 * s(259.3, -413335.36) + 0.66 * s(235.7, 890534.22) +
            0.21 * s(269.9, 954397.74) - 0.19 * s(357.5, 35999.05) - 0.11 * s(186.5, 966404.03);
        const latitude = 5.13 * s(93.3, 483202.02) + 0.28 * s(228.0, 960400.89) - 0.28 * s(318.3, 6003.15) - 0.17 * s(217.6, -407332.21);
        const parallax = 0.9508 + 0.0518 * c(135.0, 477198.87) + 0.0095 * c(259.3, -413335.36) + 0.0078 * c(235.7, 890534.22) + 0.0028 * c(269.9, 954397.74);
        return { longitude: normalize(longitude), latitude, parallax };
    },

    /** The moon at [epochMs] in right ascension and declination. */
    equatorial(epochMs: number): Equatorial {
        const moon = MoonPosition.ecliptic(epochMs);
        const lambda = toRadians(moon.longitude);
        const beta = toRadians(moon.latitude);
        // The tilt of the earth's axis, 23.44 degrees: its cosine and sine
        const l = Math.cos(beta) * Math.cos(lambda);
        const m = 0.9175 * Math.cos(beta) * Math.sin(lambda) - 0.3978 * Math.sin(beta);
        const n = 0.3978 * Math.cos(beta) * Math.sin(lambda) + 0.9175 * Math.sin(beta);
        return { rightAscension: normalize(toDegrees(Math.atan2(m, l))), declination: toDegrees(Math.asin(n)) };
    },

    /**
     * How high the moon is above the height where it rises and sets, in degrees, at [epochMs] seen
     * from [latitude] and [longitude] (degrees, north and east positive). Above 0 the moon is up.
     *
     * The height of rising (Meeus: 0.7275 x parallax - 0.5667) takes the bending of the light by the
     * air, the size of the moon and the moon's parallax into account.
     */
    heightAboveRise(epochMs: number, latitude: number, longitude: number): number {
        const moon = MoonPosition.equatorial(epochMs);
        const parallax = MoonPosition.ecliptic(epochMs).parallax;
        const siderealTime = 280.46061837 + 360.98564736629 * (julianDay(epochMs) - J2000) + longitude;
        const hourAngle = toRadians(siderealTime - moon.rightAscension);
        const phi = toRadians(latitude);
        const delta = toRadians(moon.declination);
        const altitude = toDegrees(Math.asin(Math.sin(phi) * Math.sin(delta) + Math.cos(phi) * Math.cos(delta) * Math.cos(hourAngle)));
        return altitude - (0.7275 * parallax - 0.5667);
    }
};

const J2000 = 2451545.0;

function julianDay(epochMs: number): number {
    return epochMs / 86_400_000.0 + 2440587.5;
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
