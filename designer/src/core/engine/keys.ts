// The names a design json uses, as tables.
//
// Port of the enums of wear/src/main/kotlin/app/aaps/wear/watchfaces/CustomWatchface.kt (`ViewMap`,
// `ComplicationMap`, `PrefMap`, `ValueMap`, `TrendArrowMap`, `GravityMap`, `StyleMap`, `RenderLayer`) and of
// shared/impl/.../weardata (`ViewKeys`, `JsonKeys`, `ResFileMap`, `CwfMetadataKey`)
// (AAPS branch local/personal, commit 77586f50bd). Keep the order of the entries: it is the paint order.

/** How a view is drawn: the Android view class behind it in `activity_custom.xml`. */
export type ViewKind = "background" | "complication" | "chart" | "image" | "text";

/** The part of the face a view belongs to, in paint order (`CustomWatchface.RenderLayer`). */
export type RenderLayer = "DATA_BASE" | "CLOCK_TEXT" | "MIDDLE" | "HANDS" | "SECOND_HAND";

export interface ViewDef {
    /** The key of the view in the design json. */
    readonly key: string;
    readonly kind: ViewKind;
    /** The watch setting that can hide the view (a key of [PREFS]), or null. */
    readonly pref: string | null;
    /** 0: own data. 1 or 2: data of the first or second followed person ("external"). */
    readonly external: 0 | 1 | 2;
    /** The picture of the zip this view shows: the file name without extension. */
    readonly customDrawable?: string;
    /** The picture shown while the glucose is high; falls back to [customDrawable]. */
    readonly customHigh?: string;
    /** The picture shown while the glucose is low; falls back to [customDrawable]. */
    readonly customLow?: string;
    /** True when the watch has its own picture for this view when the zip brings none. */
    readonly hasDefaultDrawable?: boolean;
    /** The id of the complication slot, for a complication placeholder. */
    readonly complicationId?: number;
    /** The view shows only one line (`maxLines` or `lines` 1 in the layout). */
    readonly oneLine?: boolean;
    /** What the view shows, for people. */
    readonly comment: string;
}

const text = (key: string, pref: string | null, comment: string, more: Partial<ViewDef> = {}): ViewDef =>
    ({ key, kind: "text", pref, external: 0, comment, ...more });

const ext = (def: ViewDef, external: 1 | 2): ViewDef => ({ ...def, key: `${def.key}_Ext${external}`, external });

const DATA_TEXTS: ViewDef[] = [
    text("patient_name", null, "Name of the person"),
    text("iob1", "key_show_iob", "Insulin on board: the label, or the total with detailed IOB on"),
    text("iob2", "key_show_iob", "Insulin on board: the total, or bolus and basal parts with detailed IOB on"),
    text("cob1", "key_show_cob", "Carbs on board: the label"),
    text("cob2", "key_show_cob", "Carbs on board: the value"),
    text("delta", "key_show_delta", "Glucose change over the last 5 minutes"),
    text("avg_delta", "key_show_avg_delta", "Average glucose change over the last 15 minutes"),
    text("tempTarget", "key_show_temp_target", "Target or temporary target"),
    text("reservoir", "key_show_reservoir_level", "Insulin left in the pump"),
    text("uploader_battery", "key_show_uploader_battery", "Phone battery in percent"),
    text("rig_battery", "key_show_rig_battery", "Rig (pump bridge) battery"),
    text("basalRate", "key_show_temp_basal", "Basal rate now"),
    text("bgi", "key_show_bgi", "Blood glucose impact"),
    text("status", "key_show_loop_status", "Status line of the loop", { oneLine: true }),
];

/** The main views that also exist for a followed person, in the order they come after the data texts. */
const TAIL_FOR_EXT: ViewDef[] = [
    text("loop", "key_show_loop_status", "Minutes since the last loop run, on a green, red or grey picture"),
    { key: "direction", kind: "image", pref: "key_show_direction", external: 0, comment: "Trend arrow" },
    text("timestamp", "key_show_ago", "Minutes since the last glucose reading"),
    text("sgv", "key_show_bg", "Glucose value", { oneLine: true }),
];

function extViews(external: 1 | 2): ViewDef[] {
    // Kotlin order: patient_name, iob1, iob2, cob1, cob2, delta, avg_delta, tempTarget, reservoir,
    // rig_battery, basalRate, bgi, status, loop, direction, timestamp, sgv (no uploader_battery)
    return [...DATA_TEXTS.filter(v => v.key !== "uploader_battery"), ...TAIL_FOR_EXT].map(v => ext(v, external));
}

const image = (key: string, comment: string, more: Partial<ViewDef> = {}): ViewDef =>
    ({ key, kind: "image", pref: null, external: 0, comment, ...more });

/** Every view of the Custom watch face, in paint order (`ViewMap`). */
export const VIEWS: readonly ViewDef[] = [
    image("background", "Picture and colour behind everything, always the full face", {
        kind: "background", customDrawable: "Background", customHigh: "BackgroundHigh", customLow: "BackgroundLow", hasDefaultDrawable: true
    }),
    ...[1, 2, 3, 4, 5].map((n): ViewDef => ({
        key: `complication${n}`, kind: "complication", pref: `key_show_complication${n}`, external: 0, complicationId: 100 + n,
        comment: `Complication slot ${n}: the wearer picks what it shows on the watch`
    })),
    { key: "chart", kind: "chart", pref: null, external: 0, comment: "Glucose graph" },
    image("cover_chart", "Picture over the graph", { customDrawable: "CoverChart", customHigh: "CoverChartHigh", customLow: "CoverChartLow" }),
    text("freetext1", null, "Free text or picture 1"),
    text("freetext2", null, "Free text or picture 2"),
    text("freetext3", null, "Free text or picture 3"),
    text("freetext4", null, "Free text or picture 4"),
    ...extViews(1),
    ...extViews(2),
    ...DATA_TEXTS,
    text("time", null, "Time as HH:mm, with seconds when they are shown"),
    text("hour", null, "Hour"),
    text("minute", null, "Minute"),
    text("second", "key_show_seconds", "Second"),
    text("timePeriod", null, "AM or PM, only with the 12 hour clock"),
    text("day_name", "key_show_date", "Name of the day"),
    text("day", "key_show_date", "Day of the month"),
    text("week_number", "key_show_week_number", "Week number, in brackets"),
    text("month", "key_show_date", "Name of the month"),
    ...TAIL_FOR_EXT,
    image("cover_plate", "Picture over all texts, below the hands", {
        customDrawable: "CoverPlate", customHigh: "CoverPlateHigh", customLow: "CoverPlateLow", hasDefaultDrawable: true
    }),
    image("hour_hand", "Hour hand", { customDrawable: "HourHand", customHigh: "HourHandHigh", customLow: "HourHandLow", hasDefaultDrawable: true }),
    image("minute_hand", "Minute hand", { customDrawable: "MinuteHand", customHigh: "MinuteHandHigh", customLow: "MinuteHandLow", hasDefaultDrawable: true }),
    image("second_hand", "Second hand", {
        pref: "key_show_seconds", customDrawable: "SecondHand", customHigh: "SecondHandHigh", customLow: "SecondHandLow", hasDefaultDrawable: true
    }),
];

const VIEW_BY_KEY = new Map(VIEWS.map(v => [v.key, v]));

/** The view with this json key, or null. */
export function viewOf(key: string | null | undefined): ViewDef | null {
    return (key != null && VIEW_BY_KEY.get(key)) || null;
}

/** The views of a layer, in paint order (`CustomWatchface.layerViews`). */
export function layerViews(layer: RenderLayer): ViewDef[] {
    const all = VIEWS;
    const index = (key: string) => all.findIndex(v => v.key === key);
    switch (layer) {
        case "DATA_BASE":
            return all.slice(0, index("time"));
        case "CLOCK_TEXT":
            return ["time", "hour", "minute", "second"].map(k => viewOf(k)!);
        case "MIDDLE":
            return all.slice(index("timePeriod"), index("hour_hand"));
        case "HANDS":
            return [viewOf("hour_hand")!, viewOf("minute_hand")!];
        case "SECOND_HAND":
            return [viewOf("second_hand")!];
    }
}

/** A watch setting a design can read (`PrefMap`). All are on/off. */
export interface PrefDef {
    /** The name in a design json (`prefKey` of a dynPref block) and in the metadata. */
    readonly key: string;
    readonly defaultValue: boolean;
    /** True when a design may set it from its `metadata` block (with the wearer's leave). */
    readonly inMetadata: boolean;
    readonly title: string;
}

const pref = (key: string, defaultValue: boolean, title: string, inMetadata = true): PrefDef => ({ key, defaultValue, inMetadata, title });

export const PREFS: readonly PrefDef[] = [
    pref("key_show_iob", true, "Show IOB"),
    pref("key_show_complication1", false, "Show complication 1"),
    pref("key_show_complication2", false, "Show complication 2"),
    pref("key_show_complication3", false, "Show complication 3"),
    pref("key_show_complication4", false, "Show complication 4"),
    pref("key_show_complication5", false, "Show complication 5"),
    pref("key_show_detailed_iob", false, "Show detailed IOB"),
    pref("key_show_cob", true, "Show COB"),
    pref("key_show_delta", true, "Show delta"),
    pref("key_show_avg_delta", true, "Show average delta"),
    pref("key_show_temp_target", true, "Show temporary target"),
    pref("key_show_reservoir_level", true, "Show reservoir level"),
    pref("key_show_detailed_delta", false, "Show detailed delta"),
    pref("key_show_uploader_battery", true, "Show phone battery"),
    pref("key_show_rig_battery", false, "Show rig battery"),
    pref("key_show_temp_basal", true, "Show basal rate"),
    pref("key_show_direction", true, "Show trend arrow"),
    pref("key_show_ago", true, "Show minutes since the reading"),
    pref("key_show_bg", true, "Show glucose"),
    pref("key_show_bgi", false, "Show BGI"),
    pref("key_show_loop_status", true, "Show loop status"),
    pref("key_show_week_number", false, "Show week number"),
    pref("key_show_date", false, "Show date"),
    pref("key_show_seconds", true, "Show seconds"),
    pref("key_units", true, "Units are mg/dL (off: mmol/L)", false),
    pref("key_dark", true, "Dark", false),
    pref("key_match_divider", false, "Matching divider", false),
];

const PREF_BY_KEY = new Map(PREFS.map(p => [p.key, p]));

export function prefOf(key: string): PrefDef | null {
    return PREF_BY_KEY.get(key) ?? null;
}

/** A value a `dynData` block or a formula (`v_<key>`) can follow (`ValueMap`), with its default range. */
export interface ValueDef {
    readonly key: string;
    readonly min: number;
    readonly max: number;
    readonly comment: string;
}

const value = (key: string, min: number, max: number, comment: string): ValueDef => ({ key, min, max, comment });

const VALUES_FOR_EXT: ValueDef[] = [
    value("sgv", 39, 400, "Glucose in mg/dL (also when the wearer uses mmol/L)"),
    value("sgvLevel", -1, 1, "-1 low, 0 in range, 1 high"),
    value("direction", 1, 7, "Trend: 1 double down, 2 down, 3 slightly down, 4 flat, 5 slightly up, 6 up, 7 double up"),
    value("delta", -25, 25, "Glucose change in mg/dL per 5 minutes"),
    value("avg_delta", -25, 25, "Average glucose change in mg/dL per 5 minutes"),
    value("tempTarget", 0, 2, "0 profile target, 1 target of the loop, 2 temporary target"),
    value("reservoir", 0, 500, "Insulin left in units"),
    value("reservoirLevel", 0, 2, "0 fine, 1 warning, 2 urgent"),
    value("rig_battery", 0, 100, "Rig battery in percent"),
    value("timestamp", 0, 60, "Minutes since the last glucose reading"),
    value("loop", 0, 28, "Minutes since the last loop run"),
];

/** Every value key, in the order of `ValueMap`. The empty key (`NONE`) is left out: it is always 0. */
export const VALUES: readonly ValueDef[] = [
    ...VALUES_FOR_EXT.slice(0, 8),
    value("uploader_battery", 0, 100, "Phone battery in percent"),
    ...VALUES_FOR_EXT.slice(8),
    value("day", 1, 31, "Day of the month"),
    value("day_name", 1, 7, "Day of the week, 1 Monday to 7 Sunday"),
    value("month", 1, 12, "Month"),
    value("week_number", 1, 53, "ISO week number"),
    ...VALUES_FOR_EXT.map(v => ({ ...v, key: v.key + "_Ext1", comment: v.comment + " (first followed person)" })),
    ...VALUES_FOR_EXT.map(v => ({ ...v, key: v.key + "_Ext2", comment: v.comment + " (second followed person)" })),
    value("watch_battery", 0, 100, "Watch battery in percent"),
    value("moon_phase", 0, 1, "0 new moon, 0.25 first quarter, 0.5 full moon, 0.75 last quarter"),
    value("sun_progress", 0, 1, "0 at sunrise, 0.5 around noon, 1 at sunset"),
    value("night_progress", 0, 1, "0 at sunset, 0.5 in the middle of the night, 1 at sunrise"),
    value("sun_cycle", 0, 1, "0 at sunrise, 0.5 at sunset, 1 at the next sunrise"),
    value("moon_cycle", 0, 1, "0 at moonrise, 0.5 at moonset, 1 at the next moonrise"),
    value("weather_temperature", -50, 50, "Temperature outside in °C"),
    value("next_sun_event", 0, 1, "The time of the next sunrise or sunset, for a text with @time"),
    value("sensor_left", 0, 72, "Hours until the sensor stops"),
    value("pump_left", 0, 72, "Hours until the pod or cannula stops"),
];

const VALUE_BY_KEY = new Map(VALUES.map(v => [v.key, v]));

/** `ValueMap.fromKey`: the value with this key, or `NONE` (key "", range 0..0) when there is none. */
export function valueOf(key: string): ValueDef {
    return VALUE_BY_KEY.get(key) ?? VALUE_NONE;
}

export const VALUE_NONE: ValueDef = value("", 0, 0, "No value: always 0");

/** The trend arrows (`TrendArrowMap`): the text the phone sends, the picture of the zip and the value for dynData. */
export interface TrendArrowDef {
    readonly name: string;
    readonly symbol: string;
    /** The file name (without extension) of the picture a zip can bring for it. */
    readonly customDrawable: string;
    /** The Android resource name of the watch's own picture. */
    readonly icon: string;
    readonly dynValue: number;
}

export const TREND_ARROWS: readonly TrendArrowDef[] = [
    { name: "NONE", symbol: "??", customDrawable: "ArrowNone", icon: "ic_invalid", dynValue: 0 },
    { name: "TRIPLE_UP", symbol: "X", customDrawable: "ArrowDoubleUp", icon: "ic_doubleup", dynValue: 7 },
    { name: "DOUBLE_UP", symbol: "⇈", customDrawable: "ArrowDoubleUp", icon: "ic_doubleup", dynValue: 7 },
    { name: "SINGLE_UP", symbol: "↑", customDrawable: "ArrowSingleUp", icon: "ic_singleup", dynValue: 6 },
    { name: "FORTY_FIVE_UP", symbol: "↗", customDrawable: "Arrow45Up", icon: "ic_fortyfiveup", dynValue: 5 },
    { name: "FLAT", symbol: "→", customDrawable: "ArrowFlat", icon: "ic_flat", dynValue: 4 },
    { name: "FORTY_FIVE_DOWN", symbol: "↘", customDrawable: "Arrow45Down", icon: "ic_fortyfivedown", dynValue: 3 },
    { name: "SINGLE_DOWN", symbol: "↓", customDrawable: "ArrowSingleDown", icon: "ic_singledown", dynValue: 2 },
    { name: "DOUBLE_DOWN", symbol: "⇊", customDrawable: "ArrowDoubleDown", icon: "ic_doubledown", dynValue: 1 },
    { name: "TRIPLE_DOWN", symbol: "X", customDrawable: "ArrowDoubleDown", icon: "ic_doubledown", dynValue: 1 },
];

/** `TrendArrowMap` for the arrow text [slopeArrow]: the first entry with that symbol, else `NONE`. */
export function trendArrowOf(slopeArrow: string): TrendArrowDef {
    return TREND_ARROWS.find(a => a.symbol === slopeArrow) ?? TREND_ARROWS[0]!;
}

/** The file names (without extension) the watch knows by name (`ResFileMap`). Any other picture is found by its own name. */
export const RES_FILES: readonly string[] = [
    "CustomWatchface", "Background", "BackgroundHigh", "BackgroundLow", "CoverChart", "CoverChartHigh", "CoverChartLow",
    "CoverPlate", "CoverPlateHigh", "CoverPlateLow", "HourHand", "HourHandHigh", "HourHandLow", "MinuteHand", "MinuteHandHigh",
    "MinuteHandLow", "SecondHand", "SecondHandHigh", "SecondHandLow", "ArrowNone", "ArrowDoubleUp", "ArrowSingleUp", "Arrow45Up",
    "ArrowFlat", "Arrow45Down", "ArrowSingleDown", "ArrowDoubleDown",
];

/** The name of the preview picture every zip must have. */
export const PREVIEW_NAME = "CustomWatchface";

/** The name of the design json in the zip. */
export const JSON_FILE = "CustomWatchface.json";

/** The size of the design space: every position and size of a design is in this 400 x 400 space (`TEMPLE_RESOLUTION`). */
export const DESIGN_SIZE = 400;

/** The fonts of the watch a design can name in `font` (`FontMap`). A font of the zip is named by its file name. */
export const SYSTEM_FONT_KEYS: readonly string[] = [
    "sans_serif", "default", "default_bold", "monospace", "serif", "roboto_condensed_bold", "roboto_condensed_light",
    "roboto_condensed_regular", "roboto_slab_light",
];

export const FONT_STYLES: readonly string[] = ["normal", "bold", "bold_italic", "italic"];
export const GRAVITIES: readonly string[] = ["center", "left", "right"];
export const VISIBILITIES: readonly string[] = ["visible", "gone"];

/** The metadata keys a design can have that are not settings (`CwfMetadataKey`, `isPref` false). */
export const METADATA_KEYS: readonly string[] = ["name", "filename", "author", "created_at", "cwf_version", "author_version", "comment", "cwf_authorization"];

/** The top level colour keys of a design, with the colour the watch takes when the key is missing. */
export const COLOR_KEYS: readonly { key: string; fallback: string; comment: string }[] = [
    { key: "highColor", fallback: "dark_highColor", comment: "Glucose above the high mark" },
    { key: "midColor", fallback: "inrange", comment: "Glucose in range" },
    { key: "lowColor", fallback: "low", comment: "Glucose below the low mark" },
    { key: "lowBatColor", fallback: "dark_uploaderBatteryEmpty", comment: "Phone battery text when the battery is low" },
    { key: "carbColor", fallback: "carbs", comment: "Carbs in the graph" },
    { key: "basalBackgroundColor", fallback: "basal_dark", comment: "Basal area in the graph" },
    { key: "basalCenterColor", fallback: "basal_light", comment: "Basal line in the graph" },
    { key: "gridColor", fallback: "white", comment: "Grid and time labels of the graph" },
    { key: "tempTargetColor", fallback: "dark_tempTarget", comment: "Target text while a temporary target runs" },
    { key: "tempTargetProfileColor", fallback: "white", comment: "Target text with the profile target" },
    { key: "tempTargetLoopColor", fallback: "dark_tempTarget_Loop", comment: "Target text when the loop changed the target" },
    { key: "reservoirColor", fallback: "white", comment: "Reservoir text when fine" },
    { key: "reservoirWarningColor", fallback: "dark_warning", comment: "Reservoir text at the warning level" },
    { key: "reservoirUrgentColor", fallback: "dark_alarm", comment: "Reservoir text at the urgent level" },
];
