// What the watch gives a face to show: the time, the glucose and loop data the phone sent, the
// settings of the wearer and what the watch knows itself (its battery, the weather, the heart rate).
//
// On the watch these come from many places (`BaseWatchFace`, the shared preferences, `DataHandlerWear`).
// Here they are one plain object, so a preview can be given any state: a high glucose, a low battery,
// rain at night. [sampleState] gives the same sample values the design tests of the Kotlin code use
// (`CwfDesignBase.storeSampleData`), so a preview made here can be compared with one made there.

import type { SingleBg, Status } from "./EventData.ts";
import { singleBg, status } from "./EventData.ts";
import type { TreatmentData } from "./chart/TreatmentData.ts";
import { PREFS } from "./keys.ts";

/** What a complication slot shows in the preview. On the watch the wearer picks a source per slot. */
export interface ComplicationSample {
    /** `none` draws nothing in the slot. */
    type: "none" | "short_text" | "ranged_value" | "icon";
    text?: string;
    title?: string;
    /** A built-in icon: `heart` or `steps`. */
    icon?: "heart" | "steps" | null;
    /** For `ranged_value`: where the value is between 0 and 1. */
    fraction?: number;
}

export interface WatchState {
    /** The moment the face shows, epoch ms. */
    nowMs: number;
    /** The time zone of the watch, an IANA name like `Europe/Amsterdam`. */
    zone: string;
    /** The language of the watch, for day and month names and number texts: a BCP 47 tag like `en` or `nl-NL`. */
    locale: string;
    is24h: boolean;
    /** True on the dim always-on screen: no seconds, no animation, no resting layers. */
    ambient: boolean;
    /** The glucose of the wearer [0] and of two followed persons [1], [2]. */
    bg: [SingleBg, SingleBg, SingleBg];
    status: [Status, Status, Status];
    /** The glucose history for the graph. */
    graph: SingleBg[];
    treatments: TreatmentData | null;
    /** The on/off settings of the watch a design can read, by their json name (see `PREFS`). */
    prefs: Record<string, boolean>;
    /** The watch setting "chart time frame" in hours. */
    chartTimeframe: number;
    /** The watch setting "log time graph". */
    logTimeGraph: boolean;
    /** The weather the phone sent, or null: `clear`, `partly`, `cloudy` or `rain`, and the temperature in °C. */
    weather: { kind: string; temperature: number | null } | null;
    /** The last known place, for sunrise, sunset and the moon; null: fixed hours. */
    location: { latitude: number; longitude: number } | null;
    /** The battery of the watch in percent, or null when not known. */
    watchBattery: number | null;
    /** The measured heart rate, or null. The pulse animation beats at it. */
    heartRate: number | null;
    /** The watch setting "Day / night look": `auto`, `day` or `night`. */
    dayNightLook: string;
    /** The watch setting "Always-on look": `night`, `day` or `active`. */
    alwaysOnLook: string;
    /** The colours the wearer chose for `colour1` and `colour2`: `design` (as designed) or `#RRGGBB`. */
    colours: [string, string];
    /** What each of the five complication slots shows. */
    complications: ComplicationSample[];
}

/** The watch settings with the defaults of the watch. */
export function defaultPrefs(): Record<string, boolean> {
    const prefs: Record<string, boolean> = {};
    for (const pref of PREFS) prefs[pref.key] = pref.defaultValue;
    return prefs;
}

/** How the phone writes a glucose value and its changes, in the units of the wearer. */
export interface BgSample {
    /** Glucose in mg/dL. */
    mgdl: number;
    /** Change over 5 minutes in mg/dL. */
    deltaMgdl: number;
    /** Average change in mg/dL. */
    avgDeltaMgdl: number;
    /** One of the arrow texts of `TREND_ARROWS`. */
    arrow: string;
    /** Minutes since the reading. */
    ageMinutes: number;
    /** True: texts in mg/dL. False: mmol/L. */
    unitsMgdl: boolean;
    /** The decimal sign of the phone's language. */
    decimalSeparator: "." | ",";
    high: number;
    low: number;
}

const MMOLL_TO_MGDL = 18.0;

function signed(value: number, digits: number, separator: string): string {
    const text = Math.abs(value).toFixed(digits).replace(".", separator);
    return (value < 0 ? "-" : "+") + text;
}

/** A glucose value with the texts the phone would send for it. */
export function makeBg(nowMs: number, sample: BgSample, dataset = 0): SingleBg {
    const mmol = !sample.unitsMgdl;
    const value = mmol ? sample.mgdl / MMOLL_TO_MGDL : sample.mgdl;
    const delta = mmol ? sample.deltaMgdl / MMOLL_TO_MGDL : sample.deltaMgdl;
    const avgDelta = mmol ? sample.avgDeltaMgdl / MMOLL_TO_MGDL : sample.avgDeltaMgdl;
    const sep = sample.decimalSeparator;
    return singleBg({
        dataset,
        timeStamp: nowMs - sample.ageMinutes * 60_000,
        sgvString: value.toFixed(mmol ? 1 : 0).replace(".", sep),
        glucoseUnits: mmol ? "mmol" : "mg/dl",
        slopeArrow: sample.arrow,
        delta: signed(delta, mmol ? 1 : 0, sep),
        deltaDetailed: signed(delta, mmol ? 2 : 1, sep),
        avgDelta: signed(avgDelta, mmol ? 1 : 0, sep),
        avgDeltaDetailed: signed(avgDelta, mmol ? 2 : 1, sep),
        sgvLevel: sample.mgdl > sample.high ? 1 : sample.mgdl < sample.low ? -1 : 0,
        sgv: sample.mgdl,
        high: sample.high,
        low: sample.low,
        deltaMgdl: sample.deltaMgdl,
        avgDeltaMgdl: sample.avgDeltaMgdl
    });
}

/** The graph of the design tests: 36 readings, one every 5 minutes, a slow wave between 90 and 160 mg/dL. */
export function sampleGraph(nowMs: number): SingleBg[] {
    const points: SingleBg[] = [];
    for (let i = 0; i < 36; i++)
        points.push(singleBg({ timeStamp: nowMs - (35 - i) * 5 * 60_000, sgv: 125.0 + 35.0 * Math.sin(i * Math.PI / 14.0), high: 180.0, low: 70.0 }));
    return points;
}

/** The moment the sample pictures show: 11 November 2026 at 23:00 UTC by default. */
export const SAMPLE_NOW_MS = Date.UTC(2026, 10, 11, 23, 0, 0);

/**
 * The sample state of the Kotlin design tests: glucose 6,7 mmol/L (120 mg/dL) steady, read 2 minutes
 * ago, loop ran 3 minutes ago, phone battery 78 %, watch battery 66 %, 24 hour clock, date shown.
 */
export function sampleState(nowMs: number = SAMPLE_NOW_MS, zone = "UTC"): WatchState {
    const prefs = defaultPrefs();
    prefs["key_show_date"] = true;
    // What the watch sets from the metadata of most designs
    prefs["key_show_delta"] = true;
    prefs["key_show_avg_delta"] = true;
    prefs["key_show_uploader_battery"] = true;
    prefs["key_show_rig_battery"] = true;
    prefs["key_units"] = false;
    const bg = makeBg(nowMs, {
        mgdl: 120, deltaMgdl: 3.6, avgDeltaMgdl: 1.8, arrow: "→", ageMinutes: 2, unitsMgdl: false, decimalSeparator: ",", high: 180, low: 70
    });
    return {
        nowMs, zone, locale: "en", is24h: true, ambient: false,
        bg: [bg, singleBg({ dataset: 1 }), singleBg({ dataset: 2 })],
        status: [
            status({
                iobSum: "1.25", iobDetail: "(0.90|0.35)", cob: "12g", currentBasal: "0.85U/h", battery: "78", rigBattery: "--",
                openApsStatus: nowMs - 3 * 60_000, bgi: "-0.4", batteryLevel: 1, tempTarget: "100", tempTargetLevel: 0,
                reservoirString: "120U", reservoir: 120, reservoirLevel: 0
            }),
            status({ dataset: 1 }), status({ dataset: 2 })
        ],
        graph: sampleGraph(nowMs),
        treatments: null,
        prefs,
        chartTimeframe: 3,
        logTimeGraph: false,
        weather: null,
        location: null,
        watchBattery: 66,
        heartRate: null,
        dayNightLook: "auto",
        alwaysOnLook: "night",
        colours: ["design", "design"],
        complications: [
            { type: "short_text", text: "72", icon: "heart" },
            { type: "short_text", text: "4.2k", icon: "steps" },
            { type: "none" }, { type: "none" }, { type: "none" }
        ]
    };
}
