package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One regulatory report as stored (`P13-TSK-021`; {@code risk V016}'s {@code regulatory_report}; {@code PHASE_13_PLAN.md}
 * sections 5, 12.9; {@code RISK_LIFECYCLES.md} section 4.8; ADR-0111 decisions 5-6; {@code INV-RSK-06}, {@code INV-RSK-07})
 * - the filing artefact, an aggregate of its own beside the case it was drafted from.
 *
 * <pre>
 *   DRAFT ──★ approved by a second AML_REPORT_APPROVE holder──> APPROVED ──filed (AML_REPORT_FILE)──> FILED
 *   FILED ──the authority acknowledges (its reference)──> ACKNOWLEDGED      FILED ──it rejects (a code)──> REJECTED
 * </pre>
 *
 * <p>Its id is its {@code REPORT_DRAFTED} investigator action's on the case ({@code DESIGN-021} D2) and its
 * {@link #filingReference()} - the reference the provider deduplicates on and is asked by. Its {@link #content()} is
 * fixed at the draft for every writer ({@code risk V016}'s trigger and the grant's shape, D4). The persons are
 * authenticated humans - {@code CUSTOMER}-typed as the session establishes every operator, or {@code EMPLOYEE} - and the
 * approver is never the drafter (refused here as well as by {@code regulatory_report_four_eyes}). While {@code FILED} the
 * row carries the drive's permit ({@code nextInquiryAt}) and its counted {@code attempts}; once concluded, nothing moves.
 * The instants are the database's.
 */
public record RegulatoryReport(
        RegulatoryReportId id,
        RiskCaseId caseId,
        ReportContent content,
        RegulatoryReportStatus status,
        RiskCase.Person draftedBy,
        Optional<Instant> draftedAt,
        Optional<RiskCase.Person> approvedBy,
        Optional<Instant> approvedAt,
        Optional<RiskCase.Person> filedBy,
        Optional<Instant> filedAt,
        Optional<String> providerReference,
        Optional<String> rejectionCode,
        Optional<Instant> concludedAt,
        Optional<Instant> nextInquiryAt,
        int attempts,
        String correlationId) {

    public RegulatoryReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(draftedBy, "draftedBy");
        Objects.requireNonNull(draftedAt, "draftedAt");
        Objects.requireNonNull(approvedBy, "approvedBy");
        Objects.requireNonNull(approvedAt, "approvedAt");
        Objects.requireNonNull(filedBy, "filedBy");
        Objects.requireNonNull(filedAt, "filedAt");
        Objects.requireNonNull(providerReference, "providerReference");
        Objects.requireNonNull(rejectionCode, "rejectionCode");
        Objects.requireNonNull(concludedAt, "concludedAt");
        Objects.requireNonNull(nextInquiryAt, "nextInquiryAt");
        Objects.requireNonNull(correlationId, "correlationId");
        person(draftedBy, "drafter");
        approvedBy.ifPresent(approver -> person(approver, "approver"));
        filedBy.ifPresent(filer -> person(filer, "filer"));
        boolean pastDraft = status != RegulatoryReportStatus.DRAFT;
        if (pastDraft != approvedBy.isPresent()) {
            throw new IllegalArgumentException("a report names its approver exactly once approved");
        }
        if (status.filed() != filedBy.isPresent()) {
            throw new IllegalArgumentException("a report names its filer exactly once filed");
        }
        if (approvedBy.filter(draftedBy::equals).isPresent()) {
            // INV-RSK-07: refused by the value as well as by regulatory_report_four_eyes.
            throw new IllegalArgumentException("a report is approved by someone other than its drafter");
        }
        if ((status == RegulatoryReportStatus.FILED) != nextInquiryAt.isPresent()) {
            throw new IllegalArgumentException("a report carries its drive's permit exactly while FILED");
        }
        if (status.concluded() != concludedAt.isPresent()) {
            throw new IllegalArgumentException("a report is stamped concluded exactly when the authority has answered");
        }
        if ((status == RegulatoryReportStatus.REJECTED) != rejectionCode.isPresent()) {
            throw new IllegalArgumentException("a rejected report carries its code, and only a rejected one");
        }
        if (status == RegulatoryReportStatus.ACKNOWLEDGED && providerReference.isEmpty()) {
            throw new IllegalArgumentException("an acknowledged report carries the authority's reference");
        }
        if (!status.filed() && (providerReference.isPresent() || attempts != 0)) {
            throw new IllegalArgumentException("a report not yet filed has no provider's reference and no attempt");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("a report's attempts count from 0");
        }
    }

    /** The reference it is filed and inquired under - its id ({@code DESIGN-021} D6). */
    public String filingReference() {
        return FilingInquiry.referenceOf(id);
    }

    /** True when {@code actor} drafted it. */
    public boolean draftedBy(Actor actor) {
        return draftedBy.is(actor);
    }

    /** True when {@code actor} approved it. */
    public boolean approvedBy(Actor actor) {
        return approvedBy.filter(person -> person.is(actor)).isPresent();
    }

    /** True when {@code actor} filed it. */
    public boolean filedBy(Actor actor) {
        return filedBy.filter(person -> person.is(actor)).isPresent();
    }

    private static void person(RiskCase.Person person, String role) {
        if (person.type() != ActorType.CUSTOMER && person.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a report's " + role + " is a person - an authenticated human");
        }
    }

    /** Identifiers and codes only - never the subject, a person or the narrative ({@code INV-RSK-08}). */
    @Override
    public String toString() {
        return "RegulatoryReport[" + id.value() + ", case " + caseId.value() + ", " + status + ", " + content + "]";
    }
}
