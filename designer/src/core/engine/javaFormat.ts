// `String.format(locale, format, value)` of Java for one number, as the watch uses it for the text of
// a view with `dynValue` (for example `"%.0f%%"` or `"%+.1f"`).
//
// Only what such a text can use: `%f`, `%e`, `%s`, `%b`, `%%`, `%n`, with flags, width and precision.
// A format Java would refuse (like `%d` for a decimal number) throws [JavaFormatError]; the watch then
// falls back to `%.0f`, and so does the caller here.

export class JavaFormatError extends Error {
}

const SPEC = /%(?:(\d+)\$)?([-#+ 0,(]*)(\d+)?(?:\.(\d+))?([a-zA-Z%])/g;

/** The decimal and grouping signs of [locale]. */
function signsOf(locale: string): { decimal: string; group: string } {
    try {
        const parts = new Intl.NumberFormat(locale, { useGrouping: true }).formatToParts(1234567.5);
        return {
            decimal: parts.find(p => p.type === "decimal")?.value ?? ".",
            group: parts.find(p => p.type === "group")?.value ?? ","
        };
    } catch {
        return { decimal: ".", group: "," };
    }
}

/** The shortest decimal text of a positive number, without an exponent. */
function plainDecimal(value: number): string {
    const text = String(value);
    const match = /^(\d+)(?:\.(\d+))?e([+-]\d+)$/.exec(text);
    if (!match) return text;
    const digits = match[1]! + (match[2] ?? "");
    const point = match[1]!.length + Number(match[3]);
    if (point <= 0) return "0." + "0".repeat(-point) + digits;
    if (point >= digits.length) return digits + "0".repeat(point - digits.length);
    return digits.substring(0, point) + "." + digits.substring(point);
}

/** [plain] (a positive decimal text) rounded half up to [precision] digits after the point, as Java's formatter does. */
function roundHalfUp(plain: string, precision: number): string {
    const [whole = "0", fraction = ""] = plain.split(".");
    if (fraction.length <= precision) return precision === 0 ? whole : whole + "." + fraction.padEnd(precision, "0");
    const digits = (whole + fraction.substring(0, precision)).split("").map(Number);
    if (Number(fraction[precision]) >= 5) {
        let i = digits.length - 1;
        while (i >= 0 && digits[i] === 9) digits[i--] = 0;
        if (i >= 0) digits[i]!++;
        else digits.unshift(1);
    }
    const text = digits.join("");
    const split = text.length - precision;
    return precision === 0 ? text : text.substring(0, split) + "." + text.substring(split);
}

/** Java's `Double.toString`. */
export function javaDoubleToString(value: number): string {
    if (Number.isNaN(value)) return "NaN";
    if (!Number.isFinite(value)) return value > 0 ? "Infinity" : "-Infinity";
    const abs = Math.abs(value);
    const sign = value < 0 || Object.is(value, -0) ? "-" : "";
    if (abs === 0) return sign + "0.0";
    if (abs >= 1e-3 && abs < 1e7) {
        const plain = plainDecimal(abs);
        return sign + (plain.includes(".") ? plain : plain + ".0");
    }
    const [mantissa = "0", exponent = "0"] = abs.toExponential().split("e");
    return sign + (mantissa.includes(".") ? mantissa : mantissa + ".0") + "E" + Number(exponent);
}

function pad(text: string, width: number | undefined, left: boolean, zero: boolean, signLength: number): string {
    if (width === undefined || text.length >= width) return text;
    if (left) return text.padEnd(width, " ");
    if (zero) return text.substring(0, signLength) + text.substring(signLength).padStart(width - signLength, "0");
    return text.padStart(width, " ");
}

/** `String.format(locale, format, value)` for the one decimal number [value]. */
export function javaFormat(locale: string, format: string, value: number): string {
    let nextIndex = 0;
    return format.replace(SPEC, (_all, indexText: string | undefined, flags: string, widthText: string | undefined, precisionText: string | undefined, conversion: string) => {
        if (conversion === "%") return pad("%", widthText === undefined ? undefined : Number(widthText), flags.includes("-"), false, 0);
        if (conversion === "n") return "\n";
        // Each conversion takes the next value; there is only one
        const index = indexText !== undefined ? Number(indexText) - 1 : nextIndex++;
        if (index !== 0) throw new JavaFormatError(`Format specifier '%${conversion}' has no value`);
        const width = widthText === undefined ? undefined : Number(widthText);
        const left = flags.includes("-");
        const upper = conversion !== conversion.toLowerCase();
        const done = (text: string, signLength = 0, zero = false) => pad(upper ? text.toUpperCase() : text, width, left, zero, signLength);
        switch (conversion.toLowerCase()) {
            case "f":
            case "e": {
                if (Number.isNaN(value)) return done("NaN");
                const negative = value < 0 || Object.is(value, -0);
                let sign = negative ? "-" : flags.includes("+") ? "+" : flags.includes(" ") ? " " : "";
                if (!Number.isFinite(value)) return done(sign + "Infinity", sign.length);
                const precision = precisionText === undefined ? 6 : Number(precisionText);
                const signs = signsOf(locale);
                let body: string;
                if (conversion.toLowerCase() === "f") {
                    const rounded = roundHalfUp(plainDecimal(Math.abs(value)), precision);
                    let [whole = "0", fraction] = rounded.split(".");
                    if (flags.includes(",")) whole = whole.replace(/\B(?=(\d{3})+(?!\d))/g, signs.group);
                    body = fraction === undefined ? whole : whole + signs.decimal + fraction;
                } else {
                    const [mantissa = "0", exponent = "0"] = Math.abs(value).toExponential(precision).split("e");
                    const exp = Number(exponent);
                    body = mantissa.replace(".", signs.decimal) + "e" + (exp < 0 ? "-" : "+") + String(Math.abs(exp)).padStart(2, "0");
                }
                if (negative && flags.includes("(")) {
                    sign = "(";
                    body += ")";
                }
                return done(sign + body, sign.length, flags.includes("0"));
            }
            case "s": {
                let text = javaDoubleToString(value);
                if (precisionText !== undefined) text = text.substring(0, Number(precisionText));
                return done(text);
            }
            case "b":
                return done("true");
            default:
                // %d, %x, %c ... : Java refuses a decimal number for these
                throw new JavaFormatError(`Format '%${conversion}' does not take a decimal number`);
        }
    });
}
