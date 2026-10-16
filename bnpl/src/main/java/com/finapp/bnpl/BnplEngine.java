package com.finapp.bnpl;

import java.util.Objects;
import java.util.Optional;

/**
 * The versions of bnpl's own engines (`P12-TSK-002`; ADR-0102 decision 2, ADR-0105, {@code PHASE_12_PLAN.md} sections
 * 12.2 and 12.6) - beside {@code creditmath}'s {@code EngineVersion}, never a member of it: the engines bnpl shares with
 * lending ({@code SCHEDULE_ENGINE_V1} for pay-in-N's schedule, {@code ACCRUAL_ENGINE_V1}, {@code ALLOCATION_ENGINE_V1})
 * keep their identities there, and what is bnpl's alone is named here.
 *
 * <p><strong>A member is a version, and a version is never edited</strong> - {@code EngineVersion}'s discipline
 * ({@code INV-LND-05}, {@code INV-LND-09}): every BNPL agreement pins the version of each engine its figures come from,
 * and replay re-derives them under exactly that version; a semantic change is a new member beside the old, which stays
 * for as long as an agreement pins it. <strong>Only the engines the plan names</strong>, each at version 1: pay-in-4's
 * schedule ({@code BNPL_SCHEDULE_ENGINE_V1}, `P12-TSK-008` - four instalments every 14 days, the remainder first) and the
 * refund's latest-first allocation ({@code REFUND_ALLOCATION_ENGINE_V1}, `P12-TSK-009`); no speculative member.
 *
 * <p>Each member's name is its engine and number, and its names never collide with {@code EngineVersion}'s
 * ({@code BnplVocabulariesTest}), so a stored engine name is one engine's - checked as the class loads with the rest of
 * the declaration: every engine holds version 1, none holds a number twice.
 */
public enum BnplEngine {

    /** Pay-in-4's schedule, version 1 (ADR-0102 decision 2): {@code n = 4}, every 14 days, {@code floor(P / 4)}, the remainder first. */
    BNPL_SCHEDULE_ENGINE_V1(Kind.BNPL_SCHEDULE, 1),

    /** A refund's allocation over the instalments, version 1 (ADR-0105): latest first, the excess to the customer. */
    REFUND_ALLOCATION_ENGINE_V1(Kind.REFUND_ALLOCATION, 1);

    /** The computations bnpl's own engines perform - each versioned by a {@link BnplEngine} member. */
    public enum Kind {

        /** Pay-in-4's BNPL Schedule. */
        BNPL_SCHEDULE,

        /** The split of a merchant's refund over the BNPL Instalments, latest first, and the excess. */
        REFUND_ALLOCATION
    }

    // The declaration checked as the class loads - by iteration over values(), holding no static collection.
    static {
        for (BnplEngine version : values()) {
            if (!version.name().equals(version.kind.name() + "_ENGINE_V" + version.number)) {
                throw new IllegalStateException(version.name() + ": a member is named for its engine and number");
            }
            for (BnplEngine other : values()) {
                if (other != version && other.kind == version.kind && other.number == version.number) {
                    throw new IllegalStateException(version.name() + ": an engine holds each number once");
                }
            }
        }
        for (Kind kind : Kind.values()) {
            if (of(kind, 1).isEmpty()) {
                throw new IllegalStateException(kind + ": every engine holds version 1, for replay");
            }
        }
    }

    private final Kind kind;
    private final int number;

    BnplEngine(Kind kind, int number) {
        this.kind = Objects.requireNonNull(kind, "kind");
        if (number < 1) {
            throw new IllegalArgumentException("an engine version is numbered from 1");
        }
        this.number = number;
    }

    /** The engine this is a version of. */
    public Kind kind() {
        return kind;
    }

    /** The version number, from 1 - what an agreement records. */
    public int number() {
        return number;
    }

    /** The version a new agreement pins for {@code kind}: the highest this build holds. */
    public static BnplEngine current(Kind kind) {
        Objects.requireNonNull(kind, "kind");
        BnplEngine current = null;
        for (BnplEngine version : values()) {
            if (version.kind == kind && (current == null || version.number > current.number)) {
                current = version;
            }
        }
        return Objects.requireNonNull(current, "every engine holds version 1");
    }

    /**
     * The version {@code number} of {@code kind}, or empty when this build does not hold it - for the caller to refuse,
     * never to fall back to another version.
     */
    public static Optional<BnplEngine> of(Kind kind, int number) {
        Objects.requireNonNull(kind, "kind");
        for (BnplEngine version : values()) {
            if (version.kind == kind && version.number == number) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
