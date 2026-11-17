// Port of the class TreatmentData of core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/rx/weardata/EventData.kt
// (AAPS branch local/personal, commit 77586f50bd). Only data, with the defaults of the Kotlin classes.
// Keep the names and the order of the fields like the Kotlin file, so a change there is easy to carry over.

import type { SingleBg } from "../EventData.ts";

/** One point of the target line, [mgdl] from [timeStamp] until the next point. */
export interface Target {
    timeStamp: number;
    mgdl: number;
}

/** A temporary basal rate. Times in epoch ms, rates in U/h. */
export interface TempBasal {
    startTime: number;
    /** The basal rate just before the start. */
    startBasal: number;
    endTime: number;
    /** The basal rate just after the end. */
    endBasal: number;
    /** The rate while it runs. */
    amount: number;
}

/** One piece of the basal profile. Times in epoch ms, the rate in U/h. */
export interface Basal {
    startTime: number;
    endTime: number;
    amount: number;
}

/** A bolus, an SMB or carbs. */
export interface Treatment {
    /** Epoch ms. */
    date: number;
    /** Insulin in U. */
    bolus: number;
    /** Carbs in g. */
    carbs: number;
    isSMB: boolean;
    isValid: boolean;
}

/** What the phone sends for the graph next to the glucose: basal, treatments and predictions. */
export interface TreatmentData {
    temps: TempBasal[];
    basals: Basal[];
    boluses: Treatment[];
    /** Predicted glucose. The field `color` of each value says which prediction line it belongs to. */
    predictions: SingleBg[];
    /**
     * The SMB size cuts of the phone's IOB graph, taken over a whole day of SMBs, so the watch
     * draws an SMB with the same size as the phone. Both -1 when there is nothing to tell apart
     * (then every SMB is medium), or from a phone that does not send them.
     */
    smbSmallBelow: number;
    smbLargeFrom: number;
    /**
     * The same for the manual boluses and for the carbs, the cuts of the phone's BG graph
     * markers, thirds from zero to the day's largest.
     */
    bolusSmallBelow: number;
    bolusLargeFrom: number;
    carbsSmallBelow: number;
    carbsLargeFrom: number;
    /**
     * The target line of the phone's BG graph, as the points where it changes plus a last one at the end of
     * the range. Empty from a phone that does not send it.
     */
    targets: Target[];
}

/** A [TreatmentData] with empty lists and the defaults of the Kotlin class; [values] replace them. */
export function treatmentData(values: Partial<TreatmentData> = {}): TreatmentData {
    return {
        temps: [], basals: [], boluses: [], predictions: [], smbSmallBelow: -1, smbLargeFrom: -1, bolusSmallBelow: -1,
        bolusLargeFrom: -1, carbsSmallBelow: -1, carbsLargeFrom: -1, targets: [], ...values
    };
}
