// Reading a design json the way the watch reads it.
//
// The watch uses Android's `org.json.JSONObject`. Its `opt...` calls never fail: a missing key or a
// value of the wrong type gives a default, and some types are turned into others (the text "12" is
// the number 12). A design that works here must work on the watch, so these helpers follow the same
// rules. Use them, not plain property reads, for everything that comes from a design.

/** Any value of a parsed json text. */
export type JsonValue = string | number | boolean | null | JsonObject | JsonValue[];

/** A json object, as `JSON.parse` gives it. Keys keep the order of the text. */
export interface JsonObject {
    [key: string]: JsonValue;
}

/** True when [value] is a json object (not null, not an array). */
export function isObject(value: unknown): value is JsonObject {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** `JSONObject.has`: true when the key is there, also when its value is null. */
export function has(json: JsonObject | null | undefined, key: string): boolean {
    return !!json && Object.prototype.hasOwnProperty.call(json, key);
}

/** `JSONObject.isNull`: true when the key is missing or its value is null. */
export function isNull(json: JsonObject | null | undefined, key: string): boolean {
    return !has(json, key) || json![key] === null;
}

/** `JSONObject.opt`: the value, or undefined when the key is missing. */
export function opt(json: JsonObject | null | undefined, key: string): JsonValue | undefined {
    return has(json, key) ? json![key] : undefined;
}

/** `JSONObject.optJSONObject`: the object, or null when the key is missing or not an object. */
export function optObject(json: JsonObject | null | undefined, key: string): JsonObject | null {
    const value = opt(json, key);
    return isObject(value) ? value : null;
}

/** `JSONObject.optJSONArray`: the array, or null when the key is missing or not an array. */
export function optArray(json: JsonObject | null | undefined, key: string): JsonValue[] | null {
    const value = opt(json, key);
    return Array.isArray(value) ? value : null;
}

/**
 * `JSONObject.optString`: a text as it is, a number or boolean as its text, and [fallback] when the
 * key is missing. An explicit null gives the text "null", as on the watch.
 */
export function optString(json: JsonObject | null | undefined, key: string, fallback = ""): string {
    const value = opt(json, key);
    if (value === undefined) return fallback;
    return valueToString(value);
}

/** The text org.json makes of a value that is not a text. */
export function valueToString(value: JsonValue): string {
    if (typeof value === "string") return value;
    if (value === null) return "null";
    if (typeof value === "object") return JSON.stringify(value);
    return String(value);
}

/** `JSONObject.optDouble`: a number, or a text that is a number; else [fallback] (NaN by default). */
export function optDouble(json: JsonObject | null | undefined, key: string, fallback = NaN): number {
    const value = toDouble(opt(json, key));
    return value === null ? fallback : value;
}

/** `JSONObject.optInt`: like [optDouble], cut to a whole number (towards zero); else [fallback] (0 by default). */
export function optInt(json: JsonObject | null | undefined, key: string, fallback = 0): number {
    const value = toDouble(opt(json, key));
    return value === null || Number.isNaN(value) ? fallback : Math.trunc(value);
}

/** `JSONObject.optLong`: the same as [optInt] here, a JS number holds both. */
export function optLong(json: JsonObject | null | undefined, key: string, fallback = 0): number {
    return optInt(json, key, fallback);
}

/** `JSONObject.optBoolean`: true or false, or the texts "true" and "false" (any case); else [fallback]. */
export function optBoolean(json: JsonObject | null | undefined, key: string, fallback = false): boolean {
    const value = opt(json, key);
    if (typeof value === "boolean") return value;
    if (typeof value === "string") {
        if (value.toLowerCase() === "true") return true;
        if (value.toLowerCase() === "false") return false;
    }
    return fallback;
}

/** The keys of [json] in the order of the text; none for null. */
export function keysOf(json: JsonObject | null | undefined): string[] {
    return json ? Object.keys(json) : [];
}

/** A deep copy of a json value. */
export function deepCopy<T extends JsonValue>(value: T): T {
    return value === undefined ? value : (JSON.parse(JSON.stringify(value)) as T);
}

function toDouble(value: JsonValue | undefined): number | null {
    if (typeof value === "number") return value;
    if (typeof value === "string") {
        // Java's Double.parseDouble: no empty text, no text that is only partly a number
        const text = value.trim();
        if (text === "" || !/^[+-]?(\d+\.?\d*([eE][+-]?\d+)?|\.\d+([eE][+-]?\d+)?|NaN|Infinity)[dDfF]?$/.test(text)) return null;
        return Number(text.replace(/[dDfF]$/, ""));
    }
    return null;
}
