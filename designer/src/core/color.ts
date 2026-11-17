// Colours as the watch holds them: one number 0xAARRGGBB (alpha, red, green, blue), never negative here.

/** A colour as 0xAARRGGBB. */
export type Argb = number;

export const BLACK: Argb = 0xff000000;
export const WHITE: Argb = 0xffffffff;
export const GRAY: Argb = 0xff888888;
export const LTGRAY: Argb = 0xffcccccc;
export const RED: Argb = 0xffff0000;
export const TRANSPARENT: Argb = 0x00000000;

/** The colour names Android's `Color.parseColor` knows. */
const NAMES: Record<string, Argb> = {
    black: 0xff000000, darkgray: 0xff444444, gray: 0xff888888, lightgray: 0xffcccccc, white: 0xffffffff,
    red: 0xffff0000, green: 0xff00ff00, blue: 0xff0000ff, yellow: 0xffffff00, cyan: 0xff00ffff, magenta: 0xffff00ff,
    aqua: 0xff00ffff, fuchsia: 0xffff00ff, darkgrey: 0xff444444, grey: 0xff888888, lightgrey: 0xffcccccc,
    lime: 0xff00ff00, maroon: 0xff800000, navy: 0xff000080, olive: 0xff808000, purple: 0xff800080,
    silver: 0xffc0c0c0, teal: 0xff008080
};

/**
 * Android's `Color.parseColor`: `#RRGGBB`, `#AARRGGBB` or a colour name. Null when the text is not a
 * colour (there Android throws, and the watch then takes a default colour).
 */
export function parseColor(text: string): Argb | null {
    if (text.startsWith("#")) {
        const hex = text.substring(1);
        if (!/^[0-9a-fA-F]+$/.test(hex)) return null;
        if (hex.length === 6) return (0xff000000 | parseInt(hex, 16)) >>> 0;
        if (hex.length === 8) return parseInt(hex, 16) >>> 0;
        return null;
    }
    const named = NAMES[text.toLowerCase()];
    return named === undefined ? null : named;
}

export function argb(alpha: number, red: number, green: number, blue: number): Argb {
    return (((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff)) >>> 0;
}

export function rgb(red: number, green: number, blue: number): Argb {
    return argb(255, red, green, blue);
}

export const alphaOf = (color: Argb): number => (color >>> 24) & 0xff;
export const redOf = (color: Argb): number => (color >>> 16) & 0xff;
export const greenOf = (color: Argb): number => (color >>> 8) & 0xff;
export const blueOf = (color: Argb): number => color & 0xff;

/** [color] with its alpha replaced by [alpha] (0..255). `ColorUtils.setAlphaComponent`. */
export function withAlpha(color: Argb, alpha: number): Argb {
    return (((alpha & 0xff) << 24) | (color & 0x00ffffff)) >>> 0;
}

/** The colour as CSS text for a canvas. [alphaScale] (0..1) is multiplied with its own alpha. */
export function css(color: Argb, alphaScale = 1): string {
    const alpha = (alphaOf(color) / 255) * alphaScale;
    return `rgba(${redOf(color)},${greenOf(color)},${blueOf(color)},${+alpha.toFixed(4)})`;
}

/** The colour as `#RRGGBB`, or `#AARRGGBB` when it is not opaque. */
export function hex(color: Argb): string {
    const text = (color >>> 0).toString(16).toUpperCase().padStart(8, "0");
    return "#" + (text.startsWith("FF") ? text.substring(2) : text);
}
