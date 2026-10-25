package com.finapp.risk;

import java.util.Objects;

/**
 * What a claimed {@code FILED} report asks the authority next (`P13-TSK-021`; {@code DESIGN-021} D7; ADR-0111 decision 6;
 * the payout precedent): the <strong>filing reference</strong> - the report's own id, fixed before any call - and the
 * <strong>attempt</strong> the claim counted. The first attempt is the only one no earlier call can have preceded, so it
 * <strong>submits</strong>; every later attempt <strong>inquires first</strong> - a lost answer is asked for, never
 * replaced by a second filing - and submits under the same reference only when the authority answers that nothing
 * arrived under it.
 */
public record FilingInquiry(RegulatoryReportId report, String filingReference, int attempt) {

    public FilingInquiry {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(filingReference, "filingReference");
        if (attempt < 1) {
            throw new IllegalArgumentException("a claimed filing has counted at least one attempt: " + attempt);
        }
        if (!filingReference.equals(referenceOf(report))) {
            throw new IllegalArgumentException("a filing is inquired by its own report's reference");
        }
    }

    /** The reference a report is filed and inquired under - its id, canonical. */
    public static String referenceOf(RegulatoryReportId report) {
        return Objects.requireNonNull(report, "report").value().toString();
    }

    /** The inquiry of {@code claimed} - a {@code FILED} report its claim returned. */
    public static FilingInquiry of(RegulatoryReport claimed) {
        Objects.requireNonNull(claimed, "claimed");
        if (claimed.status() != RegulatoryReportStatus.FILED) {
            throw new IllegalArgumentException("only a FILED report is driven");
        }
        return new FilingInquiry(claimed.id(), referenceOf(claimed.id()), claimed.attempts());
    }

    /** True for the claim no call can have preceded - it submits; every other inquires first. */
    public boolean submits() {
        return attempt == 1;
    }
}
