package com.finapp.risk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One movement cut into its windows (`P13-TSK-006`; {@code PHASE_13_PLAN.md} section 12.4; {@code INV-RSK-03},
 * {@code INV-CON-03}): the {@link VelocityMovement} and the <strong>database</strong> instant read on the caller's
 * connection after every lock it took - the caller's money locks, the policy pin and the subjects' serialisation (R2a).
 *
 * <p><strong>Only {@link VelocityWindows#cut} makes one.</strong> The constructor is package-private, so no caller -
 * `-004`'s T-eval included - can build a cut from an instance's clock, a request's timestamp or any instant other than
 * the one the database answered after the locks; and because the subjects' locks are taken by the same call, holding a
 * cut means holding the serialisation its reads and increments rely on. One cut per T-eval: the read and the increment
 * of one movement land in the same windows, even when the evaluation straddles a boundary.
 */
public final class WindowCut {

    private final VelocityMovement movement;
    private final Instant at;

    /** Whether this cut's movement has been counted - one cut, one count; a value of this one T-eval, never shared. */
    private boolean counted;

    WindowCut(VelocityMovement movement, Instant at) {
        this.movement = Objects.requireNonNull(movement, "movement");
        this.at = Objects.requireNonNull(at, "at");
    }

    /** Records that the movement is being counted - refused the second time: a movement counts once per judgement. */
    void countOnce() {
        if (counted) {
            throw new IllegalStateException("a cut's movement is counted once");
        }
        counted = true;
    }

    /** The movement cut. */
    public VelocityMovement movement() {
        return movement;
    }

    /** The database instant the windows are cut at. */
    public Instant at() {
        return at;
    }

    /** The window of {@code kind} this cut puts the movement's {@code dimension} subject in - none without a subject. */
    public Optional<VelocityWindowKey> key(VelocityDimension dimension, WindowKind kind) {
        Objects.requireNonNull(kind, "kind");
        return movement.subject(dimension)
                .map(subject -> new VelocityWindowKey(dimension, subject, kind, WindowStarts.start(kind, at)));
    }

    /** Every window the movement counts in: each dimension it carries, each window kind - sorted, R2's order. */
    public List<VelocityWindowKey> keys() {
        List<VelocityWindowKey> keys = new ArrayList<>();
        for (Map.Entry<VelocityDimension, String> subject : movement.subjects().entrySet()) {
            for (WindowKind kind : WindowKind.values()) {
                keys.add(new VelocityWindowKey(subject.getKey(), subject.getValue(), kind, WindowStarts.start(kind, at)));
            }
        }
        keys.sort(null);
        return List.copyOf(keys);
    }

    @Override
    public String toString() {
        return "WindowCut[" + movement + " at " + at + "]";
    }
}
