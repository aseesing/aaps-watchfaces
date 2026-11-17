// How the watch lays out and draws the text of a text view.
//
// The watch uses Android's `TextView` with a fixed width and height (the box of the design). This file
// copies what matters for a design, so a text that fits here fits on the watch, and one that is cut
// there is cut here:
// - the line is as high as the font says (its "top" and "bottom", with the room for accents and
//   descenders: the font padding), not as high as the digits;
// - with gravity `center` the text is put in the middle of the box, but only when it fits in the box:
//   a text higher than the box starts at the top and is cut at the bottom;
// - a text wider than the box goes on to a new line (first between words, else in the middle of a word);
// - everything outside the box is cut off.

import type { Argb } from "../color.ts";
import { css } from "../color.ts";
import { androidFontMetrics } from "../font/FontFile.ts";
import type { Ctx } from "../platform.ts";
import type { StyledFont } from "./Typefaces.ts";

export type Gravity = "center" | "left" | "right";

export interface TextStyle {
    font: StyledFont;
    /** A family for characters the font does not have, or null. */
    fallbackFamily: string | null;
    /** The text size in screen pixels. */
    sizePx: number;
    color: Argb;
    gravity: Gravity;
    /** Only the first line shows (`maxLines` 1). */
    oneLine: boolean;
    /** A line through the text: the watch does this for a glucose value that is old. */
    strikeThrough: boolean;
}

export interface TextLine {
    text: string;
    /** The width of the line in pixels. */
    width: number;
    /** Where the line starts, from the left of the box. */
    x: number;
    /** The baseline, from the top of the box. */
    baseline: number;
}

export interface TextLayout {
    lines: TextLine[];
    /** The height of all lines with the font padding. */
    height: number;
    /** The widest line. */
    width: number;
    /** The text did not fit on one line and was broken (it has no line break of its own there). */
    wrapped: boolean;
    /** The text is higher than the box: it starts at the top and its bottom is cut off. */
    cutVertical: boolean;
    /** A line is wider than the box: its end is cut off. */
    cutHorizontal: boolean;
    /** The height of one line with the font padding: the smallest box height that is not cut. */
    lineHeight: number;
    /** Characters of the text the font has no glyph for. */
    missingGlyphs: string[];
}

/** The canvas font text for [style]. */
export function canvasFont(style: TextStyle): string {
    const families = `"${style.font.typeface.family}"` + (style.fallbackFamily && style.fallbackFamily !== style.font.typeface.family ? `, "${style.fallbackFamily}"` : "");
    return `${style.sizePx}px ${families}`;
}

/** The extra outline width of Android's fake bold for a text size (Skia: 1/24 of the size at 9 px, 1/32 from 36 px on). */
export function fakeBoldExtra(sizePx: number): number {
    const lo = 9, hi = 36, atLo = 1 / 24, atHi = 1 / 32;
    const factor = sizePx <= lo ? atLo : sizePx >= hi ? atHi : atLo + (atHi - atLo) * (sizePx - lo) / (hi - lo);
    return sizePx * factor;
}

/** The width of [text] as Android measures it, fake bold included. */
function measure(ctx: Ctx, text: string, style: TextStyle): number {
    if (text === "") return 0;
    return ctx.measureText(text).width + (style.font.fakeBold ? fakeBoldExtra(style.sizePx) : 0);
}

/** Breaks one paragraph into lines no wider than [maxWidth]: between words when it can, else inside a word. */
function breakParagraph(ctx: Ctx, paragraph: string, maxWidth: number, style: TextStyle): string[] {
    if (measure(ctx, paragraph, style) <= maxWidth) return [paragraph];
    const lines: string[] = [];
    // Words with the spaces that follow them: a line can break after the spaces
    const parts = paragraph.match(/\S+\s*|\s+/g) ?? [];
    let line = "";
    const pushLine = () => {
        lines.push(line.replace(/\s+$/, ""));
        line = "";
    };
    for (const part of parts) {
        if (measure(ctx, (line + part).replace(/\s+$/, ""), style) <= maxWidth) {
            line += part;
            continue;
        }
        if (line !== "") pushLine();
        if (measure(ctx, part.replace(/\s+$/, ""), style) <= maxWidth) {
            line = part;
            continue;
        }
        // A word wider than the box: as many characters as fit per line, at least one
        let rest = part;
        while (rest !== "" && measure(ctx, rest.replace(/\s+$/, ""), style) > maxWidth) {
            const chars = Array.from(rest);
            let count = 1;
            while (count < chars.length && measure(ctx, chars.slice(0, count + 1).join(""), style) <= maxWidth) count++;
            lines.push(chars.slice(0, count).join(""));
            rest = chars.slice(count).join("");
        }
        line = rest;
    }
    if (line !== "" || lines.length === 0) pushLine();
    return lines;
}

/**
 * Lays [text] out in a box of [boxWidth] x [boxHeight] pixels. Sets the font of [ctx].
 */
export function layoutText(ctx: Ctx, text: string, boxWidth: number, boxHeight: number, style: TextStyle): TextLayout {
    ctx.font = canvasFont(style);
    const metrics = androidFontMetrics(style.font.typeface.info, style.sizePx);
    const lineHeight = metrics.bottom - metrics.top;
    const lineStep = metrics.descent - metrics.ascent;
    const paragraphs = text.split("\n");
    let lines: string[] = [];
    let wrapped = false;
    for (const paragraph of paragraphs) {
        const broken = boxWidth > 0 ? breakParagraph(ctx, paragraph, boxWidth, style) : [paragraph];
        if (broken.length > 1) wrapped = true;
        lines.push(...broken);
    }
    const allLines = lines.length;
    if (style.oneLine) lines = lines.slice(0, 1);
    const height = lineHeight + (lines.length - 1) * lineStep;
    // TextView.getVerticalOffset: centred only when the box is higher than the text
    const centred = style.gravity === "center";
    const top = centred && boxHeight > height ? (boxHeight - height) >> 1 : 0;
    let widest = 0;
    let cutHorizontal = false;
    const laidOut = lines.map((line, index): TextLine => {
        const width = measure(ctx, line, style);
        widest = Math.max(widest, width);
        if (width > boxWidth + 0.5) cutHorizontal = true;
        // Layout: the line width is cut to a whole, even number before the line is placed
        const extent = Math.floor(width) & ~1;
        const x = style.gravity === "center" ? (boxWidth - extent) >> 1 : style.gravity === "right" ? boxWidth - Math.floor(width) : 0;
        return { text: line, width, x, baseline: top - metrics.top + index * lineStep };
    });
    const info = style.font.typeface.info;
    const missing = new Set<string>();
    for (const char of text) {
        const code = char.codePointAt(0)!;
        if (code > 0x20 && code !== 0xfe0e && code !== 0xfe0f && !info.hasGlyph(code)) missing.add(char);
    }
    return {
        lines: laidOut, height, width: widest, lineHeight,
        wrapped: wrapped || (style.oneLine && allLines > 1),
        cutVertical: height > boxHeight,
        cutHorizontal,
        missingGlyphs: [...missing]
    };
}

/**
 * Draws a laid out text. [ctx] must have its origin at the top left of the box and be cut to the box
 * already (the view does both).
 */
export function drawText(ctx: Ctx, layout: TextLayout, style: TextStyle): void {
    ctx.font = canvasFont(style);
    ctx.textAlign = "left";
    ctx.textBaseline = "alphabetic";
    ctx.fillStyle = css(style.color);
    const extra = style.font.fakeBold ? fakeBoldExtra(style.sizePx) : 0;
    for (const line of layout.lines) {
        if (line.text === "") continue;
        ctx.save();
        ctx.translate(line.x, line.baseline);
        if (style.font.fakeItalic) ctx.transform(1, 0, -0.25, 1, 0, 0);
        if (extra > 0) {
            // Skia makes the outline thicker by `extra` in all, and the glyph that much wider
            ctx.translate(extra / 2, 0);
            ctx.strokeStyle = css(style.color);
            ctx.lineWidth = extra;
            ctx.lineJoin = "round";
            ctx.strokeText(line.text, 0, 0);
        }
        ctx.fillText(line.text, 0, 0);
        ctx.restore();
        if (style.strikeThrough) {
            // Skia: the line is 1/18 of the text size thick, its middle 6/21 of the size above the baseline
            const thickness = style.sizePx / 18;
            ctx.fillRect(line.x, line.baseline - style.sizePx * 6 / 21 - thickness / 2, line.width, thickness);
        }
    }
}
