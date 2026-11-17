// Values the watch app takes from its Android resources (wear/src/main/res), as the Custom watch face uses them.

import type { Argb } from "../color.ts";

/** wear `res/values/colors.xml`. */
export const RES_COLORS = {
    dark_highColor: 0xffffff00 as Argb,            // @color/yellow_A200
    inrange: 0xff00ff00 as Argb,
    low: 0xffff0000 as Argb,
    carbs: 0xfffb8c00 as Argb,
    basal_dark: 0xff0000ff as Argb,                // @color/BLUE
    basal_light: 0xff64b5f6 as Argb,               // @color/blue_300
    dark_uploaderBatteryEmpty: 0xffe53935 as Argb, // @color/red_600
    dark_tempTarget_Loop: 0xff77dd77 as Argb,
    dark_tempTarget: 0xfff4d700 as Argb,
    dark_warning: 0xffffff00 as Argb,
    dark_alarm: 0xffff4444 as Argb,
    dark_TimestampOld: 0xffe53935 as Argb,         // @color/red_600
    light_grey: 0xffcccccc as Argb,
    white: 0xffffffff as Argb,
    /** `BaseWatchFace.bolusColor`: `Color.MAGENTA`. */
    bolus: 0xffff00ff as Argb,
} as const;

/** wear `res/values/strings.xml`, English. */
export const RES_STRINGS = {
    activity_carb: "Carb",
    activity_IOB: "IOB",
    units_short: "U",
} as const;

/** The pictures of the watch itself, as svg files under `assets/drawable/`, by their Android resource name. */
export const RES_DRAWABLES = [
    "background", "hour_hand", "minute_hand", "second_hand", "simplified_dial",
    "ic_invalid", "ic_doubleup", "ic_singleup", "ic_fortyfiveup", "ic_flat", "ic_fortyfivedown", "ic_singledown", "ic_doubledown",
    "loop_green_25", "loop_grey_25", "loop_red_25", "ic_health_heart", "ic_health_steps",
] as const;

export type ResDrawable = typeof RES_DRAWABLES[number];

/** The watch's own picture for a view without a picture in the zip (`ViewMap.defaultDrawable`). */
export const VIEW_DEFAULT_DRAWABLE: Record<string, ResDrawable> = {
    background: "background",
    cover_plate: "simplified_dial",
    hour_hand: "hour_hand",
    minute_hand: "minute_hand",
    second_hand: "second_hand",
};
