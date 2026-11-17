// A small reader of TrueType and OpenType font files (the "sfnt" file format).
// It reads only what the designer needs: the sizes Android uses to place text, the names,
// the licence text, and which characters the font can draw. It uses no library.

export interface FontInfo {
    unitsPerEm: number;
    /** As Android (Skia + FreeType) reads them, in font units, y up: see `readVerticalMetrics`. */
    ascent: number;
    descent: number;
    lineGap: number;
    /** head.yMax and head.yMin: Android's FontMetrics top and bottom (the "font padding"). */
    yMax: number;
    yMin: number;
    xMin: number;
    xMax: number;
    capHeight: number | null;
    xHeight: number | null;
    /** OS/2 usWeightClass, 400 when missing. */
    weight: number;
    /** From OS/2 fsSelection, else head.macStyle. */
    bold: boolean;
    italic: boolean;
    familyName: string;
    subfamilyName: string;
    fullName: string;
    postScriptName: string;
    /** Name ids 0 and 13 (empty when missing). */
    copyright: string;
    license: string;
    numGlyphs: number;
    /** OpenType with CFF outlines (.otf). */
    isCff: boolean;
    /** Has an fvar table. */
    isVariable: boolean;
    tables: { tag: string; length: number }[];
    /** True when the font has a glyph for the code point. */
    hasGlyph(codePoint: number): boolean;
    /** All code points the font maps, sorted. */
    codePoints(): number[];
    /** Advance width of the glyph of a code point in font units (hmtx), or null when it has no glyph. */
    advanceWidth(codePoint: number): number | null;
}

/** The sizes of `android.graphics.Paint.FontMetricsInt`, in pixels. Top and ascent are negative (up). */
export interface AndroidFontMetrics {
    top: number;
    ascent: number;
    descent: number;
    bottom: number;
    leading: number;
}

/** One table of the font file: where it is and how long. */
export interface FontTable {
    tag: string;
    checksum: number;
    offset: number;
    length: number;
}

const TAG_TRUETYPE = 0x00010000;
const TAG_TRUE = 0x74727565; // "true": TrueType made for old Macs
const TAG_OTTO = 0x4f54544f; // "OTTO": OpenType with CFF outlines
const TAG_TTCF = 0x74746366; // "ttcf": a collection of fonts in one file
const TAG_WOFF = 0x774f4646; // "wOFF"
const TAG_WOF2 = 0x774f4632; // "wOF2"

/**
 * Reads the list of tables of a font file. For a collection (.ttc) it reads the first font.
 * Throws an Error with a clear text when the file is not a font.
 */
export function readTableDirectory(bytes: Uint8Array): { sfntVersion: number; isCollection: boolean; tables: FontTable[] } {
    if (bytes.length < 12) throw new Error("This file is not a font: it is too short.");
    const view = viewOf(bytes);
    let start = 0;
    let isCollection = false;
    let version = view.getUint32(0);
    if (version === TAG_WOFF || version === TAG_WOF2) {
        throw new Error("This is a WOFF web font. Only .ttf and .otf font files can be used.");
    }
    if (version === TAG_TTCF) {
        const fontCount = view.getUint32(8);
        if (fontCount < 1 || bytes.length < 16) throw new Error("This font collection (.ttc) has no font in it.");
        start = view.getUint32(12);
        if (start + 12 > bytes.length) throw new Error("This font collection (.ttc) is damaged.");
        isCollection = true;
        version = view.getUint32(start);
    }
    if (version !== TAG_TRUETYPE && version !== TAG_TRUE && version !== TAG_OTTO) {
        throw new Error("This file is not a TrueType or OpenType font (.ttf or .otf).");
    }
    const tableCount = view.getUint16(start + 4);
    if (tableCount === 0 || start + 12 + tableCount * 16 > bytes.length) {
        throw new Error("This font file is damaged: the list of tables is cut off.");
    }
    const tables: FontTable[] = [];
    for (let i = 0; i < tableCount; i++) {
        const at = start + 12 + i * 16;
        const table: FontTable = {
            tag: tagText(view.getUint32(at)),
            checksum: view.getUint32(at + 4),
            offset: view.getUint32(at + 8),
            length: view.getUint32(at + 12),
        };
        if (table.offset + table.length > bytes.length) {
            throw new Error(`This font file is damaged: the table '${table.tag}' is cut off.`);
        }
        tables.push(table);
    }
    return { sfntVersion: version, isCollection, tables };
}

/** Reads a font file. Throws an Error with a clear text for a file that is not a font. */
export function readFont(bytes: Uint8Array): FontInfo {
    const directory = readTableDirectory(bytes);
    const view = viewOf(bytes);
    const byTag = new Map<string, FontTable>();
    for (const table of directory.tables) byTag.set(table.tag, table);

    const head = needTable(byTag, "head", 54);
    const hhea = needTable(byTag, "hhea", 36);
    const maxp = needTable(byTag, "maxp", 6);
    const os2 = readOs2(view, byTag.get("OS/2"));

    const unitsPerEm = view.getUint16(head.offset + 18);
    if (unitsPerEm === 0) throw new Error("This font file is damaged: its units per em is 0.");
    const macStyle = view.getUint16(head.offset + 44);
    const numGlyphs = view.getUint16(maxp.offset + 4);
    const vertical = readVerticalMetrics(view, hhea, os2);
    const names = readNames(view, byTag.get("name"));

    // The character map is read on first use: many callers only want the sizes and names.
    let glyphOfCodePoint: Map<number, number> | null = null;
    const glyphMap = (): Map<number, number> => {
        if (glyphOfCodePoint === null) glyphOfCodePoint = readCmap(view, byTag.get("cmap"), numGlyphs);
        return glyphOfCodePoint;
    };

    const hmtx = byTag.get("hmtx");
    const numberOfHMetrics = view.getUint16(hhea.offset + 34);
    const advanceOfGlyph = (glyph: number): number | null => {
        if (hmtx === undefined || numberOfHMetrics === 0) return null;
        // Glyphs after the last full entry all have the advance of the last entry.
        const index = Math.min(glyph, numberOfHMetrics - 1);
        if (index * 4 + 2 > hmtx.length) return null;
        return view.getUint16(hmtx.offset + index * 4);
    };

    // Skia takes x-height and cap height from OS/2 and, when they are 0 there, from the outline
    // of "x" and "H". This reader can do the second step only for TrueType outlines (glyf).
    const glyphTop = (codePoint: number): number | null => {
        const glyph = glyphMap().get(codePoint);
        return glyph === undefined ? null : readGlyphYMax(view, byTag, head, glyph, numGlyphs);
    };
    const xHeight = os2 !== null && os2.xHeight !== 0 ? os2.xHeight : glyphTop(0x78);
    const capHeight = os2 !== null && os2.version >= 2 && os2.capHeight !== 0 ? os2.capHeight : glyphTop(0x48);

    return {
        unitsPerEm,
        ascent: vertical.ascent,
        descent: vertical.descent,
        lineGap: vertical.lineGap,
        xMin: view.getInt16(head.offset + 36),
        yMin: view.getInt16(head.offset + 38),
        xMax: view.getInt16(head.offset + 40),
        yMax: view.getInt16(head.offset + 42),
        capHeight,
        xHeight,
        weight: os2 !== null && os2.weight !== 0 ? os2.weight : 400,
        // As FreeType does (sfobjs.c, "Compute style flags"): OS/2 fsSelection bit 5 is bold,
        // bit 0 (italic) or bit 9 (oblique) is italic. Without OS/2: head.macStyle bit 0 and 1.
        bold: os2 !== null ? (os2.fsSelection & 0x20) !== 0 : (macStyle & 1) !== 0,
        italic: os2 !== null ? (os2.fsSelection & 0x201) !== 0 : (macStyle & 2) !== 0,
        familyName: names.get(1) ?? "",
        subfamilyName: names.get(2) ?? "",
        fullName: names.get(4) ?? "",
        postScriptName: names.get(6) ?? "",
        copyright: names.get(0) ?? "",
        license: names.get(13) ?? "",
        numGlyphs,
        isCff: byTag.has("CFF ") || byTag.has("CFF2"),
        isVariable: byTag.has("fvar"),
        tables: directory.tables.map((table) => ({ tag: table.tag, length: table.length })),
        hasGlyph: (codePoint) => glyphMap().has(codePoint),
        codePoints: () => Array.from(glyphMap().keys()).sort((a, b) => a - b),
        advanceWidth: (codePoint) => {
            const glyph = glyphMap().get(codePoint);
            return glyph === undefined ? null : advanceOfGlyph(glyph);
        },
    };
}

/**
 * The font sizes as `Paint.getFontMetricsInt()` gives them on Android, for a text size in pixels.
 *
 * Looked up in the Android source (frameworks/base, libs/hwui/jni):
 * - `Paint.cpp` `getFontMetricsInt` calls `getMetricsInternal` (Skia `SkFont::getMetrics`) and then
 *   `GraphicsJNI::set_metrics_int` in `Graphics.cpp`.
 * - `set_metrics_int` rounds: ascent, descent and leading with `SkScalarRoundToInt`
 *   (that is floor(x + 0.5)), top with `SkScalarFloorToInt`, bottom with `SkScalarCeilToInt`.
 * - Skia (`SkFontHost_FreeType.cpp`, `generateFontMetrics`) works with 32 bit floats: first the value
 *   divided by units per em, then times the text size. `Math.fround` copies that, so a value that
 *   lands close to a half rounds the same way as on the watch.
 *
 * Not copied: the fixed "elegant" sizes for apps that target an SDK below 35, and the `useLocale`
 * mode that also looks at fallback fonts. A design font on the watch uses neither.
 */
export function androidFontMetrics(info: FontInfo, textSizePx: number): AndroidFontMetrics {
    const scaled = (fontUnits: number): number => Math.fround(Math.fround(fontUnits / info.unitsPerEm) * textSizePx);
    const round = (value: number): number => Math.floor(value + 0.5) + 0; // "+ 0" turns -0 into 0
    return {
        top: Math.floor(scaled(-info.yMax)) + 0,
        ascent: round(scaled(-info.ascent)),
        descent: round(scaled(-info.descent)),
        bottom: Math.ceil(scaled(-info.yMin)) + 0,
        leading: round(scaled(info.lineGap)),
    };
}

// ---------------------------------------------------------------------------------------------

interface Os2 {
    version: number;
    weight: number;
    fsSelection: number;
    typoAscender: number;
    typoDescender: number;
    typoLineGap: number;
    winAscent: number;
    winDescent: number;
    xHeight: number;
    capHeight: number;
}

function viewOf(bytes: Uint8Array): DataView {
    return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}

function tagText(tag: number): string {
    return String.fromCharCode((tag >>> 24) & 0xff, (tag >>> 16) & 0xff, (tag >>> 8) & 0xff, tag & 0xff);
}

function needTable(byTag: Map<string, FontTable>, tag: string, minLength: number): FontTable {
    const table = byTag.get(tag);
    if (table === undefined) throw new Error(`This font file is damaged: it has no '${tag}' table.`);
    if (table.length < minLength) throw new Error(`This font file is damaged: its '${tag}' table is too short.`);
    return table;
}

/**
 * Reads the OS/2 table. Null when the font has none, or when its version is 0xFFFF: FreeType uses
 * that value to say "no OS/2 table", and Skia tests for it too.
 * Old and short tables miss the later fields: those are read as 0, as FreeType does.
 */
function readOs2(view: DataView, table: FontTable | undefined): Os2 | null {
    if (table === undefined || table.length < 6) return null;
    const version = view.getUint16(table.offset);
    if (version === 0xffff) return null;
    const u16 = (at: number): number => (at + 2 <= table.length ? view.getUint16(table.offset + at) : 0);
    const i16 = (at: number): number => (at + 2 <= table.length ? view.getInt16(table.offset + at) : 0);
    return {
        version,
        weight: u16(4),
        fsSelection: u16(62),
        typoAscender: i16(68),
        typoDescender: i16(70),
        typoLineGap: i16(72),
        winAscent: u16(74),
        winDescent: u16(76),
        xHeight: version >= 2 ? i16(86) : 0,
        capHeight: version >= 2 ? i16(88) : 0,
    };
}

/**
 * Ascent, descent and line gap as Android gets them for a font with outlines.
 *
 * Looked up in the real sources (November 2026):
 *
 * Skia, `src/ports/SkFontHost_FreeType.cpp`, `SkScalerContext_FreeType::generateFontMetrics`:
 * - when OS/2 is there, its version is not 0xFFFF and fsSelection bit 7 (USE_TYPO_METRICS) is set:
 *   ascent, descent and leading are sTypoAscender, sTypoDescender and sTypoLineGap;
 * - else ascent = face->ascender, descent = face->descender and
 *   leading = face->height + (face->descender - face->ascender);
 * - a leading below 0 becomes 0 ("disallow negative linespacing");
 * - top and bottom are face->bbox.yMax and yMin, which FreeType copies from the head table.
 *
 * FreeType, `src/sfnt/sfobjs.c`, `sfnt_load_face`, fills face->ascender, descender and height:
 * 1. OS/2 with fsSelection bit 7: the sTypo values (the same test as Skia makes);
 * 2. else the hhea values (ascender, descender, lineGap);
 * 3. when hhea ascender and descender are both 0 and OS/2 is there: the sTypo values when one of
 *    them is not 0, else usWinAscent and -usWinDescent with a height of ascender - descender
 *    (so a line gap of 0). FreeType casts the usWin values to a signed 16 bit number.
 *
 * So Skia's own test never changes the result with today's FreeType: both give the same numbers.
 */
function readVerticalMetrics(view: DataView, hhea: FontTable, os2: Os2 | null): { ascent: number; descent: number; lineGap: number } {
    let ascent: number;
    let descent: number;
    let lineGap: number;
    if (os2 !== null && (os2.fsSelection & 0x80) !== 0) {
        ascent = os2.typoAscender;
        descent = os2.typoDescender;
        lineGap = os2.typoLineGap;
    } else {
        ascent = view.getInt16(hhea.offset + 4);
        descent = view.getInt16(hhea.offset + 6);
        lineGap = view.getInt16(hhea.offset + 8);
        if (ascent === 0 && descent === 0 && os2 !== null) {
            if (os2.typoAscender !== 0 || os2.typoDescender !== 0) {
                ascent = os2.typoAscender;
                descent = os2.typoDescender;
                lineGap = os2.typoLineGap;
            } else {
                ascent = (os2.winAscent << 16) >> 16;
                descent = -((os2.winDescent << 16) >> 16);
                lineGap = 0;
            }
        }
    }
    return { ascent, descent, lineGap: Math.max(0, lineGap) };
}

// The upper half (0x80..0xFF) of the Mac Roman character set, for old Mac name records:
// 8 rows of 16 characters.
const MAC_ROMAN_HIGH =
    "ÄÅÇÉÑÖÜáàâäãåçéè" +
    "êëíìîïñóòôöõúùûü" +
    "†°¢£§•¶ß®©™´¨≠ÆØ" +
    "∞±≤≥¥µ∂∑∏π∫ªºΩæø" +
    "¿¡¬√ƒ≈∆«»… ÀÃÕŒœ" +
    "–—“”‘’÷◊ÿŸ⁄€‹›ﬁﬂ" +
    "‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔ" +
    "ÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ";

function decodeUtf16Be(view: DataView, offset: number, length: number): string {
    let text = "";
    for (let i = 0; i + 1 < length; i += 2) text += String.fromCharCode(view.getUint16(offset + i));
    return text;
}

function decodeMacRoman(view: DataView, offset: number, length: number): string {
    let text = "";
    for (let i = 0; i < length; i++) {
        const byte = view.getUint8(offset + i);
        text += byte < 0x80 ? String.fromCharCode(byte) : MAC_ROMAN_HIGH.charAt(byte - 0x80);
    }
    return text;
}

/**
 * How good a name record is, lower is better. English Windows names first (every font tool writes
 * them), then Unicode platform names, then other Windows names, then old Mac names.
 */
function nameRank(platform: number, encoding: number, language: number): number | null {
    if (platform === 3 && (encoding === 1 || encoding === 10 || encoding === 0)) {
        if (language === 0x409) return 0;
        if ((language & 0xff) === 0x09) return 1; // another kind of English
        return 3;
    }
    if (platform === 0) return 2;
    if (platform === 1 && encoding === 0) return language === 0 ? 4 : 5;
    return null;
}

/** The texts of the name table by name id, the best record of each id. */
function readNames(view: DataView, table: FontTable | undefined): Map<number, string> {
    const names = new Map<number, string>();
    if (table === undefined || table.length < 6) return names;
    const count = view.getUint16(table.offset + 2);
    const storage = table.offset + view.getUint16(table.offset + 4);
    const end = table.offset + table.length;
    const bestRank = new Map<number, number>();
    for (let i = 0; i < count; i++) {
        const at = table.offset + 6 + i * 12;
        if (at + 12 > end) break;
        const platform = view.getUint16(at);
        const encoding = view.getUint16(at + 2);
        const language = view.getUint16(at + 4);
        const nameId = view.getUint16(at + 6);
        const length = view.getUint16(at + 8);
        const offset = storage + view.getUint16(at + 10);
        const rank = nameRank(platform, encoding, language);
        if (rank === null || offset + length > end) continue;
        const best = bestRank.get(nameId);
        if (best !== undefined && best <= rank) continue;
        bestRank.set(nameId, rank);
        names.set(nameId, platform === 1 ? decodeMacRoman(view, offset, length) : decodeUtf16Be(view, offset, length));
    }
    return names;
}

/**
 * How good a character map is, lower is better: full Unicode first, then the basic plane,
 * then symbol fonts and old Mac maps.
 */
function cmapRank(platform: number, encoding: number): number | null {
    if (platform === 3 && encoding === 10) return 0;
    if (platform === 0 && (encoding === 4 || encoding === 6)) return 1;
    if (platform === 3 && encoding === 1) return 2;
    if (platform === 0 && encoding <= 3) return 3;
    if (platform === 3 && encoding === 0) return 4;
    if (platform === 1 && encoding === 0) return 5;
    return null;
}

/** The map from code point to glyph number, from the best character map this reader knows. */
function readCmap(view: DataView, table: FontTable | undefined, numGlyphs: number): Map<number, number> {
    const map = new Map<number, number>();
    if (table === undefined || table.length < 4) return map;
    const end = table.offset + table.length;
    const count = view.getUint16(table.offset + 2);
    let bestRank = Number.MAX_SAFE_INTEGER;
    let bestOffset = -1;
    for (let i = 0; i < count; i++) {
        const at = table.offset + 4 + i * 8;
        if (at + 8 > end) break;
        const rank = cmapRank(view.getUint16(at), view.getUint16(at + 2));
        const offset = table.offset + view.getUint32(at + 4);
        if (rank === null || rank >= bestRank || offset + 2 > end) continue;
        const format = view.getUint16(offset);
        if (format !== 0 && format !== 4 && format !== 6 && format !== 10 && format !== 12 && format !== 13) continue;
        bestRank = rank;
        bestOffset = offset;
    }
    if (bestOffset < 0) return map;

    // A glyph number of 0 is the "missing glyph", and a number past the last glyph is a broken entry.
    const put = (codePoint: number, glyph: number): void => {
        if (glyph > 0 && glyph < numGlyphs) map.set(codePoint, glyph);
    };
    const u16 = (at: number): number => (at + 2 <= end ? view.getUint16(at) : 0);
    const u32 = (at: number): number => (at + 4 <= end ? view.getUint32(at) : 0);
    const at = bestOffset;

    switch (view.getUint16(at)) {
        case 0: {
            for (let code = 0; code < 256 && at + 6 + code < end; code++) put(code, view.getUint8(at + 6 + code));
            break;
        }
        case 4: {
            const segCount = u16(at + 6) >> 1;
            const endCodes = at + 14;
            const startCodes = endCodes + segCount * 2 + 2;
            const deltas = startCodes + segCount * 2;
            const rangeOffsets = deltas + segCount * 2;
            for (let seg = 0; seg < segCount; seg++) {
                const last = u16(endCodes + seg * 2);
                const first = u16(startCodes + seg * 2);
                const delta = u16(deltas + seg * 2);
                const rangeOffsetAt = rangeOffsets + seg * 2;
                const rangeOffset = u16(rangeOffsetAt);
                for (let code = first; code <= last && code < 0xffff; code++) {
                    if (rangeOffset === 0) {
                        put(code, (code + delta) & 0xffff);
                    } else {
                        const glyph = u16(rangeOffsetAt + rangeOffset + (code - first) * 2);
                        if (glyph !== 0) put(code, (glyph + delta) & 0xffff);
                    }
                }
            }
            break;
        }
        case 6: {
            const first = u16(at + 6);
            const entryCount = u16(at + 8);
            for (let i = 0; i < entryCount; i++) put(first + i, u16(at + 10 + i * 2));
            break;
        }
        case 10: {
            const first = u32(at + 12);
            const entryCount = Math.min(u32(at + 16), numGlyphs + 0x10000);
            for (let i = 0; i < entryCount; i++) put(first + i, u16(at + 20 + i * 2));
            break;
        }
        case 12:
        case 13: {
            const sameGlyph = view.getUint16(at) === 13;
            const groupCount = u32(at + 12);
            for (let group = 0; group < groupCount; group++) {
                const groupAt = at + 16 + group * 12;
                if (groupAt + 12 > end) break;
                const first = u32(groupAt);
                const last = Math.min(u32(groupAt + 4), 0x10ffff);
                const glyph = u32(groupAt + 8);
                for (let code = first; code <= last; code++) put(code, sameGlyph ? glyph : glyph + (code - first));
            }
            break;
        }
    }
    return map;
}

/** The highest point (yMax) of the outline of a glyph, for TrueType outlines. Null when it has none. */
function readGlyphYMax(view: DataView, byTag: Map<string, FontTable>, head: FontTable, glyph: number, numGlyphs: number): number | null {
    const loca = byTag.get("loca");
    const glyf = byTag.get("glyf");
    if (loca === undefined || glyf === undefined || glyph + 1 > numGlyphs) return null;
    const longOffsets = view.getInt16(head.offset + 50) === 1;
    const entrySize = longOffsets ? 4 : 2;
    if ((glyph + 2) * entrySize > loca.length) return null;
    const entryAt = loca.offset + glyph * entrySize;
    const start = longOffsets ? view.getUint32(entryAt) : view.getUint16(entryAt) * 2;
    const next = longOffsets ? view.getUint32(entryAt + 4) : view.getUint16(entryAt + 2) * 2;
    if (next <= start || start + 10 > glyf.length) return null; // an empty glyph, like the space
    return view.getInt16(glyf.offset + start + 8);
}
