package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;

/**
 * The authority a regulatory report is filed with (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 12.9, 14; ADR-0111
 * decision 5; owner decision C4) - a port over a jurisdiction's filing channel, whose Phase 13 implementation in
 * {@code app} is a simulated FIU endpoint (the {@code ScreeningAdapter} precedent). The report is an artefact distinct
 * from the case; the jurisdiction, the format version and every field a real jurisdiction adds are adapter data.
 *
 * <p>The filing (`-021`) commits {@code FILED} with the provider's reference pending before the call, holds no
 * connection across it, and treats an {@link Outcome#UNKNOWN} answer as a permit on the {@code FILED} row: inquired by
 * reference until acknowledged or rejected, never re-filed (the payout precedent). An interface with no implementation;
 * `-021` refines its shape.
 */
public interface RegulatoryFilingProvider {

    /** File {@code request}; the receipt carries the provider's reference when one was issued. */
    Receipt file(Request request);

    /** The current standing of a filing by the provider's reference. */
    Receipt inquire(String providerReference);

    /** What is filed: the report, its jurisdiction, the format version the adapter speaks and the rendered content. */
    record Request(RegulatoryReportId report, String jurisdiction, String formatVersion, String content) {

        public Request {
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(jurisdiction, "jurisdiction");
            Objects.requireNonNull(formatVersion, "formatVersion");
            Objects.requireNonNull(content, "content");
        }
    }

    /** The provider's answer: its reference when issued, and the outcome. */
    record Receipt(Optional<String> providerReference, Outcome outcome) {

        public Receipt {
            Objects.requireNonNull(providerReference, "providerReference");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** A filing's standing as the provider reports it. */
    enum Outcome {
        /** Received and acknowledged. */
        ACKNOWLEDGED,
        /** Refused; a new draft follows. */
        REJECTED,
        /** Not yet known - inquire, never re-file. */
        UNKNOWN
    }
}
