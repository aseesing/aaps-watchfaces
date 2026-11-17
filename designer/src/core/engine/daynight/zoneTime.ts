// Dates and times in a time zone, for the code that is ported from Kotlin and uses `java.time` there.
//
// Kotlin has a `ZoneId`; here a zone is its IANA name, for example "Europe/Amsterdam" or "UTC".
// The functions give what `java.time` gives, also on the days the clock is set forward or back.
// They read the rules of a zone from `Intl.DateTimeFormat`, so they work in a browser and in Node.
// Made for dates of today: before the year 1583 the two do not count the days in the same way.

/** A day on the calendar, without a time and without a zone (`java.time.LocalDate`). */
export interface LocalDate {
    readonly year: number;
    /** 1 (January) to 12 (December). */
    readonly month: number;
    /** The day of the month, 1 to 31. */
    readonly day: number;
}

/** A time of the day, without a date and without a zone (`java.time.LocalTime`). */
export interface LocalTime {
    /** 0 to 23. */
    readonly hour: number;
    readonly minute: number;
    readonly second: number;
    readonly millisecond: number;
}

/** A day and a time of that day, without a zone (`java.time.LocalDateTime`). */
export interface LocalDateTime extends LocalDate, LocalTime {}

const SECOND_MS = 1000;
const DAY_MS = 24 * 60 * 60 * SECOND_MS;

/** The start of the day (`LocalTime.MIDNIGHT`). */
export const MIDNIGHT: LocalTime = { hour: 0, minute: 0, second: 0, millisecond: 0 };

/** `LocalDate.of(year, month, day)`. */
export function dateOf(year: number, month: number, day: number): LocalDate {
    return { year, month, day };
}

/** `LocalTime.of(hour, minute, second)`. */
export function timeOf(hour: number, minute: number, second = 0, millisecond = 0): LocalTime {
    return { hour, minute, second, millisecond };
}

/** The time zone of this computer (`ZoneId.systemDefault()`). */
export function systemZone(): string {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** The date and the time the clocks in [zone] show at [epochMs] (`Instant.atZone(zone).toLocalDateTime()`). */
export function localDateTime(epochMs: number, zone: string): LocalDateTime {
    const whole = Math.floor(epochMs);
    const fields = { year: 0, month: 0, day: 0, hour: 0, minute: 0, second: 0 };
    for (const part of formatOf(zone).formatToParts(whole)) {
        switch (part.type) {
            case "year":
            case "month":
            case "day":
            case "minute":
            case "second":
                fields[part.type] = Number(part.value);
                break;
            case "hour":
                // Some systems write midnight as 24
                fields.hour = Number(part.value) % 24;
                break;
            default:
                break;
        }
    }
    return { ...fields, millisecond: ((whole % SECOND_MS) + SECOND_MS) % SECOND_MS };
}

/** The date in [zone] at [epochMs] (`Instant.atZone(zone).toLocalDate()`). */
export function localDate(epochMs: number, zone: string): LocalDate {
    const { year, month, day } = localDateTime(epochMs, zone);
    return { year, month, day };
}

/** How far the clocks in [zone] are ahead of UTC at [epochMs], in ms. West of Greenwich it is below 0. */
export function offsetMs(epochMs: number, zone: string): number {
    const whole = Math.floor(epochMs);
    const local = localDateTime(whole, zone);
    return utcMs(local, local) - whole;
}

/**
 * The moment the clocks in [zone] show [date] and [time], in epoch ms
 * (`date.atTime(time).atZone(zone).toInstant().toEpochMilli()`).
 *
 * On the day the clock is set forward some times do not exist: such a time is moved later by the
 * length of the jump (02:30 becomes 03:30). On the day the clock is set back some times come twice:
 * the first of the two is taken. Both as `java.time` does.
 */
export function epochMsOf(date: LocalDate, time: LocalTime, zone: string): number {
    return ofLocal(utcMs(date, time), zone, false);
}

/**
 * The first moment of [date] in [zone], in epoch ms (`date.atStartOfDay(zone).toInstant().toEpochMilli()`).
 * In a zone that sets the clock forward at midnight the day starts after the jump, at 01:00.
 */
export function startOfDay(date: LocalDate, zone: string): number {
    return ofLocal(utcMs(date, MIDNIGHT), zone, true);
}

/** The first moment of [date] in UTC, in epoch ms (`date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()`). */
export function startOfDayUtc(date: LocalDate): number {
    return utcMs(date, MIDNIGHT);
}

/** [date] moved by [days] days, back when below 0 (`date.plusDays(days)`). */
export function plusDays(date: LocalDate, days: number): LocalDate {
    const moved = new Date(utcMs(date, MIDNIGHT) + days * DAY_MS);
    return { year: moved.getUTCFullYear(), month: moved.getUTCMonth() + 1, day: moved.getUTCDate() };
}

/** True when [year] has 366 days (`date.isLeapYear`). */
export function isLeapYear(year: number): boolean {
    return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

/** The number of the day in its year: 1 for 1 January (`date.dayOfYear`). */
export function dayOfYear(date: LocalDate): number {
    return Math.round((utcMs(date, MIDNIGHT) - utcMs({ year: date.year, month: 1, day: 1 }, MIDNIGHT)) / DAY_MS) + 1;
}

/** True when [a] and [b] are the same day. */
export function sameDate(a: LocalDate, b: LocalDate): boolean {
    return a.year === b.year && a.month === b.month && a.day === b.day;
}

/** One formatter per zone: making one is slow, using it is fast. */
const formats = new Map<string, Intl.DateTimeFormat>();

function formatOf(zone: string): Intl.DateTimeFormat {
    let format = formats.get(zone);
    if (format === undefined) {
        format = new Intl.DateTimeFormat("en-US", {
            timeZone: zone, calendar: "gregory", numberingSystem: "latn", hourCycle: "h23",
            year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric", second: "numeric"
        });
        formats.set(zone, format);
    }
    return format;
}

/** [date] and [time] read as a moment in UTC, in epoch ms. */
function utcMs(date: LocalDate, time: LocalTime): number {
    // Not Date.UTC: that one reads the years 0 to 99 as 1900 to 1999
    const moment = new Date(0);
    moment.setUTCFullYear(date.year, date.month - 1, date.day);
    return moment.getTime() + msOfDay(time);
}

function msOfDay(time: LocalTime): number {
    return ((time.hour * 60 + time.minute) * 60 + time.second) * SECOND_MS + time.millisecond;
}

/**
 * The moment the clocks in [zone] show the local time [local] (a date and time, counted as if it was UTC).
 * A time that does not exist is moved later by the length of the jump, or, with [afterGap], to the
 * first moment after the jump.
 */
function ofLocal(local: number, zone: string, afterGap: boolean): number {
    // The clock changes at most once in these two days: one of these two is the offset of the local time
    const before = offsetMs(local - DAY_MS, zone);
    const after = offsetMs(local + DAY_MS, zone);
    const first = local - before;
    if (before === after) return first;
    const second = local - after;
    const firstFits = offsetMs(first, zone) === before;
    const secondFits = offsetMs(second, zone) === after;
    // A time that comes twice: the first one. A time after the change: the only one that fits.
    if (firstFits) return first;
    if (secondFits) return second;
    // A time that does not exist. The clock jumped between [second] and [first].
    if (!afterGap) return first;
    let old = second;
    let jumped = first;
    while (jumped - old > 1) {
        const middle = Math.floor((old + jumped) / 2);
        if (offsetMs(middle, zone) === before) old = middle; else jumped = middle;
    }
    return jumped;
}
