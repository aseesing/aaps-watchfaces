// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/utils/WatchBatteryLevel.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.
// Only the key is here. Reading the battery of the watch is Android only: the designer gets the level as a setting.

/** The battery level of the watch itself, for the value `watch_battery` of a Custom watch face design. */
export const WatchBatteryLevel = {

    /** The value key in the design json. */
    KEY: "watch_battery"
};
