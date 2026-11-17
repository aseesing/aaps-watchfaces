// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/DayNightClock.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.
// Where Kotlin has an `Instant` this file has epoch ms, and where it has a `ZoneId` the name of the zone.

import { MoonPhase } from "./MoonPhase.ts";
import { MoonPosition } from "./MoonPosition.ts";
import { SkyCycle } from "./SkyCycle.ts";
import type { Result } from "./SunTimes.ts";
import { SunTimes } from "./SunTimes.ts";
import type { LocalDate, LocalTime } from "./zoneTime.ts";
import { epochMsOf, localDate, plusDays, sameDate, startOfDay, systemZone, timeOf } from "./zoneTime.ts";

/** A place on earth, in degrees: north and east positive. */
export interface GeoPoint {
    readonly latitude: number;
    readonly longitude: number;
}

/** True when [a] and [b] are the same place, or both null. This is the `==` of the Kotlin data class. */
export function sameGeoPoint(a: GeoPoint | null, b: GeoPoint | null): boolean {
    if (a === null || b === null) return a === b;
    return Object.is(a.latitude, b.latitude) && Object.is(a.longitude, b.longitude);
}

interface Key {
    readonly date: LocalDate;
    readonly place: GeoPoint;
}

/** From when to when the sun is up, in epoch ms. */
interface Daylight {
    readonly start: number;
    readonly end: number;
}

/**
 * Says whether it is day or night: from sunrise to sunset it is day. The sun times come from
 * [location]; without a location the day runs from [FALLBACK_DAY_START] to [FALLBACK_DAY_END] local time.
 */
export class DayNightClock {

    private readonly location: () => GeoPoint | null;
    private readonly zone: () => string;

    private cachedKey: Key | null = null;
    private cachedSun: Result | null = null;

    /**
     * @param location the place to use, or null when it is not known; read on every question
     * @param zone the local time zone, as its IANA name ("Europe/Amsterdam"); read on every question,
     *   so a change of zone is followed. Without it: the zone of this computer.
     */
    constructor(location: () => GeoPoint | null, zone: () => string = systemZone) {
        this.location = location;
        this.zone = zone;
    }

    /** True when [epochMs] is between sunrise and sunset. */
    isDay(epochMs: number): boolean {
        const day = this.daylightAround(epochMs);
        if (day === null) return false;
        return epochMs >= day.start && epochMs < day.end;
    }

    /**
     * How far the sun is through its day at [epochMs]: 0 at sunrise, 0.5 around noon, 1 at sunset.
     * Before sunrise it is 0 and after sunset 1. When the sun does not rise at all on that day: 0.
     */
    sunProgress(epochMs: number): number {
        const day = this.daylightAround(epochMs);
        if (day === null) return 0.0;
        const length = day.end - day.start;
        if (length <= 0) return 0.0;
        return coerceIn((epochMs - day.start) / length, 0.0, 1.0);
    }

    /**
     * How far the night is at [epochMs]: 0 at sunset, 0.5 in the middle of the night, 1 at the next
     * sunrise. In the day it is 0. When the sun does not rise at all on that day, the night is the
     * whole local day.
     */
    nightProgress(epochMs: number): number {
        const date = localDate(epochMs, this.zone());
        const today = this.daylightOn(date);
        if (today !== null && epochMs >= today.start && epochMs < today.end) return 0.0;
        let start: number;
        let end: number;
        if (today !== null && epochMs >= today.end) {
            start = today.end;
            end = this.daylightOn(plusDays(date, 1))?.start ?? startOfDay(plusDays(date, 2), this.zone());
        } else {
            start = this.daylightOn(plusDays(date, -1))?.end ?? startOfDay(date, this.zone());
            end = today?.start ?? startOfDay(plusDays(date, 1), this.zone());
        }
        const length = end - start;
        if (length <= 0) return 0.0;
        return coerceIn((epochMs - start) / length, 0.0, 1.0);
    }

    /**
     * Where the sun is in its round at [epochMs] (see [SkyCycle]): 0 at sunrise, 0.5 at sunset, 1 at
     * the next sunrise. The day is the first half, the night the second, so a picture of the sun can go
     * over the horizon by day and under it at night.
     */
    sunCycle(epochMs: number): number {
        return this.isDay(epochMs) ? this.sunProgress(epochMs) / 2 : 0.5 + this.nightProgress(epochMs) / 2;
    }

    private cachedMoonMinute: number | null = null;
    private cachedMoonPlace: GeoPoint | null = null;
    private cachedMoon = 0.0;

    /**
     * Where the moon is in its round at [epochMs] (see [SkyCycle]): 0 at moonrise, 0.5 at moonset, 1
     * at the next moonrise. Worked out for the stored place, once per minute. The real moon rises at
     * any hour: a full moon around sunset, a new moon around sunrise.
     *
     * Without a place the moon follows the sun, late by its phase: a day of 24 hours for a whole cycle
     * of the moon. That is close to the real moon at a place near the equator.
     */
    moonCycle(epochMs: number): number {
        const place = this.location();
        if (place === null || !isValid(place)) return this.sunCycle(epochMs - toLong(MoonPhase.at(epochMs) * DAY_MS));
        const minute = Math.trunc(epochMs / MINUTE_MS);
        if (minute === this.cachedMoonMinute && sameGeoPoint(place, this.cachedMoonPlace)) return this.cachedMoon;
        const moon = SkyCycle.at(epochMs, (it) => MoonPosition.heightAboveRise(it, place.latitude, place.longitude));
        this.cachedMoonMinute = minute;
        this.cachedMoonPlace = place;
        this.cachedMoon = moon;
        return moon;
    }

    /**
     * The time of the next sunrise or sunset after [epochMs] at the stored place, whichever comes
     * first. Null without a place (the fixed hours are no real sun times), and in a polar day or night
     * when the sun does not rise or set in the next days.
     */
    nextSunEvent(epochMs: number): number | null {
        const place = this.location();
        if (place === null || !isValid(place)) return null;
        const today = localDate(epochMs, this.zone());
        for (let days = 0; days <= 2; days++) {
            const sun = this.sunOn(plusDays(today, days), place);
            if (sun.kind !== "RiseAndSet") continue;
            const next = [sun.rise, sun.set].sort((a, b) => a - b).find((it) => it > epochMs);
            if (next !== undefined) return next;
        }
        return null;
    }

    private daylightAround(epochMs: number): Daylight | null {
        return this.daylightOn(localDate(epochMs, this.zone()));
    }

    /**
     * The daylight of the local day [date]: from sunrise to sunset at the stored place, the whole
     * day when the sun does not set, null when it does not rise. Without a place: the fixed hours.
     */
    private daylightOn(date: LocalDate): Daylight | null {
        const zone = this.zone();
        const place = this.location();
        if (place === null || !isValid(place))
            return { start: epochMsOf(date, DayNightClock.FALLBACK_DAY_START, zone), end: epochMsOf(date, DayNightClock.FALLBACK_DAY_END, zone) };
        const sun = this.sunOn(date, place);
        switch (sun.kind) {
            case "RiseAndSet":
                return { start: sun.rise, end: sun.set };
            case "AlwaysUp":
                return { start: startOfDay(date, zone), end: startOfDay(plusDays(date, 1), zone) };
            case "AlwaysDown":
                return null;
        }
    }

    /** The sun times of [date] at [place], computed once per day and place. */
    private sunOn(date: LocalDate, place: GeoPoint): Result {
        const key = this.cachedKey;
        if (this.cachedSun !== null && key !== null && sameDate(date, key.date) && sameGeoPoint(place, key.place)) return this.cachedSun;
        const sun = SunTimes.of(date, place.latitude, place.longitude);
        this.cachedKey = { date, place };
        this.cachedSun = sun;
        return sun;
    }

    /** The value of [nextSunEvent] for a design (`dynData`): a time, shown with `@time` (see `DesignTimeText`). */
    static readonly NEXT_SUN_EVENT_KEY = "sun_next_time";

    static readonly FALLBACK_DAY_START: LocalTime = timeOf(7, 0);
    static readonly FALLBACK_DAY_END: LocalTime = timeOf(19, 0);
}

function isValid(place: GeoPoint): boolean {
    return place.latitude >= -90.0 && place.latitude <= 90.0 && place.longitude >= -180.0 && place.longitude <= 180.0;
}

const MINUTE_MS = 60_000;
const DAY_MS = 24 * 60 * MINUTE_MS;

/** Kotlin's `coerceIn`. */
function coerceIn(value: number, min: number, max: number): number {
    return value < min ? min : value > max ? max : value;
}

/** Kotlin's `Double.toLong()`: the part after the point is cut off, and "not a number" gives 0. */
function toLong(value: number): number {
    return Number.isNaN(value) ? 0 : Math.trunc(value);
}
