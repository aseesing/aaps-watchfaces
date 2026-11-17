// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/utils/GlucoseLine.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things like the Kotlin file, so a change there is easy to carry over.

import type { Argb } from "../../color.ts";
import { Line, PointValue } from "./HelloCharts.ts";

/** One glucose reading: its [time], where it is drawn ([x], [y]) and the colour of its dot. `GlucoseLine.Point` in Kotlin. */
export interface Point {
    time: number;
    x: number;
    y: number;
    colour: Argb;
}

/**
 * The glucose of the graph drawn as a line, [width] dp wide, in place of the loose dots that
 * `BgGraphBuilder` draws. A design asks for it with [WIDTH_KEY] in its json, and can give the line one
 * colour with [COLOUR_KEY]. Without a colour ([colour] null) each part of the line keeps the colour of
 * its glucose dots: in range, high or low. Where the colour changes, it changes half way between the
 * two readings.
 *
 * The line breaks where readings are missing ([isGap]), so a sensor gap is not drawn as a straight line.
 * The newest reading gets a dot with a radius of [width], so the end of the line shows. A reading alone
 * between two gaps is a dot too, or it would not show at all.
 */
export class GlucoseLine {
    readonly width: number;
    readonly colour: Argb | null;

    constructor(width: number, colour: Argb | null) {
        this.width = width;
        this.colour = colour;
    }

    /**
     * Takes the glucose [dots] (lines of [lines], one per colour, as `BgGraphBuilder` makes them) out of
     * [lines] and puts the glucose line at the end of [lines]: it is drawn last, over the basal and the
     * treatments, so they never hide it. [times] are the times of the readings and [xOf] gives where a
     * time is drawn: the dots do not carry their time.
     */
    replaceDots(lines: Line[], dots: Line[], times: number[], xOf: (time: number) => number): void {
        if (!dots.some(dot => lines.some(it => it === dot))) return;
        const timeAt = new Map<number, number>(times.map(time => [xOf(time), time]));
        const points = dots.flatMap(dot => dot.values.flatMap((value): Point[] => {
            const time = timeAt.get(value.x);
            return time === undefined ? [] : [{ time, x: value.x, y: value.y, colour: dot.color }];
        }));
        for (let index = lines.length - 1; index >= 0; index--) {
            if (dots.some(it => it === lines[index])) lines.splice(index, 1);
        }
        lines.push(...this.lines(points));
    }

    /** The lines that draw [points], in any order. */
    lines(points: Point[]): Line[] {
        const sorted = [...points].sort((a, b) => a.x - b.x || a.time - b.time);
        if (sorted.length === 0) return [];
        const newest = sorted[sorted.length - 1];
        const result: Line[] = [];
        let part = [value(sorted[0])];
        let partColour = this.colourOf(sorted[0]);
        for (let index = 0; index + 1 < sorted.length; index++) {
            const older = sorted[index];
            const newer = sorted[index + 1];
            const newerColour = this.colourOf(newer);
            if (GlucoseLine.isGap(older.time, newer.time, newest.time)) {
                result.push(this.part(part, partColour));
                part = [value(newer)];
            } else if (newerColour !== partColour) {
                const middle = new PointValue((older.x + newer.x) / 2, (older.y + newer.y) / 2);
                part.push(middle);
                result.push(this.part(part, partColour));
                part = [new PointValue(middle.x, middle.y), value(newer)];
            } else {
                part.push(value(newer));
            }
            partColour = newerColour;
        }
        result.push(this.part(part, partColour));
        const dot = new Line([value(newest)]);
        dot.color = this.colourOf(newest);
        dot.hasLines = false;
        dot.hasPoints = true;
        dot.pointRadius = this.width;
        result.push(dot);
        return result;
    }

    private colourOf(point: Point): Argb {
        return this.colour ?? point.colour;
    }

    /** One piece of the line. A piece of one reading has no length, so it is a dot of the line's width. */
    private part(values: PointValue[], partColour: Argb): Line {
        const line = new Line(values);
        line.color = partColour;
        line.strokeWidth = this.width;
        line.hasLines = true;
        line.hasPoints = values.length === 1;
        line.pointRadius = Math.max(1, Math.trunc(this.width / 2));
        return line;
    }

    /** The design json key of the width of the line, in dp. Without it, or 0, the glucose stays dots. */
    static readonly WIDTH_KEY = "graphLineWidth";

    /** The design json key of the colour of the whole line. Without it the line has the colours of the dots. */
    static readonly COLOUR_KEY = "graphLineColor";

    /** No wider than this many dp: the graph of a watch is small. */
    static readonly MAX_WIDTH = 8;

    /** Readings further apart than this always break the line: 3 readings missed. */
    static readonly MIN_GAP_MS = 15 * 60_000;

    /**
     * With the log time graph the phone sends older glucose with fewer points, one per a twelfth of its
     * age (`WearGraphHistory`). Twice that step is still no gap.
     */
    static readonly GAP_PARTS = 6;

    /** The glucose line a design asks for with [width] (in dp, from [WIDTH_KEY]) and [colour], or null for dots. */
    static of(width: number, colour: Argb | null): GlucoseLine | null {
        return width > 0 ? new GlucoseLine(Math.min(width, GlucoseLine.MAX_WIDTH), colour) : null;
    }

    /** True when readings at [older] and [newer] are too far apart to be joined, with [newest] the newest reading. */
    static isGap(older: number, newer: number, newest: number): boolean {
        return newer - older > Math.max(GlucoseLine.MIN_GAP_MS, Math.trunc((newest - older) / GlucoseLine.GAP_PARTS));
    }
}

/** The place of [point] as a value of a line. `Point.value()` in Kotlin. */
function value(point: Point): PointValue {
    return new PointValue(point.x, point.y);
}
