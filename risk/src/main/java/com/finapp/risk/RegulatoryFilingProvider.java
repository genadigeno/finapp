package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;

/**
 * The authority a regulatory report is filed with (`P13-TSK-001` declared it; `P13-TSK-021` refined it - {@code DESIGN-021}
 * D6; {@code PHASE_13_PLAN.md} sections 12.9, 14; ADR-0111 decisions 5-7; owner decision C4) - a port over a jurisdiction's
 * filing channel, whose Phase 13 implementation in {@code app} is a simulated FIU endpoint (the {@code ScreeningAdapter}
 * precedent). The report is an artefact distinct from the case; the jurisdiction, the format version, the reporting
 * entity's reference and every field a real jurisdiction adds are adapter data.
 *
 * <h2>The contract every implementation keeps</h2>
 *
 * <ul>
 *   <li><strong>{@link #declared}</strong> is the adapter's data - a local, constant answer, never a call: which
 *       jurisdiction it files with, in which format version, as which reporting entity. A draft is refused for a
 *       jurisdiction no adapter declares.</li>
 *   <li><strong>{@link #file}</strong> submits under the <strong>filing reference</strong> - the report's own id, known
 *       before the call - and the provider <strong>deduplicates on it</strong> (the {@code Idempotency-Key} precedent,
 *       {@code INV-PAY-04}): a second submission under one reference is the first filing, never a second. It may throw
 *       (unavailable, timed out, the answer lost) - the caller records nothing and inquires later.</li>
 *   <li><strong>{@link #inquire}</strong> asks the standing of a filing by that reference - so an answer lost on the
 *       wire is recovered by asking, never by filing again (the payout precedent). {@link Outcome#NOT_RECEIVED} is the one
 *       definitive "nothing arrived under this reference": the filing is still owed, and is submitted under the same
 *       reference (the FX precedent's {@code unrecognised}).</li>
 * </ul>
 *
 * <p>The caller holds no database connection across any call ({@code RegulatoryReports}' drive: the claim committed
 * first, the record after - ADR-0046). What is sent is the artefact and nothing more: references, codes and the approved
 * narrative - no personal data is resolved beyond it.
 */
public interface RegulatoryFilingProvider {

    /** The adapter's declaration for {@code jurisdiction} - empty when it files with no such authority. Never I/O. */
    Optional<Declaration> declared(String jurisdiction);

    /** Submits {@code request} under its filing reference; the receipt is the authority's first answer. */
    Receipt file(Request request);

    /** The standing of the filing made under {@code filingReference}. */
    Receipt inquire(String filingReference);

    /**
     * What an adapter files as: the jurisdiction code, the format version it renders and the reporting entity's reference
     * with that authority.
     */
    record Declaration(String jurisdiction, int formatVersion, String reportingEntityReference) {

        public Declaration {
            Objects.requireNonNull(jurisdiction, "jurisdiction");
            Objects.requireNonNull(reportingEntityReference, "reportingEntityReference");
            if (!ReportContent.JURISDICTION.matcher(jurisdiction).matches()) {
                throw new IllegalArgumentException("a jurisdiction is a region code and an optional qualifier");
            }
            if (formatVersion < 1 || formatVersion > ReportContent.FORMAT_VERSION_BOUND) {
                throw new IllegalArgumentException("a format version is 1 to " + ReportContent.FORMAT_VERSION_BOUND);
            }
            if (reportingEntityReference.length() > 64
                    || !ReportContent.REFERENCE.matcher(reportingEntityReference).matches()) {
                throw new IllegalArgumentException("the reporting entity's reference is an opaque reference");
            }
        }
    }

    /** What is filed: the report, the reference the provider deduplicates on, and the artefact's content. */
    record Request(RegulatoryReportId report, String filingReference, ReportContent content) {

        public Request {
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(filingReference, "filingReference");
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * The provider's answer: the outcome, the provider's own reference for the filing when it issued one (always with an
     * acknowledgement), and the rejection's closed code (exactly with a rejection).
     */
    record Receipt(Outcome outcome, Optional<String> providerReference, Optional<String> rejectionCode) {

        public Receipt {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(providerReference, "providerReference");
            Objects.requireNonNull(rejectionCode, "rejectionCode");
            providerReference.ifPresent(reference -> {
                if (!ReportContent.REFERENCE.matcher(reference).matches()) {
                    throw new IllegalArgumentException("a provider's reference is an opaque reference");
                }
            });
            rejectionCode.ifPresent(code -> {
                if (!ReportContent.CODE.matcher(code).matches()) {
                    throw new IllegalArgumentException("a rejection's code is a closed code");
                }
            });
            if ((outcome == Outcome.REJECTED) != rejectionCode.isPresent()) {
                throw new IllegalArgumentException("a rejection carries its code, and only a rejection does");
            }
            if (outcome == Outcome.ACKNOWLEDGED && providerReference.isEmpty()) {
                throw new IllegalArgumentException("an acknowledgement carries the authority's reference");
            }
            if (outcome == Outcome.NOT_RECEIVED && providerReference.isPresent()) {
                throw new IllegalArgumentException("nothing received carries no reference");
            }
        }

        public static Receipt acknowledged(String providerReference) {
            return new Receipt(Outcome.ACKNOWLEDGED, Optional.of(providerReference), Optional.empty());
        }

        public static Receipt rejected(Optional<String> providerReference, String code) {
            return new Receipt(Outcome.REJECTED, providerReference, Optional.of(code));
        }

        public static Receipt unknown(Optional<String> providerReference) {
            return new Receipt(Outcome.UNKNOWN, providerReference, Optional.empty());
        }

        public static Receipt notReceived() {
            return new Receipt(Outcome.NOT_RECEIVED, Optional.empty(), Optional.empty());
        }
    }

    /** A filing's standing as the provider reports it. */
    enum Outcome {
        /** Received and acknowledged. */
        ACKNOWLEDGED,
        /** Refused; a new draft may follow. */
        REJECTED,
        /** Received, its standing not yet known - inquire again, never re-file. */
        UNKNOWN,
        /** Nothing was received under the reference - only an inquiry says so; the filing is still owed. */
        NOT_RECEIVED
    }
}
