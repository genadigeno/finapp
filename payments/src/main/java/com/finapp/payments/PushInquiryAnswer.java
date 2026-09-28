package com.finapp.payments;

import java.util.Objects;
import java.util.Optional;

/**
 * The scheme's view of one push operation, asked by our reference (`P7-TSK-006`). The extra
 * word over {@link PushAnswer} is {@link Verdict#UNRECOGNISED}: the scheme <em>explicitly
 * answering</em> that it never saw our reference — which becomes the licence to conclude
 * "never executed" only once the declared outcome deadline has passed since the latest send
 * (ADR-0062 §3; that judgement is the caller's, on the locked row, with `P7-TSK-009`).
 */
public record PushInquiryAnswer(
        Verdict verdict,
        Optional<ProviderReference> schemeReference,
        Optional<String> settlementCycle,
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
        Objects.requireNonNull(evidence, "evidence must not be null");
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
                Optional.of(evidence));
    }

    public static PushInquiryAnswer rejected(byte[] evidence) {
        return new PushInquiryAnswer(
                Verdict.REJECTED, Optional.empty(), Optional.empty(), Optional.of(evidence));
    }

    public static PushInquiryAnswer unrecognised(byte[] evidence) {
        return new PushInquiryAnswer(
                Verdict.UNRECOGNISED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(evidence));
    }

    public static PushInquiryAnswer indeterminate() {
        return new PushInquiryAnswer(
                Verdict.INDETERMINATE, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static PushInquiryAnswer indeterminate(byte[] evidence) {
        return new PushInquiryAnswer(
                Verdict.INDETERMINATE,
                Optional.empty(),
                Optional.empty(),
                Optional.of(evidence));
    }
}
