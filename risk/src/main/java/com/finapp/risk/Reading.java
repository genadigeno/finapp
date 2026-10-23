package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * One sealed reading the engine judges (`P13-TSK-003`; {@code PHASE_13_PLAN.md} section 12.2; ADR-0109 decision 3) -
 * {@code PRESENT(value)} or {@code ABSENT}. A source may be absent at evaluation time (no session for the system
 * actor, no signal yet for a reading `-005` has no event for, a window `-006` has not yet wired): a rule over an
 * {@code ABSENT} reading never fires and its hit record says so ({@link RuleOutcome#ABSENT}), which is a different
 * explanation from "read and found nothing" - that one is a present value such as {@link FirstSeen#never()}.
 *
 * <p>A value, never a lookup: the engine reads no row and no clock. The caller (`-004`) seals every reading before
 * the engine runs, and the canonical form ({@link CanonicalEvaluation}) writes an absence explicitly.
 */
public sealed interface Reading<T> {

    /** A reading the source answered. */
    record Present<T>(T held) implements Reading<T> {
        public Present {
            Objects.requireNonNull(held, "held");
        }
    }

    /** A reading no source could answer - never a default. */
    record Absent<T>() implements Reading<T> {}

    static <T> Reading<T> of(T value) {
        return new Present<>(value);
    }

    static <T> Reading<T> absent() {
        return new Absent<>();
    }

    default boolean isPresent() {
        return this instanceof Present;
    }

    /** The value when present. */
    default Optional<T> value() {
        return this instanceof Present<T> present ? Optional.of(present.held()) : Optional.empty();
    }

    /** This reading with its value transformed - an absence stays an absence. */
    default <R> Reading<R> map(Function<? super T, ? extends R> transform) {
        Objects.requireNonNull(transform, "transform");
        return this instanceof Present<T> present ? new Present<>(transform.apply(present.held())) : new Absent<>();
    }
}
