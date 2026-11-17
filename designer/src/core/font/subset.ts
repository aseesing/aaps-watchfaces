// Cuts a font file down to the characters that are needed ("subsetting"), with HarfBuzz.
// HarfBuzz comes as a WebAssembly file from the npm package `harfbuzzjs`
// (`node_modules/harfbuzzjs/dist/harfbuzz-subset.wasm`). That file needs nothing from outside,
// so the same code runs in the browser and in Node. The package has no JavaScript wrapper for
// subsetting, so this file calls the C functions of HarfBuzz directly.

export interface SubsetOptions {
    /** The code points to keep. */
    codePoints: Iterable<number>;
    /** Drop hinting. Default true. */
    noHinting?: boolean;
    /** Keep these OpenType layout features; default: the ones HarfBuzz keeps by default. */
    layoutFeatures?: string[] | "default" | "all";
    /** Tables to drop in addition, default ["DSIG", "FFTM"]. */
    dropTables?: string[];
}

export interface Subsetter {
    subset(font: Uint8Array, options: SubsetOptions): Uint8Array;
}

/** The subsetter of [createSubsetter]. It can also tell how much memory HarfBuzz holds. */
export interface WasmSubsetter extends Subsetter {
    /** The size of the WebAssembly memory in bytes. It must not keep growing from call to call. */
    memoryBytes(): number;
}

/**
 * The characters a watch face font should keep (see docs/font-subset.md): all Latin letters, so
 * day and month names work in every Latin-script language, and the signs the watch writes.
 * The combining accents (U+0300..036F) are left out on purpose: they pull in large tables and
 * Android writes ready-made accented letters.
 */
export const LATIN_RANGES: [number, number][] = [
    [0x0020, 0x024f], // Basic Latin, Latin-1, Latin Extended-A and -B
    [0x02b0, 0x02ff], // modifier letters
    [0x0394, 0x0394], // Greek capital delta
    [0x03bc, 0x03bc], // Greek small mu
    [0x1e00, 0x1eff], // Latin Extended Additional
    [0x2000, 0x206f], // spaces, dashes, quotes, bullet, per mille, primes
    [0x20a0, 0x20cf], // currency signs
    [0x2100, 0x215f], // letter-like symbols and fractions
    [0x2190, 0x21ff], // arrows
    [0x2206, 0x2206], // increment (the other delta)
    [0x2212, 0x2212], // minus sign
    [0x2215, 0x2215], // division slash
    [0x221e, 0x221e], // infinity
    [0x2248, 0x2248], // almost equal
    [0x2260, 0x2265], // not equal, less or equal, greater or equal
];

/** All code points of the ranges (first and last included), sorted, each one once. */
export function codePointsOfRanges(ranges: [number, number][]): number[] {
    const set = new Set<number>();
    for (const [first, last] of ranges) {
        for (let codePoint = first; codePoint <= last; codePoint++) set.add(codePoint);
    }
    return Array.from(set).sort((a, b) => a - b);
}

/** The code points of the text, sorted, each one once. */
export function codePointsOfText(text: string): number[] {
    const set = new Set<number>();
    for (const character of text) set.add(character.codePointAt(0)!);
    return Array.from(set).sort((a, b) => a - b);
}

// The C functions of HarfBuzz this file uses. Every pointer is a number: a place in the wasm memory.
interface HarfBuzzExports {
    memory: WebAssembly.Memory;
    _initialize?: () => void;
    malloc(size: number): number;
    free(pointer: number): void;
    hb_blob_create(data: number, length: number, memoryMode: number, userData: number, destroy: number): number;
    hb_blob_destroy(blob: number): void;
    hb_blob_get_length(blob: number): number;
    hb_blob_get_data(blob: number, lengthPointer: number): number;
    hb_face_create(blob: number, index: number): number;
    hb_face_destroy(face: number): void;
    hb_face_reference_blob(face: number): number;
    hb_set_add(set: number, value: number): void;
    hb_set_clear(set: number): void;
    hb_set_invert(set: number): void;
    hb_subset_input_create_or_fail(): number;
    hb_subset_input_destroy(input: number): void;
    hb_subset_input_unicode_set(input: number): number;
    hb_subset_input_set(input: number, setType: number): number;
    hb_subset_input_get_flags(input: number): number;
    hb_subset_input_set_flags(input: number, flags: number): void;
    hb_subset_or_fail(face: number, input: number): number;
}

// Values from the HarfBuzz headers (hb-blob.h and hb-subset.h).
const HB_MEMORY_MODE_READONLY = 1;
const HB_SUBSET_FLAGS_NO_HINTING = 0x1;
const HB_SUBSET_SETS_DROP_TABLE_TAG = 3;
const HB_SUBSET_SETS_NAME_ID = 4;
const HB_SUBSET_SETS_LAYOUT_FEATURE_TAG = 6;

// HarfBuzz keeps only the name ids 0..6 by default. These two are the licence text and its web
// address: a font under the Open Font License should carry them, and they are short.
const NAME_ID_LICENSE = 13;
const NAME_ID_LICENSE_URL = 14;

const DEFAULT_DROP_TABLES = ["DSIG", "FFTM"];

/** A table or feature tag ("kern", "DSIG") as the 32 bit number HarfBuzz uses. Short tags are filled with spaces. */
function tagNumber(tag: string): number {
    if (tag.length < 1 || tag.length > 4) throw new Error(`'${tag}' is not a font tag: a tag has 1 to 4 characters.`);
    const padded = tag.padEnd(4, " ");
    let value = 0;
    for (let i = 0; i < 4; i++) {
        const code = padded.charCodeAt(i);
        if (code < 0x20 || code > 0x7e) throw new Error(`'${tag}' is not a font tag: only plain ASCII characters are allowed.`);
        value = value * 256 + code;
    }
    return value;
}

/**
 * [wasm] is the content of `harfbuzz-subset.wasm`; the caller loads it (the browser fetches it,
 * Node reads the file).
 */
export async function createSubsetter(wasm: Uint8Array): Promise<WasmSubsetter> {
    // A copy with its own buffer: WebAssembly wants a plain ArrayBuffer view.
    const { instance } = await WebAssembly.instantiate(new Uint8Array(wasm), {});
    const hb = instance.exports as unknown as HarfBuzzExports;
    // The file is built as a library ("reactor"): its start-up code must run once.
    hb._initialize?.();

    function subset(font: Uint8Array, options: SubsetOptions): Uint8Array {
        // Everything made here is freed in the `finally` block, also when a step fails.
        let fontPointer = 0;
        let blob = 0;
        let face = 0;
        let input = 0;
        let subsetFace = 0;
        let subsetBlob = 0;
        try {
            if (font.length === 0) throw new Error("The font file is empty.");
            fontPointer = hb.malloc(font.length);
            if (fontPointer === 0) throw new Error("Not enough memory to load the font.");
            // The memory can move when it grows, so make a new view after each call that allocates.
            new Uint8Array(hb.memory.buffer).set(font, fontPointer);

            // No destroy function is given (the last 0): the bytes are freed below, after the blob.
            blob = hb.hb_blob_create(fontPointer, font.length, HB_MEMORY_MODE_READONLY, 0, 0);
            face = hb.hb_face_create(blob, 0);
            input = hb.hb_subset_input_create_or_fail();
            if (input === 0) throw new Error("Not enough memory to make the font smaller.");

            // The sets below belong to the input: they are freed with it.
            const unicodes = hb.hb_subset_input_unicode_set(input);
            for (const codePoint of options.codePoints) {
                if (Number.isInteger(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff) hb.hb_set_add(unicodes, codePoint);
            }

            if (options.noHinting !== false) {
                hb.hb_subset_input_set_flags(input, hb.hb_subset_input_get_flags(input) | HB_SUBSET_FLAGS_NO_HINTING);
            }

            const layoutFeatures = options.layoutFeatures ?? "default";
            if (layoutFeatures !== "default") {
                const features = hb.hb_subset_input_set(input, HB_SUBSET_SETS_LAYOUT_FEATURE_TAG);
                hb.hb_set_clear(features);
                if (layoutFeatures === "all") {
                    hb.hb_set_invert(features); // an empty set turned around holds every tag
                } else {
                    for (const feature of layoutFeatures) hb.hb_set_add(features, tagNumber(feature));
                }
            }

            const dropTables = hb.hb_subset_input_set(input, HB_SUBSET_SETS_DROP_TABLE_TAG);
            for (const table of options.dropTables ?? DEFAULT_DROP_TABLES) hb.hb_set_add(dropTables, tagNumber(table));

            const nameIds = hb.hb_subset_input_set(input, HB_SUBSET_SETS_NAME_ID);
            hb.hb_set_add(nameIds, NAME_ID_LICENSE);
            hb.hb_set_add(nameIds, NAME_ID_LICENSE_URL);

            subsetFace = hb.hb_subset_or_fail(face, input);
            if (subsetFace === 0) throw new Error("HarfBuzz could not make the font smaller. Is this a font file?");
            subsetBlob = hb.hb_face_reference_blob(subsetFace);
            const length = hb.hb_blob_get_length(subsetBlob);
            const data = hb.hb_blob_get_data(subsetBlob, 0);
            if (length === 0 || data === 0) throw new Error("HarfBuzz made an empty font. Is this a font file?");
            // `slice` copies the bytes out of the wasm memory, so they stay good after the free.
            return new Uint8Array(hb.memory.buffer).slice(data, data + length);
        } finally {
            if (subsetBlob !== 0) hb.hb_blob_destroy(subsetBlob);
            if (subsetFace !== 0) hb.hb_face_destroy(subsetFace);
            if (input !== 0) hb.hb_subset_input_destroy(input);
            if (face !== 0) hb.hb_face_destroy(face);
            if (blob !== 0) hb.hb_blob_destroy(blob);
            if (fontPointer !== 0) hb.free(fontPointer);
        }
    }

    return { subset, memoryBytes: () => hb.memory.buffer.byteLength };
}
