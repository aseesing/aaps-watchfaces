// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/utils/LogTimeGraph.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things like the Kotlin file, so a change there is easy to carry over.
//
// Not here, it is Android only: `labelsOnTop(chart: View, ...)` with `boundsOf` (the caller gives the place
// of the chart on the screen itself) and `row()` (the row of the setting in the menu of the watch).

import type { Argb } from "../../color.ts";
import { alphaOf, argb, blueOf, greenOf, redOf, rgb, WHITE, withAlpha } from "../../color.ts";
import type { SP } from "./BgGraphBuilder.ts";
import { Axis, AxisValue, Line, PointValue } from "./HelloCharts.ts";
import type { LineChartData } from "./HelloCharts.ts";
import { LogTimeScale } from "./LogTimeScale.ts";

/** The texts of the labels, as Android string resources: `%1$d` is the number. */
export interface LogTimeGraphResources {
    /** `R.string.log_graph_minutes`. Example: 30m */
    log_graph_minutes: string;
    /** `R.string.log_graph_hours`. Example: 6h */
    log_graph_hours: string;
}

/** The English texts, from wear/src/main/res/values/strings_graph.xml. */
export const LOG_TIME_GRAPH_RESOURCES: LogTimeGraphResources = { log_graph_minutes: "%1$dm", log_graph_hours: "%1$dh" };

/** A rectangle on the screen, like Android's `RectF`. */
export interface RectF {
    left: number;
    top: number;
    right: number;
    bottom: number;
}

/**
 * The glucose graph of the watch faces with a log time scale ([LogTimeScale]): a whole day, the last
 * minutes in detail. A setting of the watch, on by default. The phone sends the older glucose with
 * fewer points when it is on.
 *
 * `BgGraphBuilder` places every point with the scale; this class adds the time axis, with lines and
 * labels for how long ago ([LogTimeScale.LABEL_AGES_MS]). The labels are drawn inside the graph, so a
 * small graph keeps its height: outside, they and their margins take the whole graph of a small design.
 * They go on the bottom edge, or on the top edge when the round screen cuts the bottom edge ([labelsOnTop]).
 */
export class LogTimeGraph {
    private readonly resources: LogTimeGraphResources;

    constructor(resources: LogTimeGraphResources = LOG_TIME_GRAPH_RESOURCES) {
        this.resources = resources;
    }

    /**
     * Puts the time into [data], in [colour]: a line at now and at each label age, and the labels of how
     * long ago, on the top edge when [onTop], else on the bottom edge. Takes away the value axis and the
     * lines without values. Returns [data].
     *
     * The chart keeps a margin round the plot of the biggest point of all lines, plus 4 dp, also for a
     * line with no values (the bolus and carbs markers are twice the size of a glucose point). On a
     * watch that is a third of a small graph; without the empty lines it is less. The time lines are
     * lines of the plot, not of the axis: the chart draws axis lines over the whole view, margin and all.
     */
    label(data: LineChartData, colour: Argb, onTop = false): LineChartData {
        const lines = data.lines.filter(it => it.values.length > 0);
        const ys = lines.flatMap(line => line.values.map(it => it.y));
        data.lines = ys.length === 0 ? lines : [...this.timeLines(Math.min(...ys), Math.max(...ys), colour), ...lines];
        data.axisXBottom = onTop ? null : this.timeAxis(colour);
        data.axisXTop = onTop ? this.timeAxis(colour) : null;
        data.axisYLeft = null;
        return data;
    }

    /** Lines from [bottom] to [top] at now and at each label age, below everything else. */
    timeLines(bottom: number, top: number, colour: Argb): Line[] {
        return [0, ...LogTimeScale.LABEL_AGES_MS.map(it => LogTimeScale.xOfOffset(-it))].map(x => {
            const line = new Line([new PointValue(x, bottom), new PointValue(x, top)]);
            line.color = LogTimeGraph.lineColour(colour);
            line.strokeWidth = 1;
            line.hasPoints = false;
            return line;
        });
    }

    /**
     * The labels of how long ago, drawn inside the graph, at the label ages, in [labelColour] of the grid
     * colour [colour].
     */
    timeAxis(colour: Argb): Axis {
        const values = LogTimeScale.LABEL_AGES_MS.map(age => new AxisValue(LogTimeScale.xOfOffset(-age)).setLabel(this.ageLabel(age)));
        const axis = new Axis(values);
        axis.hasLines = false;
        axis.isInside = true;
        axis.hasSeparationLine = false;
        axis.textSize = LogTimeGraph.TEXT_SIZE_SP;
        // Lines and labels closer than this many characters are left out by the chart
        axis.maxLabelChars = LogTimeGraph.MAX_LABEL_CHARS;
        axis.lineColor = colour;
        axis.textColor = LogTimeGraph.labelColour(colour);
        return axis;
    }

    /** "30m", "1h" and so on. */
    ageLabel(ageMs: number): string {
        return ageMs < LogTimeGraph.HOUR_MS
            ? getString(this.resources.log_graph_minutes, Math.trunc(ageMs / LogTimeGraph.MINUTE_MS))
            : getString(this.resources.log_graph_hours, Math.trunc(ageMs / LogTimeGraph.HOUR_MS));
    }

    /** On until the wearer turns it off. */
    static readonly DEFAULT = true;

    /** The stored name of the setting (the text of `R.string.key_graph_log_time`), for the phone. */
    static readonly SETTING_NAME = "graph_log_time";

    private static readonly TEXT_SIZE_SP = 7;

    /** How far the labels are taken from the grid colour toward white. */
    static readonly LABEL_LIGHTER = 0.4;

    /**
     * The grey of the time lines and labels on a design without a grid: many designs make the grid colour
     * see-through to hide the grid. A middle grey reads on light and on dark graphs alike.
     */
    static readonly NO_GRID_GREY: Argb = rgb(0x8a, 0x90, 0x99);

    /** The alpha of the time lines in [NO_GRID_GREY]: faint, like a grid. */
    static readonly NO_GRID_LINE_ALPHA = 110;

    /** Below this alpha the grid colour counts as no grid at all. */
    private static readonly MIN_GRID_ALPHA = 64;

    private static hasGrid(colour: Argb): boolean {
        return alphaOf(colour) >= LogTimeGraph.MIN_GRID_ALPHA;
    }

    /** The colour of the time lines for the grid colour [colour]: that colour, or a faint grey without a grid. */
    static lineColour(colour: Argb): Argb {
        return LogTimeGraph.hasGrid(colour) ? colour : withAlpha(LogTimeGraph.NO_GRID_GREY, LogTimeGraph.NO_GRID_LINE_ALPHA);
    }

    /**
     * The colour of the labels for the grid colour [colour]: lighter, with its own alpha. The grid colour of a
     * design is often nearly the colour of the graph's background, fine for a line, but text in it cannot be
     * read on a watch. Without a grid: [NO_GRID_GREY].
     */
    static labelColour(colour: Argb): Argb {
        return LogTimeGraph.hasGrid(colour)
            ? withAlpha(blendARGB(colour, WHITE, LogTimeGraph.LABEL_LIGHTER), alphaOf(colour))
            : LogTimeGraph.NO_GRID_GREY;
    }

    private static readonly MAX_LABEL_CHARS = 2;
    private static readonly MINUTE_MS = 60_000;
    private static readonly HOUR_MS = 60 * LogTimeGraph.MINUTE_MS;

    /**
     * True when the labels go on the top edge of [chart] (in pixels of a screen of [screenWidth] x
     * [screenHeight]): the round screen cuts off part of the bottom edge, and less of the top edge. A
     * graph low on the screen loses the corners of its bottom edge, and with them the labels there.
     * Every screen counts as round here: the watch faces are made for round watches.
     */
    static labelsOnTop(chart: RectF, screenWidth: number, screenHeight: number): boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || chart.right - chart.left <= 0) return false;
        const bottom = LogTimeGraph.visibleWidth(chart, chart.bottom, screenWidth, screenHeight);
        const top = LogTimeGraph.visibleWidth(chart, chart.top, screenWidth, screenHeight);
        return bottom < chart.right - chart.left - LogTimeGraph.EDGE_TOLERANCE_PX && top > bottom;
    }

    /** How much of the line at height [y] from [chart]'s left to its right is on the round screen. */
    private static visibleWidth(chart: RectF, y: number, screenWidth: number, screenHeight: number): number {
        const radius = Math.min(screenWidth, screenHeight) / 2;
        const dy = Math.abs(y - screenHeight / 2);
        if (dy >= radius) return 0;
        const half = Math.sqrt(radius * radius - dy * dy);
        return Math.max(Math.min(chart.right, screenWidth / 2 + half) - Math.max(chart.left, screenWidth / 2 - half), 0);
    }

    /** An edge cut by less than this is not cut: the labels are a little inside it. */
    private static readonly EDGE_TOLERANCE_PX = 2;

    /** True when the watch graphs use the log time scale. */
    static isOn(sp: SP): boolean {
        return sp.getBoolean(LogTimeGraph.SETTING_NAME, LogTimeGraph.DEFAULT);
    }

    /** The scale for a graph made at [now], or null when the setting is off. */
    static scaleOrNull(sp: SP, now: number): LogTimeScale | null {
        return LogTimeGraph.isOn(sp) ? new LogTimeScale(now) : null;
    }
}

/** Android's `Resources.getString(id, number)` for a text with one `%1$d`. */
function getString(format: string, number: number): string {
    return format.replace("%1$d", String(number));
}

/**
 * `ColorUtils.blendARGB` of androidx: [color1] taken [ratio] of the way to [color2]. It works with
 * 32 bit floats and cuts the result off to whole numbers, so this does the same.
 */
function blendARGB(color1: Argb, color2: Argb, ratio: number): Argb {
    const ratioF = Math.fround(ratio);
    const inverseRatio = Math.fround(1 - ratioF);
    const blend = (c1: number, c2: number): number => Math.trunc(Math.fround(Math.fround(c1 * inverseRatio) + Math.fround(c2 * ratioF)));
    return argb(
        blend(alphaOf(color1), alphaOf(color2)), blend(redOf(color1), redOf(color2)),
        blend(greenOf(color1), greenOf(color2)), blend(blueOf(color1), blueOf(color2))
    );
}
