// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/utils/BgGraphBuilder.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things like the Kotlin file, so a change there is easy to carry over.
//
// What is different from the Kotlin file:
// - The clock and the time zone are given to the constructor ([now], [tz]). Kotlin asks the system for them.
// - `sp` and `dateUtil` are the small interfaces [SP] and [DateUtil] below, with only what the graph uses.
// - The second constructor (low resolution: one colour for all glucose) is not here. The caller gives the
//   same colour three times, see WatchChart.ts.
// - A number that is a `Float` in Kotlin is rounded to a 32 bit float here too (`Math.fround`, and inside
//   `PointValue`), so the points are at the same places as on the watch.

import type { Argb } from "../../color.ts";
import type { SingleBg } from "../EventData.ts";
import type { GlucoseLine } from "./GlucoseLine.ts";
import { Axis, AxisValue, Line, LineChartData, PointValue } from "./HelloCharts.ts";
import { LogTimeGraph } from "./LogTimeGraph.ts";
import type { LogTimeScale } from "./LogTimeScale.ts";
import type { Basal, TempBasal, Treatment } from "./TreatmentData.ts";

/** The settings of the watch, read like the Kotlin class `SP` (shared preferences). */
export interface SP {
    /** The setting [key], or [defaultValue] when the wearer never set it. */
    getBoolean(key: string, defaultValue: boolean): boolean;
}

/** The names of the settings the graph reads: the texts of `R.string.key_...` in wear/src/main/res/values/strings.xml. */
export const R = {
    string: {
        key_show_graph_basal: "show_graph_basal",
        key_show_graph_temp_basal: "show_graph_temp_basal",
        key_show_graph_carbs: "show_graph_carbs",
        key_show_graph_bolus: "show_graph_bolus",
        key_show_graph_grid: "show_graph_grid",
        key_highlight_basals: "highlight_basals",
        key_prediction_lines: "prediction_lines"
    }
} as const;

/** The two texts the graph takes from the Kotlin class `DateUtil`. */
export interface DateUtil {
    /** The time as `HH:mm`, or as `hh:mm a` on a watch with a 12 hour clock. */
    timeString(mills: number): string;

    /** The hour as `HH`, or as `hh` on a watch with a 12 hour clock. */
    hourString(mills: number): string;
}

const HOUR_MS = 1000 * 60 * 60;

/** Android's `DashPathEffect(floatArrayOf(4f, 3f), 4f)` of the basal line: 4 pixels line, 3 pixels gap. */
const BASAL_DASH_INTERVALS = [4, 3];
const BASAL_DASH_PHASE = 4;

/**
 * Builds the blood glucose graph of the Wear OS watch faces.
 *
 * Makes a chart of many lines:
 * - the glucose readings (coloured by range: high, in range, low)
 * - the prediction lines (COB, IOB, UAM, ZT)
 * - the treatment markers (boluses, carbs, temp basals)
 * - the basal rate profile
 * - the target range lines
 * - the time axis with its grid
 *
 * The graph shows the set time span (1 to 5 hours) and gets wider to fit predictions.
 */
export class BgGraphBuilder {
    private readonly sp: SP;
    private readonly dateUtil: DateUtil;
    private readonly bgDataList: SingleBg[];
    private readonly predictionsList: SingleBg[];
    private readonly tempWatchDataList: TempBasal[];
    private readonly basalWatchDataList: Basal[];
    private readonly bolusWatchDataList: Treatment[];
    private readonly pointSize: number;
    private readonly highColor: Argb;
    private readonly lowColor: Argb;
    private readonly midColor: Argb;
    private readonly gridColour: Argb;
    private readonly basalBackgroundColor: Argb;
    private readonly basalCenterColor: Argb;
    private readonly bolusInvalidColor: Argb;
    private readonly carbsColor: Argb;
    private readonly timeSpan: number;
    /** `System.currentTimeMillis()` in Kotlin. */
    private readonly now: number;
    /** `TimeZone.currentSystemDefault()` in Kotlin: an IANA name, or undefined for the zone of this machine. */
    private readonly tz: string | undefined;

    private endingTime: number;
    private startingTime: number;
    private fuzzyTimeDiv = 1000 * 60 * 1;
    private highMark: number;
    private lowMark: number;
    private readonly inRangeValues: PointValue[] = [];
    private readonly highValues: PointValue[] = [];
    private readonly lowValues: PointValue[] = [];
    glucoseLine: GlucoseLine | null = null; // PERSONAL PATCH: the glucose as a line, see GlucoseLine

    private get predictionEndTime(): number {
        let maxPredictionDate = this.now;
        for (const { timeStamp } of this.predictionsList) {
            if (maxPredictionDate < timeStamp) {
                maxPredictionDate = timeStamp;
            }
        }
        return Math.trunc(Math.min(maxPredictionDate, this.now + BgGraphBuilder.MAX_PREDICTION__TIME_RATIO * this.timeSpan * 1000 * 60 * 60));
    }

    /**
     * @param sp the settings of the watch (grid, predictions and so on)
     * @param dateUtil gives the texts of the time axis
     * @param bgDataList the glucose readings to draw. Not empty: the last one gives the high and low mark
     * @param predictionsList predicted glucose (COB, IOB, UAM, ZT)
     * @param tempWatchDataList the temporary basal rates
     * @param basalWatchDataList the basal profile
     * @param bolusWatchDataList boluses, SMBs and carbs
     * @param pointSize the radius of a glucose point in dp
     * @param highColor the colour of glucose above the range
     * @param lowColor the colour of glucose below the range
     * @param midColor the colour of glucose in the range
     * @param gridColour the colour of the grid lines and the axis
     * @param basalBackgroundColor the colour of the temp basal lines
     * @param basalCenterColor the colour of the basal profile line, and of the bolus and SMB points
     * @param bolusInvalidColor the colour of the points of treatments that are not valid
     * @param carbsColor the colour of the carbs points
     * @param timeSpan the time span to show in hours (1 to 5)
     * @param now the time the graph is made, epoch ms
     * @param tz the time zone of the full hours on the time axis
     */
    constructor(
        sp: SP,
        dateUtil: DateUtil,
        bgDataList: SingleBg[],
        predictionsList: SingleBg[],
        tempWatchDataList: TempBasal[],
        basalWatchDataList: Basal[],
        bolusWatchDataList: Treatment[],
        pointSize: number,
        highColor: Argb,
        lowColor: Argb,
        midColor: Argb,
        gridColour: Argb,
        basalBackgroundColor: Argb,
        basalCenterColor: Argb,
        bolusInvalidColor: Argb,
        carbsColor: Argb,
        timeSpan: number,
        now: number,
        tz?: string
    ) {
        this.sp = sp;
        this.dateUtil = dateUtil;
        this.bgDataList = bgDataList;
        this.predictionsList = predictionsList;
        this.tempWatchDataList = tempWatchDataList;
        this.basalWatchDataList = basalWatchDataList;
        this.bolusWatchDataList = bolusWatchDataList;
        this.pointSize = pointSize;
        this.highColor = highColor;
        this.lowColor = lowColor;
        this.midColor = midColor;
        this.gridColour = gridColour;
        this.basalBackgroundColor = basalBackgroundColor;
        this.basalCenterColor = basalCenterColor;
        this.bolusInvalidColor = bolusInvalidColor;
        this.carbsColor = carbsColor;
        this.timeSpan = timeSpan;
        this.now = now;
        this.tz = tz;

        this.endingTime = now + 1000 * 60 * 6 * timeSpan;
        this.startingTime = now - 1000 * 60 * 60 * timeSpan;
        this.highMark = bgDataList[bgDataList.length - 1].high;
        this.lowMark = bgDataList[bgDataList.length - 1].low;

        if (sp.getBoolean(R.string.key_prediction_lines, true))
            this.endingTime = Math.max(this.predictionEndTime, this.endingTime);

        // PERSONAL PATCH: the log time scale (see LogTimeGraph): a whole day back, at most an hour of predictions
        this.logScale = LogTimeGraph.scaleOrNull(sp, now);
        const scale = this.logScale;
        if (scale !== null) {
            this.startingTime = scale.start;
            this.endingTime = scale.end(sp.getBoolean(R.string.key_prediction_lines, true) ? maxOfOrNull(predictionsList.map(it => it.timeStamp)) : null);
        }
    }

    /**
     * Builds the whole chart:
     * 1. the glucose lines (high, in range and low values)
     * 2. the prediction lines (when on)
     * 3. the treatment lines (bolus, carbs, temp basals, when on)
     * 4. the target range lines (high and low mark)
     * 5. the basal profile (when on)
     * 6. the axes and the grid (when on)
     */
    lineData(): LineChartData {
        const lineData = new LineChartData(this.defaultLines());
        if (this.sp.getBoolean(R.string.key_show_graph_grid, true)) {
            lineData.axisYLeft = this.yAxis();
            lineData.axisXBottom = this.xAxis();
        }
        return lineData;
    }

    private defaultLines(): Line[] {
        this.addBgReadingValues();
        const lines: Line[] = [];
        lines.push(this.highLine());
        lines.push(this.lowLine());
        lines.push(this.inRangeValuesLine());
        lines.push(this.lowValuesLine());
        lines.push(this.highValuesLine());
        let minChart = this.lowMark;
        let maxChart = this.highMark;
        for (const { sgv } of this.bgDataList) {
            if (sgv > maxChart) maxChart = sgv;
            if (sgv < minChart) minChart = sgv;
        }
        let maxBasal = 0.1;
        for (const { amount } of this.basalWatchDataList) {
            if (amount > maxBasal) maxBasal = amount;
        }
        let maxTemp = maxBasal;
        for (const { amount } of this.tempWatchDataList) {
            if (amount > maxTemp) maxTemp = amount;
        }
        let factor = (maxChart - minChart) / maxTemp;
        // in case basal is the highest, don't paint it totally at the top.
        factor = Math.min(factor, (maxChart - minChart) / maxBasal * (2 / 3.0));
        const highlight = this.sp.getBoolean(R.string.key_highlight_basals, false);
        if (this.sp.getBoolean(R.string.key_show_graph_temp_basal, true))
            for (const twd of this.tempWatchDataList) {
                if (twd.endTime > this.startingTime) {
                    lines.push(this.tempValuesLine(twd, Math.fround(minChart), factor, false, highlight ? this.pointSize + 1 : this.pointSize));
                    if (highlight) lines.push(this.tempValuesLine(twd, Math.fround(minChart), factor, true, 1));
                }
            }
        if (this.sp.getBoolean(R.string.key_prediction_lines, true))
            this.addPredictionLines(lines);
        if (this.sp.getBoolean(R.string.key_show_graph_basal, true))
            lines.push(this.basalLine(Math.fround(minChart), factor, highlight));
        lines.push(this.bolusInvalidLine(Math.fround(minChart)));
        if (this.sp.getBoolean(R.string.key_show_graph_carbs, true))
            lines.push(this.carbsLine(Math.fround(minChart)));
        if (this.sp.getBoolean(R.string.key_show_graph_bolus, true)) {
            lines.push(this.bolusLine(Math.fround(minChart)));
            lines.push(this.smbLine(Math.fround(minChart)));
        }
        this.glucoseLine?.replaceDots(lines, lines.slice(2, 5), this.bgDataList.map(it => it.timeStamp), value => this.fuzz(value)); // PERSONAL PATCH: the glucose as a line on top, see GlucoseLine
        return lines;
    }

    private basalLine(offset: number, factor: number, highlight: boolean): Line {
        const pointValues: PointValue[] = [];
        for (const { startTime, endTime, amount } of this.basalWatchDataList) {
            if (endTime > this.startingTime) {
                const begin = Math.max(this.startingTime, startTime);
                pointValues.push(new PointValue(this.fuzz(begin), offset + Math.fround(factor * amount)));
                pointValues.push(new PointValue(this.fuzz(endTime), offset + Math.fround(factor * amount)));
            }
        }
        const basalLine = new Line(pointValues);
        basalLine.hasPoints = false;
        basalLine.color = this.basalCenterColor;
        basalLine.pathEffect = { intervals: BASAL_DASH_INTERVALS, phase: BASAL_DASH_PHASE };
        basalLine.strokeWidth = highlight ? 2 : 1;
        return basalLine;
    }

    private bolusLine(offset: number): Line {
        const pointValues: PointValue[] = [];
        for (const { date, bolus, isSMB, isValid } of this.bolusWatchDataList) {
            if (date >= this.startingTime + 1 && date <= this.endingTime && !isSMB && isValid && bolus > 0) {
                pointValues.push(new PointValue(this.fuzz(date), offset - this.pointSize * 3));
            }
        }
        const line = new Line(pointValues);
        line.color = this.basalCenterColor;
        line.hasLines = false;
        line.pointRadius = this.pointSize * 2;
        line.hasPoints = true;
        return line;
    }

    private smbLine(offset: number): Line {
        const pointValues: PointValue[] = [];
        for (const { date, bolus, isSMB, isValid } of this.bolusWatchDataList) {
            if (date >= this.startingTime + 1 && date <= this.endingTime && isSMB && isValid && bolus > 0) {
                pointValues.push(new PointValue(this.fuzz(date), offset - this.pointSize * 3));
            }
        }
        const line = new Line(pointValues);
        line.color = this.basalCenterColor;
        line.hasLines = false;
        line.pointRadius = this.pointSize;
        line.hasPoints = true;
        return line;
    }

    private bolusInvalidLine(offset: number): Line {
        const pointValues: PointValue[] = [];
        for (const { date, bolus, carbs, isValid } of this.bolusWatchDataList) {
            if (date >= this.startingTime + 1 && date <= this.endingTime && !(isValid && (bolus > 0 || carbs > 0))) {
                pointValues.push(new PointValue(this.fuzz(date), offset - this.pointSize * 3));
            }
        }
        const line = new Line(pointValues);
        line.color = this.bolusInvalidColor;
        line.hasLines = false;
        line.pointRadius = this.pointSize;
        line.hasPoints = true;
        return line;
    }

    private carbsLine(offset: number): Line {
        const pointValues: PointValue[] = [];
        for (const { date, carbs, isSMB, isValid } of this.bolusWatchDataList) {
            if (date >= this.startingTime + 1 && date <= this.endingTime && !isSMB && isValid && carbs > 0) {
                pointValues.push(new PointValue(this.fuzz(date), offset + this.pointSize * 3));
            }
        }
        const line = new Line(pointValues);
        line.color = this.carbsColor;
        line.hasLines = false;
        line.pointRadius = this.pointSize * 2;
        line.hasPoints = true;
        return line;
    }

    private addPredictionLines(lines: Line[]): void {
        const values = new Map<number, PointValue[]>();
        for (const { timeStamp, sgv, color } of this.predictionsList) {
            if (timeStamp <= this.predictionEndTime && this.logScale?.shows(timeStamp) !== false) { // PERSONAL PATCH: log scale ends sooner
                const value = Math.min(sgv, BgGraphBuilder.UPPER_CUTOFF_SGV);
                let list = values.get(color);
                if (list === undefined) {
                    list = [];
                    values.set(color, list);
                }
                list.push(new PointValue(this.fuzz(timeStamp), value));
            }
        }
        for (const [key, value] of hashMapOrder(values)) {
            const line = new Line(value);
            line.color = key;
            line.hasLines = false;
            let size = Math.trunc(this.pointSize / 2);
            size = size > 0 ? size : 1;
            line.pointRadius = size;
            line.hasPoints = true;
            lines.push(line);
        }
    }

    private highValuesLine(): Line {
        const highValuesLine = new Line(this.highValues);
        highValuesLine.color = this.highColor;
        highValuesLine.hasLines = false;
        highValuesLine.pointRadius = this.pointSize;
        highValuesLine.hasPoints = true;
        return highValuesLine;
    }

    private lowValuesLine(): Line {
        const lowValuesLine = new Line(this.lowValues);
        lowValuesLine.color = this.lowColor;
        lowValuesLine.hasLines = false;
        lowValuesLine.pointRadius = this.pointSize;
        lowValuesLine.hasPoints = true;
        return lowValuesLine;
    }

    private inRangeValuesLine(): Line {
        const inRangeValuesLine = new Line(this.inRangeValues);
        inRangeValuesLine.color = this.midColor;
        inRangeValuesLine.pointRadius = this.pointSize;
        inRangeValuesLine.hasPoints = true;
        inRangeValuesLine.hasLines = false;
        return inRangeValuesLine;
    }

    private tempValuesLine(twd: TempBasal, offset: number, factor: number, isHighlightLine: boolean, strokeWidth: number): Line {
        const lineValues: PointValue[] = [];
        const begin = Math.max(this.startingTime, twd.startTime);
        lineValues.push(new PointValue(this.fuzz(begin), offset + Math.fround(factor * twd.startBasal)));
        lineValues.push(new PointValue(this.fuzz(begin), offset + Math.fround(factor * twd.amount)));
        lineValues.push(new PointValue(this.fuzz(twd.endTime), offset + Math.fround(factor * twd.amount)));
        lineValues.push(new PointValue(this.fuzz(twd.endTime), offset + Math.fround(factor * twd.endBasal)));
        const valueLine = new Line(lineValues);
        valueLine.hasPoints = false;
        if (isHighlightLine) {
            valueLine.color = this.basalCenterColor;
            valueLine.strokeWidth = 1;
        } else {
            valueLine.color = this.basalBackgroundColor;
            valueLine.strokeWidth = strokeWidth;
        }
        return valueLine;
    }

    private addBgReadingValues(): void {
        for (const { timeStamp, sgv } of this.bgDataList) {
            if (timeStamp > this.startingTime) {
                if (sgv >= 450) this.highValues.push(new PointValue(this.fuzz(timeStamp), 450));
                else if (sgv >= this.highMark) this.highValues.push(new PointValue(this.fuzz(timeStamp), sgv));
                else if (sgv >= this.lowMark) this.inRangeValues.push(new PointValue(this.fuzz(timeStamp), sgv));
                else if (sgv >= 40) this.lowValues.push(new PointValue(this.fuzz(timeStamp), sgv));
                else if (sgv >= 11) this.lowValues.push(new PointValue(this.fuzz(timeStamp), 40));
            }
        }
    }

    private highLine(): Line {
        const highLineValues: PointValue[] = [];
        highLineValues.push(new PointValue(this.fuzz(this.startingTime), this.highMark));
        highLineValues.push(new PointValue(this.fuzz(this.endingTime), this.highMark));
        const highLine = new Line(highLineValues);
        highLine.hasPoints = false;
        highLine.strokeWidth = 1;
        highLine.color = this.highColor;
        return highLine;
    }

    private lowLine(): Line {
        const lowLineValues: PointValue[] = [];
        lowLineValues.push(new PointValue(this.fuzz(this.startingTime), this.lowMark));
        lowLineValues.push(new PointValue(this.fuzz(this.endingTime), this.lowMark));
        const lowLine = new Line(lowLineValues);
        lowLine.hasPoints = false;
        lowLine.color = this.lowColor;
        lowLine.strokeWidth = 1;
        return lowLine;
    }

    /////////AXIS RELATED//////////////
    private yAxis(): Axis {
        const yAxis = new Axis();
        yAxis.isAutoGenerated = true;
        const axisValues: AxisValue[] = [];
        yAxis.values = axisValues; // like in the library, this turns isAutoGenerated off again: the axis has no labels
        yAxis.hasLines = false;
        yAxis.lineColor = this.gridColour;
        return yAxis;
    }

    private xAxis(): Axis {
        const timeNow = this.now;
        const xAxis = new Axis();
        xAxis.isAutoGenerated = false;
        const xAxisValues: AxisValue[] = [];

        //get the time-tick at the full hour after start_time
        const tz = this.tz;
        let hourInstant = truncatedHour(this.startingTime, tz) + HOUR_MS;

        //Display current time on the graph
        xAxisValues.push(new AxisValue(this.fuzz(timeNow)).setLabel(this.dateUtil.timeString(timeNow)));
        let hourTick = hourInstant;

        // add all full hours within the timeframe
        while (hourTick < this.endingTime) {
            if (Math.abs(hourTick - timeNow) > Math.trunc(8 * (this.endingTime - this.startingTime) / 60)) {
                xAxisValues.push(new AxisValue(this.fuzz(hourTick)).setLabel(this.dateUtil.hourString(hourTick)));
            } else {
                //don't print hour label if too close to now to avoid overlaps
                xAxisValues.push(new AxisValue(this.fuzz(hourTick)).setLabel(""));
            }

            //increment by one hour. Kotlin adds one hour of real time here, also over a change of summer time
            hourInstant = hourInstant + HOUR_MS;
            hourTick = hourInstant;
        }
        xAxis.values = xAxisValues;
        xAxis.textSize = 10;
        xAxis.hasLines = true;
        xAxis.lineColor = this.gridColour;
        xAxis.textColor = this.gridColour;
        return xAxis;
    }

    private fuzz(value: number): number {
        if (this.logScale !== null) return this.logScale.x(value); // PERSONAL PATCH: log time scale, see LogTimeGraph
        return Math.fround(Math.round(value / this.fuzzyTimeDiv));
    }

    // PERSONAL PATCH: the log time scale (see LogTimeGraph): a whole day back, at most an hour of predictions
    private readonly logScale: LogTimeScale | null;

    static readonly MAX_PREDICTION__TIME_RATIO = 3.0 / 5;
    static readonly UPPER_CUTOFF_SGV = 400.0;
}

/** Kotlin's `maxOfOrNull`: the biggest of [values], or null when there are none. */
function maxOfOrNull(values: number[]): number | null {
    return values.length === 0 ? null : values.reduce((a, b) => Math.max(a, b));
}

/**
 * The entries of [map] in the order a Java `HashMap` with `Int` keys gives them: by their place in the
 * hash table, not by the order they were put in. The Kotlin code walks such a map of the prediction
 * colours, so this is the order of the prediction lines on the watch.
 */
function hashMapOrder<V>(map: Map<number, V>): [number, V][] {
    let capacity = 16;
    while (map.size > capacity * 0.75) capacity *= 2;
    const bucket = (key: number): number => (key ^ (key >>> 16)) & (capacity - 1);
    // The sort keeps the order of putting in for keys in the same bucket, like the table does
    return [...map.entries()].sort((a, b) => bucket(a[0]) - bucket(b[0]));
}

/**
 * The start of the hour of [mills] on the clock of the time zone [tz] (undefined: the zone of this
 * machine), as epoch ms. Kotlin does this with `LocalDateTime(year, month, day, hour, 0).toInstant(tz)`.
 */
function truncatedHour(mills: number, tz: string | undefined): number {
    const parts = new Intl.DateTimeFormat("en-US", { timeZone: tz, minute: "numeric", second: "numeric" }).formatToParts(mills);
    const part = (type: string): number => Number(parts.find(it => it.type === type)?.value ?? 0);
    const millisecond = ((mills % 1000) + 1000) % 1000;
    return mills - millisecond - part("second") * 1000 - part("minute") * 60_000;
}
