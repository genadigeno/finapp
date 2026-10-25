package com.finapp.risk;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The regulatory report's persistence (`P13-TSK-021`; {@code risk V016}; ADR-0033) - every edge one conditional statement
 * on the caller's unit of work, its history row ({@code regulatory_report_event}) written by the same call, the stamps the
 * database's. The arbiters are the database's: {@code regulatory_report_one_live_per_case} for a birth, the row lock and
 * the conditional edge for a person's decision, {@code FOR UPDATE SKIP LOCKED} on a due permit for a claim, the
 * conditional record on {@code FILED} and the claimed attempt. Risk's own tables only.
 */
public interface RegulatoryReportStore {

    /** A report to be born {@code DRAFT}: its id (its {@code REPORT_DRAFTED} act's), its case, content and drafter. */
    record NewReport(RegulatoryReportId id, RiskCaseId caseId, ReportContent content, RiskCase.Person drafter,
            String correlationId) {
        public NewReport {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(drafter, "drafter");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /** The monitored transactions a draft references, the first {@code limit} in the journal's order, and how many matched. */
    record Referenced(List<UUID> transactions, long matched) {
        public Referenced {
            transactions = List.copyOf(Objects.requireNonNull(transactions, "transactions"));
            if (matched < transactions.size()) {
                throw new IllegalArgumentException("more transactions referenced than matched");
            }
        }
    }

    /** {@code caseId}'s report that occupies the case - {@code DRAFT}, {@code APPROVED}, {@code FILED} or acknowledged. */
    Optional<RegulatoryReport> liveOf(Connection unitOfWork, RiskCaseId caseId);

    /**
     * Births {@code report} {@code DRAFT} with its history row - empty, nothing written, when its case already has a live
     * or accepted report ({@code INSERT … ON CONFLICT (case_id) WHERE status <> 'REJECTED' DO NOTHING}).
     */
    Optional<RegulatoryReport> insert(Connection unitOfWork, NewReport report);

    /** R4′: the report row {@code FOR UPDATE}. */
    Optional<RegulatoryReport> lock(Connection unitOfWork, RegulatoryReportId id);

    /** The report as it stands - no lock. */
    Optional<RegulatoryReport> find(Connection unitOfWork, RegulatoryReportId id);

    /** {@code DRAFT → APPROVED} by {@code approver}, with its history row; empty when it is not {@code DRAFT}. */
    Optional<RegulatoryReport> approve(Connection unitOfWork, RegulatoryReportId id, RiskCase.Person approver,
            String correlationId);

    /**
     * {@code APPROVED → FILED} by {@code filer}, the drive's permit due at once and no attempt counted, with its history
     * row; empty when it is not {@code APPROVED}.
     */
    Optional<RegulatoryReport> file(Connection unitOfWork, RegulatoryReportId id, RiskCase.Person filer,
            String correlationId);

    /**
     * The drive's claim of {@code id}: {@code FOR UPDATE SKIP LOCKED} where it is {@code FILED} and its permit has come,
     * one attempt counted and the permit moved {@code lease} ahead - empty when another claimer holds it, its permit has
     * not come, or it is concluded.
     */
    Optional<RegulatoryReport> claim(Connection unitOfWork, RegulatoryReportId id, Duration lease);

    /** The oldest due {@code FILED} report claimed, as {@link #claim} - the inquiry leg's. */
    Optional<RegulatoryReport> claimNextDue(Connection unitOfWork, Duration lease);

    /**
     * Records {@code outcome} on the claimed {@code id}, conditional on {@code FILED} and the claim's {@code attempt}: an
     * acknowledgement or a rejection concludes it with its history row ({@code actor} - the platform); an unknown
     * standing keeps it {@code FILED} with the permit {@code inquiryInterval} ahead and the provider's reference recorded
     * once. Empty - nothing written - when another drive recorded first.
     */
    Optional<RegulatoryReport> record(Connection unitOfWork, RegulatoryReportId id, int attempt, FilingOutcome outcome,
            Duration inquiryInterval, RiskCase.Person actor, String correlationId);

    /**
     * The monitored transactions born from {@code journalEntries} that touch one of {@code accounts} - as the subject's
     * movement or as its customer counterparty - the first {@code limit} in the journal's order, and how many matched.
     */
    Referenced referenced(Connection unitOfWork, List<UUID> journalEntries, Set<UUID> accounts, int limit);
}
