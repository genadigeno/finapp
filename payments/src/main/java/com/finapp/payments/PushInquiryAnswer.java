package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * The scheme's view of one push operation, asked by our reference (`P7-TSK-006`). The extra
 * word over {@link PushAnswer} is {@link Verdict#UNRECOGNISED}: the scheme <em>explicitly
 * answering</em> that it never saw our reference — which becomes the licence to conclude
 * "never executed" only once the declared outcome deadline has passed since the latest send
 * (ADR-0062 §3; that judgement is the caller's, on the locked row, with `P7-TSK-009`).
 *
 * <p>{@code executed} is the amount the scheme says it executed — carried by the initiation
 * inquiry since the Phase 7 -&gt; 8 transition, because the execution applier judges it for
 * both of its producers (the gate found the inquiry sweep crediting the initiation's ask with
 * no amount anywhere to judge). The transfer and return inquiries carry none: their amounts
 * are ours, frozen on the row we sent.
 */
public record PushInquiryAnswer(
        Verdict verdict,
        Optional<ProviderReference> schemeReference,
        Optional<String> settlementCycle,
        Optional<Money> executed,
        Optional<byte[]> evidence) {

    public enum Verdict {
        /** Executed and final. */
        ACCEPTED,
        /** Rejected: knowledge, nothing moved. */
        REJECTED,
        /** The scheme explicitly does not know our reference. */
        UNRECOGNISED,
        /** No usable answer; ask again ({@code INV-LIFE-03}). */
        INDETERMINATE
    }

    public PushInquiryAnswer {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
        Objects.requireNonNull(executed, "executed must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (executed.isPresent() && (verdict != Verdict.ACCEPTED || !executed.get().isPositive())) {
            throw new IllegalArgumentException(
                    "an executed amount accompanies an ACCEPTED answer only, and is positive");
        }
        settlementCycle.ifPresent(PushAnswer::requireCycleShape);
        if (verdict == Verdict.ACCEPTED && schemeReference.isEmpty()) {
            throw new IllegalArgumentException(
                    "an ACCEPTED inquiry carries the scheme's transaction reference - the"
                            + " approved-without-reference rule, asked instead of sent");
        }
    }

    public static PushInquiryAnswer accepted(
            ProviderReference schemeReference, Optional<String> settlementCycle,
            byte[] evidence) {
        return new PushInquiryAnswer(
                Verdict.ACCEPTED,
                Optional.of(schemeReference),
                settlementCycle,
                Optional.empty(),
                Optional.of(evidence));
    }

    /** An initiation's execution, with the amount the scheme says it executed. */
    public static PushInquiryAnswer executed(
            ProviderReference schemeReference, Optional<String> settlementCycle,
            Money executed, byte[] evidence) {
        return new PushInquiryAnswer(
                Verdict.ACCEPTED,
                Optional.of(schemeReference),
                settlementCycle,
                Optional.of(executed),
                Optional.of(evidence));
    }

    public static PushInquiryAnswer rejected(byte[] evidence) {
        return new PushInquiryAnswer(
                Verdict.REJECTED, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(evidence));
    }

    public static PushInquiryAnswer unrecognised(byte[] evidence) {
        return new PushInquiryAnswer(
                Verdict.UNRECOGNISED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(evidence));
    }

    public static PushInquiryAnswer indeterminate() {
        return new PushInquiryAnswer(
                Verdict.INDETERMINATE, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    public static PushInquiryAnswer indeterminate(byte[] evidence) {
        return new PushInquiryAnswer(
                Verdict.INDETERMINATE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(evidence));
    }
}
