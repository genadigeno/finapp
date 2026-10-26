package com.finapp.risk;

import java.time.Instant;
import java.util.Objects;

/**
 * The window an alert speaks for (`P13-TSK-015`; {@code risk V010}'s {@code risk_alert_window_start_is_aligned};
 * {@code INV-RSK-06}) - a {@link WindowKind} and the start of its bucket, the third member of the alert's identity
 * (subject, rule, window start).
 *
 * <p><strong>A bucket, never an instant.</strong> Two instances stamping one detection with their own clocks would
 * never meet on the unique, so the start is a tumbling-window boundary ({@link WindowStarts}: the velocity windows'
 * lengths and anchor), held aligned by the constructor here and by the {@code CHECK} for every writer.
 *
 * <p><strong>From a database instant that is the evidence's own</strong> - so a replayed event or a reprocessed history
 * recomputes the same start rather than "now"'s bucket: {@link #of(WindowKind, WindowCut)} in T-eval (the database
 * instant read after the caller's locks), {@link #of(VelocityWindowKey)} for a velocity window's own start, and - for
 * risk's own raisers over a born-once row's stored instant (a monitored transaction's or a signal's {@code recorded_at},
 * or, for the account-takeover alert, the anchor signal's {@code observed_at}: `P13-TSK-013`, {@link AccountTakeover})
 * - the package's {@link #containing(WindowKind, Instant)}; and for a default's alert (`P13-TSK-025`,
 * {@link CollectionsCases}) the {@code D1} window of the business date the born-once signal stores - the producer's own
 * date of the default, so a replay, or the same default re-delivered under any id, recomputes the same start. No factory
 * reads a clock.
 */
public final class AlertWindow {

    private final WindowKind kind;
    private final Instant start;

    private AlertWindow(WindowKind kind, Instant start) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.start = Objects.requireNonNull(start, "start");
        if (!WindowStarts.aligned(kind, start)) {
            throw new IllegalArgumentException("an alert's window starts on a " + kind + " boundary");
        }
    }

    /** The {@code kind} window T-eval's cut puts the judged movement in - the database instant after the locks. */
    public static AlertWindow of(WindowKind kind, WindowCut cut) {
        Objects.requireNonNull(cut, "cut");
        return containing(kind, cut.at());
    }

    /** A velocity window's own bucket - the window a divergence or a velocity rule speaks for. */
    public static AlertWindow of(VelocityWindowKey window) {
        Objects.requireNonNull(window, "window");
        return new AlertWindow(window.windowKind(), window.windowStart());
    }

    /**
     * The {@code kind} window containing {@code databaseInstant} - an instant a born-once row of the evidence holds as
     * its own, written once and read identically by every reader (the database's stamp, or a signal's stored
     * {@code observed_at}; never an instance's clock, never a request's timestamp): risk's own raisers only.
     */
    static AlertWindow containing(WindowKind kind, Instant databaseInstant) {
        Objects.requireNonNull(kind, "kind");
        return new AlertWindow(kind, WindowStarts.start(kind, databaseInstant));
    }

    /** A stored window, as read back - aligned or refused. */
    static AlertWindow stored(WindowKind kind, Instant start) {
        return new AlertWindow(kind, start);
    }

    public WindowKind kind() {
        return kind;
    }

    public Instant start() {
        return start;
    }

    /** The first instant after the window. */
    public Instant end() {
        return WindowStarts.end(kind, start);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AlertWindow window && window.kind == kind && window.start.equals(start);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, start);
    }

    @Override
    public String toString() {
        return kind + "@" + start;
    }
}
