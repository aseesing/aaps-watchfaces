// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/daynight/NightFace.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.

/**
 * The choice between the main face and the optional night face of the Custom watch face (see
 * `CwfNight`). Private patch. Pure functions only.
 */
export const NightFace = {

    /**
     * The face to show: [night] when there is one and it is not day, else [main]. With no night face
     * the main face shows day and night, as before.
     *
     * @param showDay whether it is day, see [DayNightChoice.showDay]; only asked when there is a night face
     */
    choose<T>(main: T, night: T | null | undefined, showDay: () => boolean): T {
        return night !== null && night !== undefined && !showDay() ? night : main;
    }
};
