// The few things the designer needs from the place it runs in (a browser or Node).
// Everything in src/core draws with the standard Canvas 2D API and gets it through this file,
// so the same code runs in the web page and in the command line tool.

/** The drawing context: the standard Canvas 2D API. */
export type Ctx = CanvasRenderingContext2D;

/** Something to draw on. */
export interface Surface {
    width: number;
    height: number;
    getContext(type: "2d"): Ctx;
}

/** A decoded picture (png, jpg or svg) that `ctx.drawImage` can draw. Its size is its own size in pixels. */
export interface Picture {
    readonly width: number;
    readonly height: number;
    /** The object to give to `ctx.drawImage`. */
    readonly source: CanvasImageSource;
}

/** The file types a design zip can hold, as AAPS names them (`ResFormat`). */
export type ResFormat = "svg" | "jpg" | "png" | "ttf" | "otf";

/** The picture types among [ResFormat]. */
export type PictureFormat = "svg" | "jpg" | "png";

export interface Platform {
    /** A new, clear surface. */
    createSurface(width: number, height: number): Surface;

    /**
     * Decodes a picture. For an svg, [size] is the size to draw it at (the svg is made sharp for it);
     * without it the svg has its own document size.
     */
    decodePicture(bytes: Uint8Array, format: PictureFormat, size?: { width: number; height: number }): Promise<Picture>;

    /** The surface as a png, or as a jpg of [quality] (0..1). */
    encodeSurface(surface: Surface, format: "png" | "jpg", quality?: number): Promise<Uint8Array>;

    /** Makes the font in [bytes] known under the name [family], for `ctx.font`. */
    registerFont(family: string, bytes: Uint8Array): Promise<void>;

    /** Reads a file that ships with the designer (a default picture or font), by its path under `assets/`. */
    readAsset(path: string): Promise<Uint8Array>;
}

/** A [Surface] as a [Picture], so one surface can be drawn onto another. */
export function pictureOf(surface: Surface): Picture {
    return { width: surface.width, height: surface.height, source: surface as unknown as CanvasImageSource };
}
