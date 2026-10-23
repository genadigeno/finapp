package com.finapp.risk;

import java.util.Comparator;
import java.util.Objects;

/**
 * The key of one velocity reading: the dimension and the window (`P13-TSK-003`; {@code PHASE_13_PLAN.md} section
 * 12.4). The dimension is the {@link VelocityDimension} a {@link RuleKind#VELOCITY_COUNT} or {@link RuleKind#VELOCITY_SUM}
 * rule names in its {@code dimension} parameter ({@link RuleKind.ParameterType#VELOCITY_DIMENSION}) - the engine
 * evaluates what the policy stores. The subject is the judged movement's, so it is not part of the key: `-006`'s
 * {@link VelocityWindows#read} answers one reading per key for the movement it cut.
 *
 * <p><em>Re-keyed by `P13-TSK-006`</em> (the coordinator's decision at wave C): the dimension was a {@link SubjectKind}
 * until then, which could not keep a transfer's source and destination accounts apart. The canonical format stays 1 -
 * nothing had been sealed.
 *
 * <p>Ordered by declaration order of both enums, so a map of readings keyed by this is iterated the same way in any
 * JVM ({@code INV-RSK-01}).
 */
public record VelocityKey(VelocityDimension dimension, WindowKind window) implements Comparable<VelocityKey> {

    private static final Comparator<VelocityKey> ORDER =
            Comparator.comparing(VelocityKey::dimension).thenComparing(VelocityKey::window);

    public VelocityKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(window, "window");
    }

    @Override
    public int compareTo(VelocityKey other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return dimension + ":" + window;
    }
}
