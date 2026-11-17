// The date and time texts of the watch (`DateUtilImpl` with `AndroidDateFormatPlatform`): the time of
// the watch in its zone, written with a Java date pattern in the language of the watch.
//
// Only the patterns the Custom watch face uses: HH hh mm ss a dd ww, E to EEEE, M to MMMM.

/** The date and time of a moment in a time zone. */
export interface LocalFields {
    year: number;
    /** 1 to 12. */
    month: number;
    day: number;
    hour: number;
    minute: number;
    second: number;
    /** 1 Monday to 7 Sunday. */
    dayOfWeek: number;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(key: string, locale: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
    const id = key + "|" + locale + "|" + (options.timeZone ?? "");
    let found = formatters.get(id);
    if (!found) {
        try {
            found = new Intl.DateTimeFormat(locale, options);
        } catch {
            // An unknown zone or language: fall back to UTC and English
            found = new Intl.DateTimeFormat("en", { ...options, timeZone: "UTC" });
        }
        formatters.set(id, found);
    }
    return found;
}

/** The date and time of [ms] in [zone]. */
export function localFields(ms: number, zone: string): LocalFields {
    const parts = formatter("fields", "en-US", {
        timeZone: zone, hourCycle: "h23", year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric",
        second: "numeric", weekday: "short"
    }).formatToParts(new Date(ms));
    const get = (type: string) => parts.find(p => p.type === type)?.value ?? "0";
    const weekdays = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
    return {
        year: Number(get("year")), month: Number(get("month")), day: Number(get("day")), hour: Number(get("hour")) % 24,
        minute: Number(get("minute")), second: Number(get("second")), dayOfWeek: weekdays.indexOf(get("weekday")) + 1
    };
}

const two = (value: number) => String(value).padStart(2, "0");

/** Days since 1970-01-01 of a calendar date. */
function epochDay(year: number, month: number, day: number): number {
    return Math.round(Date.UTC(year, month - 1, day) / 86_400_000);
}

/** The first day of the week (1 Monday to 7 Sunday) and the smallest number of days of the first week, for a language. */
function weekRules(locale: string): { firstDay: number; minimalDays: number } {
    try {
        const info = new Intl.Locale(locale) as Intl.Locale & { getWeekInfo?: () => { firstDay: number; minimalDays: number }; weekInfo?: { firstDay: number; minimalDays: number } };
        const week = info.getWeekInfo?.() ?? info.weekInfo;
        if (week) return { firstDay: week.firstDay, minimalDays: week.minimalDays };
    } catch {
        // falls through to ISO
    }
    return { firstDay: 1, minimalDays: 4 };
}

/** The week of the week based year (Java pattern `w`), by the week rules of [locale]. */
export function weekOfYear(fields: LocalFields, locale: string): number {
    const { firstDay, minimalDays } = weekRules(locale);
    const weekOne = (year: number): number => {
        // The day the first week of [year] starts on
        const jan1 = epochDay(year, 1, 1);
        const jan1Dow = ((jan1 + 3) % 7 + 7) % 7 + 1;                 // 1970-01-01 was a Thursday
        const back = ((jan1Dow - firstDay) % 7 + 7) % 7;               // days from the start of that week to Jan 1
        const daysInFirstWeek = 7 - back;
        return daysInFirstWeek >= minimalDays ? jan1 - back : jan1 - back + 7;
    };
    const today = epochDay(fields.year, fields.month, fields.day);
    let start = weekOne(fields.year);
    if (today < start) start = weekOne(fields.year - 1);
    else if (today >= weekOne(fields.year + 1)) start = weekOne(fields.year + 1);
    return Math.floor((today - start) / 7) + 1;
}

/** The ISO week number, as `WeekFields.ISO.weekOfWeekBasedYear()`. */
export function isoWeek(fields: LocalFields): number {
    const today = epochDay(fields.year, fields.month, fields.day);
    const thursday = today + (4 - fields.dayOfWeek);
    const year = new Date(thursday * 86_400_000).getUTCFullYear();
    return Math.floor((thursday - epochDay(year, 1, 1)) / 7) + 1;
}

/** The texts of the watch for one moment. */
export class DateText {

    readonly fields: LocalFields;
    private readonly ms: number;
    private readonly zone: string;
    private readonly locale: string;
    private readonly is24h: boolean;

    constructor(ms: number, zone: string, locale: string, is24h: boolean) {
        this.ms = ms;
        this.zone = zone;
        this.locale = locale;
        this.is24h = is24h;
        this.fields = localFields(ms, zone);
    }

    private hour12(): number {
        return this.fields.hour % 12 === 0 ? 12 : this.fields.hour % 12;
    }

    /** `HH` with the 24 hour clock, `hh` with the 12 hour clock. */
    hourString(): string {
        return two(this.is24h ? this.fields.hour : this.hour12());
    }

    minuteString(): string {
        return two(this.fields.minute);
    }

    secondString(): string {
        return two(this.fields.second);
    }

    /** `a`: AM or PM in the language of the watch. */
    amPm(): string {
        const parts = formatter("ampm", this.locale, { timeZone: this.zone, hour: "numeric", hour12: true }).formatToParts(new Date(this.ms));
        return parts.find(p => p.type === "dayPeriod")?.value ?? (this.fields.hour < 12 ? "AM" : "PM");
    }

    /** `HH:mm`, or `hh:mm a` with the 12 hour clock. */
    timeString(): string {
        return this.is24h ? `${this.hourString()}:${this.minuteString()}` : `${this.hourString()}:${this.minuteString()} ${this.amPm()}`;
    }

    /** `dd`. */
    dayString(): string {
        return two(this.fields.day);
    }

    /** `ww`. */
    weekString(): string {
        return two(weekOfYear(this.fields, this.locale));
    }

    /** Pattern `E` to `EEEE`: the short name, or the full name for four letters. */
    dayNameString(format: string): string {
        const weekday = format.length >= 4 ? "long" : "short";
        return formatter("day" + weekday, this.locale, { timeZone: this.zone, weekday }).format(new Date(this.ms));
    }

    /** Pattern `M` to `MMMM`: the number, two digits, the short name or the full name. */
    monthString(format: string): string {
        if (format.length === 1) return String(this.fields.month);
        if (format.length === 2) return two(this.fields.month);
        const month = format.length >= 4 ? "long" : "short";
        // With the day in the pattern Java gives the form of the name used in a date; alone it is the same for most languages
        return formatter("month" + month, this.locale, { timeZone: this.zone, month }).format(new Date(this.ms));
    }
}

/** The text of the time [ms] as `DateUtil.timeString` gives it, for `@time` in a design text. */
export function timeString(ms: number, zone: string, locale: string, is24h: boolean): string {
    return new DateText(ms, zone, locale, is24h).timeString();
}
