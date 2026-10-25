package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;

/**
 * One drive's answer from the authority, as the record writes it (`P13-TSK-021`; {@code DESIGN-021} D6-D7;
 * {@code PHASE_13_PLAN.md} section 14; ADR-0111 decision 6): {@link Kind#ACKNOWLEDGED} with the authority's reference,
 * {@link Kind#REJECTED} with its closed code, or {@link Kind#UNKNOWN} - received or not, the standing not known: the
 * {@code FILED} row keeps its permit and is asked again, never filed again. {@code submitted} says whether this drive sent
 * the filing (its first attempt, or the owed submission after a definitive "nothing received"), for the meters.
 *
 * <p>A submission's own {@code NOT_RECEIVED} - a provider contradicting the call it just answered - is read as
 * {@link Kind#UNKNOWN}: the safe direction costs one inquiry; reading it as anything decided would record an answer
 * nobody gave.
 */
public record FilingOutcome(Kind kind, Optional<String> providerReference, Optional<String> rejectionCode,
        boolean submitted) {

    /** What the record does with an answer. */
    public enum Kind {
        /** {@code FILED → ACKNOWLEDGED}. */
        ACKNOWLEDGED,
        /** {@code FILED → REJECTED}. */
        REJECTED,
        /** {@code FILED} kept, the permit an inquiry interval ahead. */
        UNKNOWN
    }

    public FilingOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(providerReference, "providerReference");
        Objects.requireNonNull(rejectionCode, "rejectionCode");
        if ((kind == Kind.REJECTED) != rejectionCode.isPresent()) {
            throw new IllegalArgumentException("a rejection carries its code, and only a rejection does");
        }
        if (kind == Kind.ACKNOWLEDGED && providerReference.isEmpty()) {
            throw new IllegalArgumentException("an acknowledgement carries the authority's reference");
        }
    }

    /** {@code receipt} as the record reads it; {@code submitted} when this drive sent the filing. */
    public static FilingOutcome of(RegulatoryFilingProvider.Receipt receipt, boolean submitted) {
        Objects.requireNonNull(receipt, "receipt");
        return switch (receipt.outcome()) {
            case ACKNOWLEDGED -> new FilingOutcome(Kind.ACKNOWLEDGED, receipt.providerReference(), Optional.empty(),
                    submitted);
            case REJECTED -> new FilingOutcome(Kind.REJECTED, receipt.providerReference(), receipt.rejectionCode(),
                    submitted);
            case UNKNOWN, NOT_RECEIVED -> new FilingOutcome(Kind.UNKNOWN, receipt.providerReference(), Optional.empty(),
                    submitted);
        };
    }

    /** True when the authority has answered for good. */
    public boolean concludes() {
        return kind != Kind.UNKNOWN;
    }
}
