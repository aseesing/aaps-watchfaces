// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/SunTimes.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.

import type { LocalDate } from "./zoneTime.ts";
import { dayOfYear, isLeapYear, startOfDayUtc } from "./zoneTime.ts";

/** What the sun does on one day (`SunTimes.Result`). Look at `kind` to know which of the three it is. */
export type Result = RiseAndSet | AlwaysUp | AlwaysDown;

/** The sun rises at [rise] and sets at [set], both in epoch ms. */
export interface RiseAndSet {
    readonly kind: "RiseAndSet";
    readonly rise: number;
    readonly set: number;
}

/** The sun does not set on this day (summer near the poles). */
export interface AlwaysUp {
    readonly kind: "AlwaysUp";
}

/** The sun does not rise on this day (winter near the poles). */
export interface AlwaysDown {
    readonly kind: "AlwaysDown";
}

/** The angle from straight up to the sun's centre at sunrise and sunset: refraction and the sun's own size, in degrees. */
const SUNRISE_ZENITH = 90.833;

/**
 * Sunrise and sunset for one day at one place, with the NOAA formulas (the simple form, good to a
 * few minutes between the polar circles). Pure functions only: no clock, no state.
 */
export const SunTimes = {

    /**
     * Sunrise and sunset on [date] at [latitude] and [longitude], in degrees, north and east
     * positive. [date] is taken as a day in UTC, which is close enough for any place on land.
     */
    of(date: LocalDate, latitude: number, longitude: number): Result {
        // The year as an angle, at noon of the day
        const daysInYear = isLeapYear(date.year) ? 366 : 365;
        const gamma = 2 * Math.PI / daysInYear * (dayOfYear(date) - 1 + 0.5);
        // Equation of time in minutes, and the sun's declination in radians
        const eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma) - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        const decl = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma) - 0.006758 * Math.cos(2 * gamma) +
            0.000907 * Math.sin(2 * gamma) - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);
        const lat = toRadians(latitude);
        const cosHourAngle = Math.cos(toRadians(SUNRISE_ZENITH)) / (Math.cos(lat) * Math.cos(decl)) - Math.tan(lat) * Math.tan(decl);
        if (cosHourAngle < -1) return { kind: "AlwaysUp" };
        if (cosHourAngle > 1) return { kind: "AlwaysDown" };
        const hourAngle = toDegrees(Math.acos(cosHourAngle));
        // Minutes after midnight UTC
        const solarNoon = 720 - 4 * longitude - eqTime;
        const midnight = startOfDayUtc(date);
        const at = (minutes: number): number => midnight + toLong(minutes * MINUTE_MS);
        return { kind: "RiseAndSet", rise: at(solarNoon - 4 * hourAngle), set: at(solarNoon + 4 * hourAngle) };
    }
};

const MINUTE_MS = 60_000;

/** Java's `Math.toRadians`. */
function toRadians(degrees: number): number {
    return degrees * 0.017453292519943295;
}

/** Java's `Math.toDegrees`. */
function toDegrees(radians: number): number {
    return radians * 57.29577951308232;
}

/** Kotlin's `Double.toLong()`: the part after the point is cut off, and "not a number" gives 0. */
function toLong(value: number): number {
    return Number.isNaN(value) ? 0 : Math.trunc(value);
}
