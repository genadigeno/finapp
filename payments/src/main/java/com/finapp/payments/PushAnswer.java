package com.finapp.payments;

import java.util.Objects;
import java.util.Optional;

/**
 * A push send's total answer (`P7-TSK-006`, ADR-0062 §1): our four verdicts, with the
 * scheme's transaction reference and settlement-cycle identifier carried whenever the scheme
 * gave them — the values Phase 8 reconciles cycles against. The doctrine is the card wire's
 * ({@code INV-LIFE-03}): only a refused connection is knowledge that nothing happened;
 * everything after a send is either the scheme's explicit word or {@code INDETERMINATE}.
 */
public record PushAnswer(
        Verdict verdict,
        Optional<ProviderReference> schemeReference,
        Optional<String> settlementCycle,
        Optional<byte[]> evidence) {

    /** Our words, never the scheme's (`INV-PAY-03` per adapter). */
    public enum Verdict {
        /** The scheme accepted: FINAL — the payee's credit cannot be taken back. */
        ACCEPTED,
        /** The scheme rejected: knowledge, nothing moved. */
        REJECTED,
        /** Sent, or possibly sent, and no usable answer ({@code INV-LIFE-03}). */
        INDETERMINATE,
        /** The connection was refused before anything left: nothing happened. */
        NOTHING_SENT
    }

    /** The settlement-cycle identifier's bound: an opaque scheme token, never parsed. */
    public static final int MAX_CYCLE_LENGTH = 64;

    public PushAnswer {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        settlementCycle.ifPresent(PushAnswer::requireCycleShape);
        if (verdict == Verdict.ACCEPTED && schemeReference.isEmpty()) {
            throw new IllegalArgumentException(
                    "an ACCEPTED push carries the scheme's transaction reference - an"
                            + " acceptance reconciliation cannot key is not one this port"
                            + " may report (the approved-without-reference rule)");
        }
    }

    static void requireCycleShape(String cycle) {
        if (cycle.isBlank() || cycle.length() > MAX_CYCLE_LENGTH) {
            throw new IllegalArgumentException(
                    "a settlement-cycle identifier is 1.." + MAX_CYCLE_LENGTH
                            + " characters");
        }
    }

    public static PushAnswer accepted(
            ProviderReference schemeReference, Optional<String> settlementCycle,
            byte[] evidence) {
        return new PushAnswer(
                Verdict.ACCEPTED,
                Optional.of(schemeReference),
                settlementCycle,
                Optional.of(evidence));
    }

    public static PushAnswer rejected(byte[] evidence) {
        return new PushAnswer(
                Verdict.REJECTED, Optional.empty(), Optional.empty(), Optional.of(evidence));
    }

    public static PushAnswer indeterminate() {
        return new PushAnswer(
                Verdict.INDETERMINATE, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static PushAnswer indeterminate(byte[] evidence) {
        return new PushAnswer(
                Verdict.INDETERMINATE,
                Optional.empty(),
                Optional.empty(),
                Optional.of(evidence));
    }

    public static PushAnswer nothingSent() {
        return new PushAnswer(
                Verdict.NOTHING_SENT, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
