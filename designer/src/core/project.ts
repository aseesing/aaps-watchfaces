// A watch face project: the files of one design, as they are in its zip, plus the settings of this tool.
//
// The zip of a design is flat: `CustomWatchface.json`, a preview picture `CustomWatchface.png|jpg|svg`,
// and any number of pictures (png, jpg, svg) and fonts (ttf, otf). The watch knows a file by its name
// without the extension (its "key"). A project keeps exactly those files, so a zip can be opened,
// changed and built again without losing anything.

import { strFromU8, strToU8, unzipSync, zipSync } from "fflate";
import type { JsonObject } from "./json.ts";
import { isObject } from "./json.ts";
import { JSON_FILE, PREVIEW_NAME } from "./engine/keys.ts";
import type { ResFormat } from "./platform.ts";

/** The file of tool settings in a project folder. It never goes into the zip. */
export const DESIGNER_FILE = "designer.json";

/** One resource of a design: a picture or a font. */
export interface Resource {
    /** The name the design json uses: the file name without extension. */
    key: string;
    format: ResFormat;
    /** The file name, key + "." + extension. */
    fileName: string;
    bytes: Uint8Array;
}

/** The format of a file name by its extension, as the watch decides it (`ResFormat.fromFileName`); null for any other file. */
export function formatOfFileName(fileName: string): ResFormat | null {
    const dot = fileName.lastIndexOf(".");
    const extension = (dot < 0 ? fileName : fileName.substring(dot + 1)).toLowerCase();
    return extension === "svg" || extension === "jpg" || extension === "png" || extension === "ttf" || extension === "otf" ? extension : null;
}

/** The key of a file name: the part before the last dot. */
export function keyOfFileName(fileName: string): string {
    const dot = fileName.lastIndexOf(".");
    return dot < 0 ? fileName : fileName.substring(0, dot);
}

export const isPictureFormat = (format: ResFormat): format is "svg" | "jpg" | "png" => format === "svg" || format === "jpg" || format === "png";
export const isFontFormat = (format: ResFormat): format is "ttf" | "otf" => format === "ttf" || format === "otf";

/**
 * The files of one design. The json is kept as text (people and AI tools edit the text); [design] gives
 * it parsed, or null with [jsonError] when the text is not valid json.
 */
export class Project {

    /** The text of `CustomWatchface.json`. */
    private text: string;
    private parsed: JsonObject | null = null;
    private parseError: string | null = null;
    private parsedFor: string | null = null;

    /** Every other file, by file name. Files the watch does not read (a licence text) are kept too. */
    readonly files = new Map<string, Uint8Array>();

    /** The text of `designer.json`: settings of this tool. Empty when there is none. */
    designerText = "";

    /** Goes up by one on every change, so a kept render can tell it is old. */
    revision = 0;

    constructor(jsonText = "{}") {
        this.text = jsonText;
    }

    get jsonText(): string {
        return this.text;
    }

    set jsonText(value: string) {
        if (value !== this.text) {
            this.text = value;
            this.revision++;
        }
    }

    private parse(): void {
        if (this.parsedFor === this.text) return;
        this.parsedFor = this.text;
        this.parsed = null;
        this.parseError = null;
        try {
            const value: unknown = JSON.parse(this.text);
            if (isObject(value)) this.parsed = value;
            else this.parseError = "The design must be a json object: { ... }";
        } catch (e) {
            this.parseError = e instanceof Error ? e.message : String(e);
        }
    }

    /** The design as parsed json, or null when the text is not a json object. Do not change the returned object: use [setDesign]. */
    get design(): JsonObject | null {
        this.parse();
        return this.parsed;
    }

    /** Why the json text could not be read, or null when it is fine. */
    get jsonError(): string | null {
        this.parse();
        return this.parseError;
    }

    /** Replaces the json text by [design], written with 4 spaces as the watch writes it. */
    setDesign(design: JsonObject): void {
        this.jsonText = JSON.stringify(design, null, 4);
    }

    /** Adds or replaces a file. `CustomWatchface.json` and `designer.json` set the texts. */
    setFile(fileName: string, bytes: Uint8Array): void {
        if (fileName === JSON_FILE) {
            this.jsonText = strFromU8(bytes);
            return;
        }
        if (fileName === DESIGNER_FILE) {
            this.designerText = strFromU8(bytes);
            this.revision++;
            return;
        }
        this.files.set(fileName, bytes);
        this.revision++;
    }

    removeFile(fileName: string): boolean {
        const removed = this.files.delete(fileName);
        if (removed) this.revision++;
        return removed;
    }

    renameFile(from: string, to: string): boolean {
        const bytes = this.files.get(from);
        if (!bytes || this.files.has(to)) return false;
        this.files.delete(from);
        this.files.set(to, bytes);
        this.revision++;
        return true;
    }

    /**
     * The resources the watch reads, by key, the way `ZipWatchfaceFormat.loadCustomWatchface` fills its
     * map: files with a known extension, in file order, a later file with the same key replacing an
     * earlier one.
     */
    resources(): Map<string, Resource> {
        const result = new Map<string, Resource>();
        for (const [fileName, bytes] of this.files) {
            const format = formatOfFileName(fileName);
            if (!format) continue;
            const key = keyOfFileName(fileName);
            result.set(key, { key, format, fileName, bytes });
        }
        return result;
    }

    /** The file name of the preview picture (`CustomWatchface.png|jpg|svg`), or null when there is none. */
    previewFileName(): string | null {
        for (const fileName of this.files.keys()) {
            const format = formatOfFileName(fileName);
            if (format && isPictureFormat(format) && keyOfFileName(fileName) === PREVIEW_NAME) return fileName;
        }
        return null;
    }

    /** The bytes the watch gets: the json and every resource, unpacked. The phone sends this in one message. */
    payloadBytes(): number {
        let total = strToU8(this.text).length;
        for (const resource of this.resources().values()) total += resource.bytes.length;
        return total;
    }

    clone(): Project {
        const copy = new Project(this.text);
        for (const [name, bytes] of this.files) copy.files.set(name, bytes);
        copy.designerText = this.designerText;
        return copy;
    }

    /** The design zip: the json first, then every file. `designer.json` is left out. */
    toZip(): Uint8Array {
        const entries: Record<string, Uint8Array> = { [JSON_FILE]: strToU8(this.text) };
        for (const [fileName, bytes] of this.files) entries[fileName] = bytes;
        return zipSync(entries, { level: 9 });
    }

    /** A project from the bytes of a design zip. Throws when the bytes are not a zip. */
    static fromZip(zip: Uint8Array): Project {
        const entries = unzipSync(zip);
        const project = new Project("{}");
        for (const [name, bytes] of Object.entries(entries)) {
            if (name.endsWith("/")) continue;               // a folder entry
            if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue;
            project.setFile(name, bytes);
        }
        project.revision = 0;
        return project;
    }

    /** A project from loose files (a folder): file name to bytes. */
    static fromFiles(files: Iterable<[string, Uint8Array]>): Project {
        const project = new Project("{}");
        for (const [name, bytes] of files) project.setFile(name, bytes);
        project.revision = 0;
        return project;
    }

    /** All files of the project folder: the json, `designer.json` when set, and every file. */
    toFiles(): Map<string, Uint8Array> {
        const result = new Map<string, Uint8Array>();
        result.set(JSON_FILE, strToU8(this.text));
        if (this.designerText.trim() !== "") result.set(DESIGNER_FILE, strToU8(this.designerText));
        for (const [name, bytes] of this.files) result.set(name, bytes);
        return result;
    }
}
