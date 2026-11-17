// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/utils/PartTimeLeft.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.

/**
 * How many hours the sensor or the pump part (pod or cannula) still works, as values for a Custom watch
 * face design (`dynData`), for example for the hand of a time-left gauge. Private patch.
 *
 * The phone sends when each part stops (`EventData.Status`), with the same ends as its status lights.
 */
export const PartTimeLeft = {

    /** The value of the sensor. */
    SENSOR_KEY: "sensor_left",

    /** The value of the pod, or of the cannula of another pump. */
    PUMP_KEY: "pump_left",

    /** The hours from [now] until [endsAt]; below 0 once it has passed. Null when the end is not known. */
    hours(endsAt: number | null, now: number): number | null {
        return endsAt === null ? null : (endsAt - now) / HOUR_MS;
    }
};

const HOUR_MS = 60 * 60 * 1000.0;
