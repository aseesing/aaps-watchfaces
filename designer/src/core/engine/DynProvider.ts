// Port of the classes `DynProvider`, `DataRange` and the maths of `ValueMap` in
// wear/src/main/kotlin/app/aaps/wear/watchfaces/CustomWatchface.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order like the Kotlin file, so a change there is easy to carry over.
//
// A `dynData` (or `dynPref`) block makes a view follow a value: it picks a picture, colour, size,
// offset or text by the step the value is in, or moves and turns the view in proportion to the value.

import type { Argb } from "../color.ts";
import type { JsonObject } from "../json.ts";
import { has, optDouble, optInt, optObject, optString } from "../json.ts";
import type { ValueDef } from "./keys.ts";

/** A picture of the design, as a view draws it. */
export interface Drawable<P> {
    readonly picture: P;
    /** An svg: Android cannot colour it (`PictureDrawable` has no colour filter). */
    readonly isSvg: boolean;
}

/** What a [DynProvider] needs from the face. */
export interface DynHost<P> {
    readonly zoomFactor: number;
    /** `CustomWatchface.getColor`. */
    getColor(color: string, fallback?: Argb): Argb;
    /** A picture of the design by its key (`resDataMap[key]?.toDrawable(...)`), or null. */
    drawable(key: string): Drawable<P> | null;
    /** The value of [value] now, or null when there is none (`DynProvider.dataValue`). */
    dataValue(value: ValueDef): number | null;
}

class DataRange {
    readonly minData: number;
    readonly maxData: number;
    readonly invalidData: number;

    constructor(minData: number, maxData: number, invalidData = 0) {
        this.minData = minData;
        this.maxData = maxData;
        this.invalidData = invalidData;
    }
}

/** `ValueMap.dynValue`: [dataValue] cut to [dataRange] and mapped onto [valueRange]. */
function dynValue(dataValue: number, dataRange: DataRange, valueRange: DataRange): number {
    const cut = dataValue < dataRange.minData ? dataRange.minData : dataValue > dataRange.maxData ? dataRange.maxData : dataValue;
    return dataRange.minData !== dataRange.maxData
        ? valueRange.minData + (cut - dataRange.minData) * (valueRange.maxData - valueRange.minData) / (dataRange.maxData - dataRange.minData)
        : cut;
}

/** `ValueMap.stepValue`: the step (1..[step]) [dataValue] is in, when [range] is cut into [step] equal parts. */
export function stepValue(dataValue: number, range: { minData: number; maxData: number }, step: number): number {
    const cut = dataValue < range.minData ? range.minData
        : dataValue >= range.maxData ? range.maxData * 0.9999 // to avoid dataValue == maxData and be out of range
            : dataValue;
    return range.minData !== range.maxData ? Math.trunc(1 + ((cut - range.minData) * step) / (range.maxData - range.minData)) : 0;
}

/** Kotlin `Double.toInt()`: towards zero, NaN is 0. */
const toInt = (value: number): number => Number.isNaN(value) ? 0 : Math.trunc(value);

export class DynProvider<P> {

    private readonly host: DynHost<P>;
    readonly dataJson: JsonObject;
    readonly valueMap: ValueDef;
    private readonly dynDrawable = new Map<number, Drawable<P> | null>();
    private readonly dynColor = new Map<number, Argb>();
    private readonly dynFontColor = new Map<number, Argb>();
    private readonly dynTextSize = new Map<number, number>();
    private readonly dynLeftOffset = new Map<number, number>();
    private readonly dynTopOffset = new Map<number, number>();
    private readonly dynRotationOffset = new Map<number, number>();
    private readonly dynTextValue = new Map<number, string>();
    private dataRange: DataRange | null = null;
    private topRange: DataRange | null = null;
    private leftRange: DataRange | null = null;
    private dynRange: DataRange | null = null;
    private rotationRange: DataRange | null = null;

    constructor(host: DynHost<P>, dataJson: JsonObject, valueMap: ValueDef) {
        this.host = host;
        this.dataJson = dataJson;
        this.valueMap = valueMap;
    }

    private static steps<T>(map: Map<number, T>): number {
        return map.has(0) ? map.size - 1 : map.size;
    }

    get stepDraw(): number {
        return this.dynDrawable.size - 1;
    }

    get stepColor(): number {
        return DynProvider.steps(this.dynColor);
    }

    get stepFontColor(): number {
        return DynProvider.steps(this.dynFontColor);
    }

    get stepTextSize(): number {
        return DynProvider.steps(this.dynTextSize);
    }

    get stepLeftOffset(): number {
        return DynProvider.steps(this.dynLeftOffset);
    }

    get stepTopOffset(): number {
        return DynProvider.steps(this.dynTopOffset);
    }

    get stepRotationOffset(): number {
        return DynProvider.steps(this.dynRotationOffset);
    }

    get stepTextValue(): number {
        return DynProvider.steps(this.dynTextValue);
    }

    /** The number of `image<n>` keys, for the checks. */
    get imageCount(): number {
        return this.dynDrawable.size - (this.dynDrawable.has(0) ? 1 : 0);
    }

    get hasInvalidImage(): boolean {
        return this.dynDrawable.has(0);
    }

    dataValue(): number | null {
        return this.host.dataValue(this.valueMap);
    }

    getTopOffset(): number {
        const dataRange = this.dataRange, topRange = this.topRange;
        if (!dataRange || !topRange) return 0;
        const value = this.dataValue();
        return value !== null ? toInt(dynValue(value, dataRange, topRange) * this.host.zoomFactor) : toInt(topRange.invalidData * this.host.zoomFactor);
    }

    getLeftOffset(): number {
        const dataRange = this.dataRange, leftRange = this.leftRange;
        if (!dataRange || !leftRange) return 0;
        const value = this.dataValue();
        return value !== null ? toInt(dynValue(value, dataRange, leftRange) * this.host.zoomFactor) : toInt(leftRange.invalidData * this.host.zoomFactor);
    }

    getRotationOffset(): number {
        const dataRange = this.dataRange, rotRange = this.rotationRange;
        if (!dataRange || !rotRange) return 0;
        const value = this.dataValue();
        return value !== null ? toInt(dynValue(value, dataRange, rotRange)) : rotRange.invalidData;
    }

    getDrawable(): Drawable<P> | null {
        const dataRange = this.dataRange;
        if (!dataRange) return null;
        const value = this.dataValue();
        return (value !== null ? this.dynDrawable.get(stepValue(value, dataRange, this.stepDraw)) : null) ?? this.dynDrawable.get(0) ?? null;
    }

    getFontColorStep(): Argb | null {
        return this.getIntValue(this.dynFontColor, this.stepFontColor);
    }

    getTextSizeStep(): number | null {
        return this.getIntValue(this.dynTextSize, this.stepTextSize);
    }

    getColorStep(): Argb | null {
        return this.getIntValue(this.dynColor, this.stepColor);
    }

    getTopOffsetStep(): number | null {
        return this.getIntValue(this.dynTopOffset, this.stepTopOffset);
    }

    getLeftOffsetStep(): number | null {
        return this.getIntValue(this.dynLeftOffset, this.stepLeftOffset);
    }

    getRotationOffsetStep(): number | null {
        return this.getIntValue(this.dynRotationOffset, this.stepRotationOffset);
    }

    getTextValueStep(): string | null {
        const dataRange = this.dataRange;
        if (!dataRange) return null;
        const value = this.dataValue();
        return (value !== null ? this.dynTextValue.get(stepValue(value, dataRange, this.stepTextValue)) : undefined) ?? this.dynTextValue.get(0) ?? null;
    }

    getDynValue(range: boolean): number | null {
        if (range) {
            const dataRange = this.dataRange, dynRange = this.dynRange;
            if (dataRange && dynRange) {
                const value = this.dataValue();
                return value !== null ? dynValue(value, dataRange, dynRange) : dynRange.invalidData;
            }
        }
        return this.dataValue();
    }

    private getIntValue(dynMap: Map<number, number>, step: number): number | null {
        if (step <= 0) return null;
        const dataRange = this.dataRange;
        if (!dataRange) return null;
        const value = this.dataValue();
        return (value !== null ? dynMap.get(stepValue(value, dataRange, step)) : undefined) ?? dynMap.get(0) ?? dynMap.get(1) ?? null;
    }

    load(): this {
        const json = this.dataJson;
        const defaultRange = new DataRange(optDouble(json, "minData", this.valueMap.min), optDouble(json, "maxData", this.valueMap.max));
        this.dataRange = defaultRange;
        this.dynRange = this.parseDataRange(optObject(json, "dynValue"), defaultRange);
        this.topRange = this.parseDataRange(optObject(json, "topOffset"), defaultRange);
        this.leftRange = this.parseDataRange(optObject(json, "leftOffset"), defaultRange);
        this.rotationRange = this.parseDataRange(optObject(json, "rotationOffset"), defaultRange);
        this.getDrawableSteps(this.dynDrawable, "image", "invalidImage");
        this.getColorSteps(this.dynColor, "color", "invalidColor");
        this.getColorSteps(this.dynFontColor, "fontColor", "invalidFontColor");
        this.getIntSteps(this.dynTextSize, "textsize", "invalidTextSize");
        this.getIntSteps(this.dynLeftOffset, "leftOffset", "invalidLeftOffset");
        this.getIntSteps(this.dynTopOffset, "topOffset", "invalidTopOffset");
        this.getIntSteps(this.dynRotationOffset, "rotationOffset", "invalidRotationOffset");
        this.getStringSteps(this.dynTextValue, "textvalue", "invalidTextvalue");
        return this;
    }

    private getDrawableSteps(dynMap: Map<number, Drawable<P> | null>, key: string, invalidKey: string): void {
        if (has(this.dataJson, invalidKey)) dynMap.set(0, this.host.drawable(optString(this.dataJson, invalidKey)));
        for (let idx = 1; has(this.dataJson, `${key}${idx}`); idx++) dynMap.set(idx, this.host.drawable(optString(this.dataJson, `${key}${idx}`)));
    }

    private getColorSteps(dynMap: Map<number, Argb>, key: string, invalidKey: string): void {
        if (has(this.dataJson, invalidKey)) dynMap.set(0, this.host.getColor(optString(this.dataJson, invalidKey)));
        for (let idx = 1; has(this.dataJson, `${key}${idx}`); idx++) dynMap.set(idx, this.host.getColor(optString(this.dataJson, `${key}${idx}`)));
    }

    private getIntSteps(dynMap: Map<number, number>, key: string, invalidKey: string): void {
        if (has(this.dataJson, invalidKey)) dynMap.set(0, optInt(this.dataJson, invalidKey));
        for (let idx = 1; has(this.dataJson, `${key}${idx}`); idx++) dynMap.set(idx, optInt(this.dataJson, `${key}${idx}`, 22));
    }

    private getStringSteps(dynMap: Map<number, string>, key: string, invalidKey: string): void {
        if (has(this.dataJson, invalidKey)) dynMap.set(0, optString(this.dataJson, invalidKey));
        for (let idx = 1; has(this.dataJson, `${key}${idx}`); idx++) dynMap.set(idx, optString(this.dataJson, `${key}${idx}`));
    }

    private parseDataRange(json: JsonObject | null, defaultData: DataRange): DataRange {
        return json
            ? new DataRange(optDouble(json, "minValue", defaultData.minData), optDouble(json, "maxValue", defaultData.maxData), optInt(json, "invalidValue", defaultData.invalidData))
            : defaultData;
    }
}
