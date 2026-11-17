// Port of wear/src/main/kotlin/app/aaps/wear/watchfaces/utils/LogTimeScale.kt (AAPS branch local/personal, commit 77586f50bd).
// Keep the names and the order of things like the Kotlin file, so a change there is easy to carry over.

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;

/**
 * The time scale of the glucose graph in log mode (see LogTimeGraph): the last minutes are wide and
 * detailed, and the further back a time is, the more it is squeezed, so one graph shows a whole day.
 *
 * A time `t` is at x = ln(1 + |t - now| / [DETAIL_MS]) * [UNIT], negative in the past. With a
 * [DETAIL_MS] of 30 minutes the last half hour takes 18 % of the past part, the last hour 28 %, 3 hours
 * half, 6 hours two thirds and 12 hours 82 %. Predictions use the same scale to the right of now, for
 * at most [FUTURE_MS].
 */
export class LogTimeScale {

    /** The time of the x 0: the time the graph is made, rounded down to the minute. */
    readonly now: number;

    /**
     * @param time the time the graph is made. The x 0 ([now]) is its whole minute, like the plain scale of the
     * graph, so the graph does not change between two minutes.
     */
    constructor(time: number) {
        this.now = time - (((time % MINUTE_MS) + MINUTE_MS) % MINUTE_MS);
    }

    /** The oldest time the graph shows. */
    get start(): number {
        return this.now - LogTimeScale.PAST_MS;
    }

    /**
     * The newest time the graph shows: up to [predictionsUntil], the last prediction (null without
     * predictions), but at least [MIN_FUTURE_MS] and at most [FUTURE_MS] after now.
     */
    end(predictionsUntil: number | null): number {
        return Math.min(Math.max(predictionsUntil ?? this.now, this.now + LogTimeScale.MIN_FUTURE_MS), this.now + LogTimeScale.FUTURE_MS);
    }

    /** The x of [time]. */
    x(time: number): number {
        return LogTimeScale.xOfOffset(time - this.now);
    }

    /** True when the graph shows [time]. */
    shows(time: number): boolean {
        return time >= this.start && time <= this.now + LogTimeScale.FUTURE_MS;
    }

    /** How far back the graph goes. */
    static readonly PAST_MS = 24 * HOUR_MS;

    /** How far ahead predictions go at most. */
    static readonly FUTURE_MS = HOUR_MS;

    /** A small room right of now, also without predictions, so the newest point is not on the edge. */
    static readonly MIN_FUTURE_MS = 5 * MINUTE_MS;

    /** The time around now that is shown nearly as wide as a plain scale: the smaller, the stronger the squeeze. */
    static readonly DETAIL_MS = 30 * MINUTE_MS;

    /** Near now one x unit is about one minute. */
    static readonly UNIT = LogTimeScale.DETAIL_MS / MINUTE_MS;

    /** The ages with a line and a label on the time axis. */
    static readonly LABEL_AGES_MS: readonly number[] = [HOUR_MS, 3 * HOUR_MS, 6 * HOUR_MS, 12 * HOUR_MS];

    /** The x of a time [offsetMs] from now (negative in the past). A 32 bit float, like in Kotlin. */
    static xOfOffset(offsetMs: number): number {
        return Math.fround(Math.sign(offsetMs) * Math.log(1 + Math.abs(offsetMs) / LogTimeScale.DETAIL_MS) * LogTimeScale.UNIT);
    }
}
