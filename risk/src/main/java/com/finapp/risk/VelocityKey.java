package com.finapp.risk;

import java.util.Comparator;
import java.util.Objects;

/**
 * The key of one velocity reading: the dimension and the window (`P13-TSK-003`; {@code PHASE_13_PLAN.md} section
 * 12.4). The dimension is the {@link SubjectKind} a {@link RuleKind#VELOCITY_COUNT} or {@link RuleKind#VELOCITY_SUM}
 * rule names in its {@code dimension} parameter ({@link RuleKind.ParameterType#SUBJECT_KIND}, `-002`) - the engine
 * evaluates what the policy stores; the caller maps `-006`'s window figures onto these keys.
 *
 * <p>Ordered by declaration order of both enums, so a map of readings keyed by this is iterated the same way in any
 * JVM ({@code INV-RSK-01}).
 */
public record VelocityKey(SubjectKind dimension, WindowKind window) implements Comparable<VelocityKey> {

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
