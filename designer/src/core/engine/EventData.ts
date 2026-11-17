// Port of the parts of core/interfaces/.../rx/weardata/EventData.kt that a watch face reads
// (AAPS branch local/personal, commit 77586f50bd). Only data, with the defaults of the Kotlin classes.
// The treatment data of the graph is in engine/chart/TreatmentData.ts.

/** One glucose value, as the phone sends it. The texts are already in the units of the wearer. */
export interface SingleBg {
    dataset: number;
    /** Time of the reading, epoch ms. 0: no reading. */
    timeStamp: number;
    /** The glucose as text, for example `6,7` or `120`. `---` means no value. */
    sgvString: string;
    /** `mmol` or `mg/dl`. */
    glucoseUnits: string;
    /** The trend arrow as one character, see `TrendArrowMap` in views. */
    slopeArrow: string;
    delta: string;
    deltaDetailed: string;
    avgDelta: string;
    avgDeltaDetailed: string;
    /** 1 high, 0 in range, -1 low. */
    sgvLevel: number;
    /** The glucose in mg/dL, always. */
    sgv: number;
    /** The high mark in mg/dL. */
    high: number;
    /** The low mark in mg/dL. */
    low: number;
    color: number;
    deltaMgdl: number | null;
    avgDeltaMgdl: number | null;
    id: number;
    fromSensor: boolean;
}

/** A [SingleBg] with the defaults of the Kotlin class; [values] replace them. */
export function singleBg(values: Partial<SingleBg> = {}): SingleBg {
    return {
        dataset: 0, timeStamp: 0, sgvString: "---", glucoseUnits: "-", slopeArrow: "--", delta: "--", deltaDetailed: "--",
        avgDelta: "--", avgDeltaDetailed: "--", sgvLevel: 0, sgv: 0, high: 0, low: 0, color: 0, deltaMgdl: null,
        avgDeltaMgdl: null, id: 0, fromSensor: false, ...values
    };
}

/** The status the phone sends: insulin, carbs, batteries, loop. */
export interface Status {
    dataset: number;
    externalStatus: string;
    iobSum: string;
    iobDetail: string;
    cob: string;
    currentBasal: string;
    /** The phone battery in percent, as text without `%`. */
    battery: string;
    rigBattery: string;
    /** Time of the last loop run, epoch ms; -1: none. */
    openApsStatus: number;
    bgi: string;
    /** 1: fine. Anything else: low (the text gets `lowBatColor`). */
    batteryLevel: number;
    patientName: string;
    tempTarget: string;
    /** 0 profile target, 1 target set by the loop, 2 temporary target. */
    tempTargetLevel: number;
    reservoirString: string;
    reservoir: number;
    /** 0 fine, 1 warning, 2 urgent. */
    reservoirLevel: number;
    /** When the sensor stops, epoch ms; null: not known. For the value `sensor_left`. */
    sensorEndsAt: number | null;
    /** When the pod or cannula stops, epoch ms; null: not known. For the value `pump_left`. */
    pumpEndsAt: number | null;
}

/** A [Status] with empty values; [values] replace them. */
export function status(values: Partial<Status> = {}): Status {
    return {
        dataset: 0, externalStatus: "", iobSum: "", iobDetail: "", cob: "", currentBasal: "", battery: "", rigBattery: "",
        openApsStatus: -1, bgi: "", batteryLevel: 1, patientName: "", tempTarget: "", tempTargetLevel: 0, reservoirString: "",
        reservoir: 0, reservoirLevel: 0, sensorEndsAt: null, pumpEndsAt: null, ...values
    };
}

/** The glucose history of the graph. */
export interface GraphData {
    entries: SingleBg[];
}

/** The weather the phone sends: `clear`, `partly`, `cloudy` or `rain`, and the temperature in °C. */
export interface Weather {
    timeStamp: number;
    kind: string;
    temperature: number | null;
}

export const WEATHER_KINDS = ["clear", "partly", "cloudy", "rain"] as const;
