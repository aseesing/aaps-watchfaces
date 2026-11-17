// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/utils/DesignTimeText.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things as in the Kotlin file, so a change there is easy to carry over.

/**
 * A time in the text of a Custom watch face design. Private patch.
 *
 * The text of a view with `dynData` is a format for a number, like `"%.0f°"`. For a value that is a
 * moment (a time in milliseconds, like the next sunrise or sunset), a design writes [TOKEN] in the
 * text instead: the watch puts the time there, in the 12 or 24 hour format of the watch.
 */
export const DesignTimeText = {

    TOKEN: "@time",

    /**
     * [format] with [TOKEN] replaced by [epochMs] written by [timeString], or null when [format] has no
     * [TOKEN] (then it is a number format, as before).
     */
    of(format: string, epochMs: number, timeString: (epochMs: number) => string): string | null {
        if (!format.includes(DesignTimeText.TOKEN)) return null;
        const time = timeString(toLong(epochMs));
        // Not `replaceAll`: that one gives `$` in the time a special meaning
        return format.split(DesignTimeText.TOKEN).join(time);
    }
};

/** Kotlin's `Double.toLong()`: the part after the point is cut off, and "not a number" gives 0. */
function toLong(value: number): number {
    return Number.isNaN(value) ? 0 : Math.trunc(value);
}
