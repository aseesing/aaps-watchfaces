// The fonts of a face: the fonts of the watch and the fonts of the design zip.
//
// Port of `FontMap` and `StyleMap` of CustomWatchface.kt, and of what Android does when a text view
// gets a typeface with a style (`TextView.setTypeface(tf, style)`, `Typeface.create`, minikin):
// - a style adds 300 to the weight of the font for bold, and asks for italic;
// - of a family with more files, the file with the nearest weight is taken;
// - when the font is much lighter than asked (200 or more, and 600 or more is asked) Android makes it
//   bold itself ("fake bold": the outline is made thicker), and when it is not italic it slants it.

import type { FontInfo } from "../font/FontFile.ts";
import { readFont } from "../font/FontFile.ts";
import type { Platform } from "../platform.ts";

/** One font file, ready to draw with. */
export interface Typeface {
    /** The family name the font is known under on the canvas. One name per file. */
    readonly family: string;
    readonly info: FontInfo;
    readonly weight: number;
    readonly italic: boolean;
}

/** A font as a text view draws it: the file, and what Android fakes. */
export interface StyledFont {
    readonly typeface: Typeface;
    readonly fakeBold: boolean;
    readonly fakeItalic: boolean;
}

/** A font a design can name: one or more files of one family, and the weight a plain text is drawn in. */
interface FontFamily {
    readonly faces: Typeface[];
    readonly baseWeight: number;
}

/** The files of the fonts of the watch, under `assets/fonts/`. */
const SYSTEM_FAMILIES: Record<string, { files: string[]; baseWeight: number }> = {
    sans_serif: { files: ["Roboto-Regular.ttf", "Roboto-Bold.ttf", "Roboto-Italic.ttf", "Roboto-BoldItalic.ttf"], baseWeight: 400 },
    default: { files: ["Roboto-Regular.ttf", "Roboto-Bold.ttf", "Roboto-Italic.ttf", "Roboto-BoldItalic.ttf"], baseWeight: 400 },
    default_bold: { files: ["Roboto-Regular.ttf", "Roboto-Bold.ttf", "Roboto-Italic.ttf", "Roboto-BoldItalic.ttf"], baseWeight: 700 },
    monospace: { files: ["DroidSansMono.ttf"], baseWeight: 400 },
    serif: { files: ["NotoSerif-Regular.ttf", "NotoSerif-Bold.ttf", "NotoSerif-Italic.ttf", "NotoSerif-BoldItalic.ttf"], baseWeight: 400 },
    roboto_condensed_bold: { files: ["RobotoCondensed-Bold.ttf"], baseWeight: 700 },
    roboto_condensed_light: { files: ["RobotoCondensed-Light.ttf"], baseWeight: 300 },
    roboto_condensed_regular: { files: ["RobotoCondensed-Regular.ttf"], baseWeight: 400 },
    roboto_slab_light: { files: ["RobotoSlab-Light.ttf"], baseWeight: 300 },
};

const BOLD = 1;
const ITALIC = 2;

/** `StyleMap.style`: the Android style bits of a `fontStyle` text; unknown is normal. */
export function styleBits(key: string | null | undefined): number {
    switch (key) {
        case "bold":
            return BOLD;
        case "bold_italic":
            return BOLD | ITALIC;
        case "italic":
            return ITALIC;
        default:
            return 0;
    }
}

let familyCounter = 0;

/** Loaded font files, so a font is read and made known only once. */
const loadedByBytes = new WeakMap<Uint8Array, Typeface>();
const loadedAssets = new Map<string, Promise<Typeface>>();

async function loadTypeface(platform: Platform, bytes: Uint8Array): Promise<Typeface> {
    const known = loadedByBytes.get(bytes);
    if (known) return known;
    const info = readFont(bytes);
    const family = `cwf-font-${++familyCounter}`;
    await platform.registerFont(family, bytes);
    const typeface: Typeface = { family, info, weight: info.weight, italic: info.italic };
    loadedByBytes.set(bytes, typeface);
    return typeface;
}

function loadAsset(platform: Platform, file: string): Promise<Typeface> {
    let loading = loadedAssets.get(file);
    if (!loading) {
        loading = platform.readAsset(`fonts/${file}`).then(bytes => loadTypeface(platform, bytes));
        loadedAssets.set(file, loading);
    }
    return loading;
}

/** The fonts one design can use, by the key in its json. */
export class FontMap {

    private readonly families = new Map<string, FontFamily>();

    /** Fonts that could not be read, with the reason. */
    readonly errors: { key: string; message: string }[] = [];

    /**
     * `FontMap.init`: the fonts of the watch that [systemKeys] name (always `default` too), then the
     * fonts of the zip. A font of the zip with the name of a watch font takes its place.
     */
    static async load(platform: Platform, systemKeys: Iterable<string>, zipFonts: Iterable<{ key: string; bytes: Uint8Array }>): Promise<FontMap> {
        const map = new FontMap();
        const wanted = new Set<string>(["default"]);
        for (const key of systemKeys) if (SYSTEM_FAMILIES[key.toLowerCase()]) wanted.add(key.toLowerCase());
        for (const key of wanted) {
            const family = SYSTEM_FAMILIES[key]!;
            try {
                const faces = await Promise.all(family.files.map(file => loadAsset(platform, file)));
                map.families.set(key, { faces, baseWeight: family.baseWeight });
            } catch (e) {
                map.errors.push({ key, message: e instanceof Error ? e.message : String(e) });
            }
        }
        for (const font of zipFonts) {
            try {
                const typeface = await loadTypeface(platform, font.bytes);
                map.families.set(font.key.toLowerCase(), { faces: [typeface], baseWeight: typeface.weight });
            } catch (e) {
                // The watch falls back to its default font for a file it cannot read
                map.errors.push({ key: font.key, message: e instanceof Error ? e.message : String(e) });
            }
        }
        return map;
    }

    /** True when the design can name [key] in `font`. */
    has(key: string): boolean {
        return this.families.has(key.toLowerCase());
    }

    /** The family name of the plain default font, used for characters a font does not have. */
    get fallbackFamily(): string | null {
        return this.families.get("default")?.faces[0]?.family ?? null;
    }

    /**
     * The font for the json values `font` and `fontStyle`. An unknown font is the default font of the
     * watch, as `FontMap.font` does it.
     */
    styled(fontKey: string, styleKey: string | null | undefined): StyledFont | null {
        const family = this.families.get(fontKey.toLowerCase()) ?? this.families.get("default");
        if (!family || family.faces.length === 0) return null;
        const style = styleBits(styleKey);
        const wantedWeight = Math.min(1000, family.baseWeight + ((style & BOLD) !== 0 ? 300 : 0));
        const wantedItalic = (style & ITALIC) !== 0;
        // minikin: the nearest weight, an italic mismatch counts as two weight steps
        let best = family.faces[0]!;
        let bestScore = Infinity;
        for (const face of family.faces) {
            const score = Math.abs(face.weight - wantedWeight) / 100 + (face.italic === wantedItalic ? 0 : 2);
            if (score < bestScore) {
                best = face;
                bestScore = score;
            }
        }
        return {
            typeface: best,
            fakeBold: wantedWeight >= 600 && wantedWeight - best.weight >= 200,
            fakeItalic: wantedItalic && !best.italic
        };
    }
}

/** The keys of the watch fonts a design json names in any `font` or `fontTitle` value. */
export function fontKeysOf(json: unknown, found = new Set<string>()): Set<string> {
    if (Array.isArray(json)) for (const item of json) fontKeysOf(item, found);
    else if (typeof json === "object" && json !== null)
        for (const [key, value] of Object.entries(json)) {
            if ((key === "font" || key === "fontTitle") && typeof value === "string") found.add(value);
            else fontKeysOf(value, found);
        }
    return found;
}
