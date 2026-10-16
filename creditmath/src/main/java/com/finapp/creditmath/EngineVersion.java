package com.finapp.lending;

import java.util.Objects;
import java.util.Optional;

/**
 * The engine versions this build holds - the backlog's {@code EngineVersions} (`P11-TSK-002`;
 * {@code PHASE_11_PLAN.md} section 12.1, ADR-0092).
 *
 * <p><strong>A member is a version, and a version is never edited.</strong> Every agreement pins the version of each
 * of the four {@link LendingEngine}s its figures are computed by, and replay re-derives them under exactly that
 * version. A semantic change to an engine - a rounding rule, a day-count, an allocation order the code applies - is
 * therefore a NEW member beside the old one, which stays in the code for as long as an agreement pins it
 * ({@code INV-LND-05}, {@code INV-LND-09}); it is never a change to what an existing member means.
 *
 * <p>Each member's name is its engine and number ({@code ACCRUAL_ENGINE_V1}), checked as the class loads together with
 * the rest of the declaration - every engine holds version 1, no engine holds a number twice - so a member that
 * disagreed with itself fails every use of the type. Immutable, and the same on every instance: a build's engines are
 * its code.
 */
public enum EngineVersion {

    /** The amortisation schedule, version 1 (ADR-0093): the annuity rounded {@code UP} to the minor unit. */
    SCHEDULE_ENGINE_V1(LendingEngine.SCHEDULE, 1),

    /** Daily accrual, version 1 (ADR-0094): {@code ACT_365F}, simple, cumulative {@code HALF_EVEN} rounding. */
    ACCRUAL_ENGINE_V1(LendingEngine.ACCRUAL, 1),

    /** Allocation, version 1 (ADR-0095): oldest due first; fees, then interest, then principal. */
    ALLOCATION_ENGINE_V1(LendingEngine.ALLOCATION, 1),

    /** The credit line's statement, version 1 (ADR-0100): the minimum-payment rule of A24. */
    STATEMENT_ENGINE_V1(LendingEngine.STATEMENT, 1);

    // The declaration checked as the class loads - by iteration over values(), deliberately holding no
    // static collection: a build's engines are its code, and there is no state here to share or cache.
    static {
        for (EngineVersion version : values()) {
            if (!version.name().equals(version.engine.name() + "_ENGINE_V" + version.number)) {
                throw new IllegalStateException(version.name() + ": a member is named for its engine and number");
            }
            for (EngineVersion other : values()) {
                if (other != version && other.engine == version.engine && other.number == version.number) {
                    throw new IllegalStateException(version.name() + ": an engine holds each number once");
                }
            }
        }
        for (LendingEngine engine : LendingEngine.values()) {
            if (of(engine, 1).isEmpty()) {
                throw new IllegalStateException(engine + ": every engine holds version 1, for replay");
            }
        }
    }

    private final LendingEngine engine;
    private final int number;

    EngineVersion(LendingEngine engine, int number) {
        this.engine = Objects.requireNonNull(engine, "engine");
        if (number < 1) {
            throw new IllegalArgumentException("an engine version is numbered from 1");
        }
        this.number = number;
    }

    /** The engine this is a version of. */
    public LendingEngine engine() {
        return engine;
    }

    /** The version number, from 1 - what an agreement records. */
    public int number() {
        return number;
    }

    /** The version a new agreement pins for {@code engine}: the highest this build holds. */
    public static EngineVersion current(LendingEngine engine) {
        Objects.requireNonNull(engine, "engine");
        EngineVersion current = null;
        for (EngineVersion version : values()) {
            if (version.engine == engine && (current == null || version.number > current.number)) {
                current = version;
            }
        }
        return Objects.requireNonNull(current, "every engine holds version 1");
    }

    /**
     * The version {@code number} of {@code engine}, or empty when this build does not hold it - for the caller to
     * refuse, never to fall back to another version.
     */
    public static Optional<EngineVersion> of(LendingEngine engine, int number) {
        Objects.requireNonNull(engine, "engine");
        for (EngineVersion version : values()) {
            if (version.engine == engine && version.number == number) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
