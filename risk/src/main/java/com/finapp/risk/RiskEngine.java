package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;

/**
 * The versions of risk's own engine (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.2; ADR-0109) - bnpl's
 * {@code BnplEngine} discipline: a member is a version, and a version is never edited. Every assessment pins the engine
 * version that produced it, and replay re-derives the hits, score and decision under exactly that version
 * ({@code INV-RSK-01}); a semantic change is a new member beside the old, which stays for as long as an assessment pins
 * it. One engine at version 1 ({@code RISK_ENGINE_V1}, the plan names it; `-003` builds it); no speculative member.
 *
 * <p>Each member's name is its engine and number, checked as the class loads with the rest of the declaration: every
 * engine holds version 1, none holds a number twice.
 */
public enum RiskEngine {

    /** The rule engine, version 1: the kinds of {@link RuleKind} evaluated in the version's order, deterministically. */
    RISK_ENGINE_V1(Kind.RISK, 1);

    /** The computations risk's own engines perform - each versioned by a {@link RiskEngine} member. */
    public enum Kind {
        /** The evaluation of a policy version's rules over sealed inputs: hits, score, decision. */
        RISK
    }

    static {
        for (RiskEngine version : values()) {
            if (!version.name().equals(version.kind.name() + "_ENGINE_V" + version.number)) {
                throw new IllegalStateException(version.name() + ": a member is named for its engine and number");
            }
            for (RiskEngine other : values()) {
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

    RiskEngine(Kind kind, int number) {
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

    /** The version number, from 1 - what an assessment records. */
    public int number() {
        return number;
    }

    /** The version a new policy version is written for: the highest this build holds for {@code kind}. */
    public static RiskEngine current(Kind kind) {
        Objects.requireNonNull(kind, "kind");
        RiskEngine current = null;
        for (RiskEngine version : values()) {
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
    public static Optional<RiskEngine> of(Kind kind, int number) {
        Objects.requireNonNull(kind, "kind");
        for (RiskEngine version : values()) {
            if (version.kind == kind && version.number == number) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
