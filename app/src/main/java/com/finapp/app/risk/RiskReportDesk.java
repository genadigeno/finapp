package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.Cases;
import com.finapp.risk.RegulatoryReport;
import com.finapp.risk.RegulatoryReportId;
import com.finapp.risk.RegulatoryReports;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskReasons;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The boundary half of the AML desk's regulatory report doors (`P13-TSK-021`; {@code PHASE_13_PLAN.md} sections 9, 11,
 * 12.9; {@code RISK_LIFECYCLES.md} section 4.8; ADR-0111 decisions 5-6; {@code DESIGN-021} D9; the {@code RiskActionDesk}
 * shape): each act keyed per principal ({@code risk.report:<type>:<id>}) and run in one transaction under its span; the
 * domain's refusals translated onto the error contract - a refused self-approval or self-dealing recorded {@code FAILED}
 * in a transaction of its own first; and, after a filing's transaction has committed - and again on every replay of its
 * key - the report driven to the authority ({@link RegulatoryFilingDriver#submitIfDue}: the claim committed, the call on no
 * transaction <strong>as the platform</strong>, the answer recorded). The filing answers {@code 202}: the authority's
 * answer is recorded on the report - usually already by this door's own drive, else by {@code RiskActionSchedule}'s
 * report leg.
 *
 * <p><strong>The narrative is served only here</strong>, behind {@code AML_REPORT_APPROVE} / {@code AML_REPORT_FILE}
 * ({@code INV-RSK-08}): in the acts' receipts and in the one read - which is recorded {@code risk.RegulatoryReportRead}
 * in a transaction of its own <em>before</em> it is answered (`-018`'s shape). The key's stored answer is the report's id,
 * never its content - the narrative is not in the idempotency record. Persons are never served.
 */
public final class RiskReportDesk {

    /** Every report act is keyed per principal under this scope. */
    static final String SCOPE = "risk.report:";

    private final RegulatoryReports reports;
    private final RegulatoryFilingDriver driver;
    private final IdempotentExecutor executor;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskReportDesk(RegulatoryReports reports, RegulatoryFilingDriver driver, IdempotentExecutor executor,
            RiskTransactions transactions, RiskFlowScope flow) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /**
     * A regulatory report as the AML desk reads it: its id (what the approval, the filing and the read name - and the
     * filing reference), its case, its status, the content (the jurisdiction and format, the reporting entity's
     * reference, the subject, its accounts, the transactions referenced, the grounds, the narrative, the attachments'
     * references), the authority's reference and a rejection's code, the drive's attempts and the database's stamps.
     * Instants ISO-8601 UTC; no person.
     */
    public record RiskReportView(String reportId, String caseId, String status, String jurisdiction, int formatVersion,
            String reportingEntityReference, String subjectKind, String subjectRef, List<String> accountRefs,
            List<String> transactionIds, List<String> grounds, String narrative, List<String> attachmentRefs,
            String providerReference, String rejectionCode, int attempts, String draftedAt, String approvedAt,
            String filedAt, String concludedAt) {}

    // ------------------------------------------------------------------ acts

    public RiskReportView draft(String idempotencyKey, String rawCaseId, RiskReportController.RiskReportDraftRequest
            request) {
        RiskCaseId caseId = caseId(rawCaseId);
        List<UUID> attachments = attachments(request.attachments());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String stored = flow.door(RiskSpans.REPORT, () -> keyed(actor,
                (uow, dealing) -> reports.recordRefusal(uow, caseId, dealing, actor, correlation), idempotencyKey,
                "report-draft|" + caseId.value() + "|" + request.jurisdiction() + "|" + request.narrative() + "|"
                        + attachments + "|" + request.reason(),
                uow -> reports.draft(uow, caseId, new RegulatoryReports.Draft(request.jurisdiction(),
                        request.narrative(), attachments, request.reason()), actor, correlation).id().value()
                        .toString()));
        return receipt(reportId(stored));
    }

    public RiskReportView approve(String idempotencyKey, String rawCaseId, String rawReportId,
            RiskReportController.RiskReportDecisionRequest request) {
        RiskCaseId caseId = caseId(rawCaseId);
        RegulatoryReportId reportId = reportId(rawReportId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(RiskSpans.REPORT, () -> keyed(actor,
                (uow, dealing) -> reports.recordRefusal(uow, reportId, dealing, actor, correlation), idempotencyKey,
                "report-approve|" + caseId.value() + "|" + reportId.value() + "|" + request.reason(),
                uow -> reports.approve(uow, caseId, reportId, request.reason(), actor, correlation).report().id()
                        .value().toString()));
        return receipt(reportId);
    }

    public RiskReportView file(String idempotencyKey, String rawCaseId, String rawReportId,
            RiskReportController.RiskReportDecisionRequest request) {
        RiskCaseId caseId = caseId(rawCaseId);
        RegulatoryReportId reportId = reportId(rawReportId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(RiskSpans.REPORT, () -> keyed(actor,
                (uow, dealing) -> reports.recordRefusal(uow, reportId, dealing, actor, correlation), idempotencyKey,
                "report-file|" + caseId.value() + "|" + reportId.value() + "|" + request.reason(),
                uow -> reports.file(uow, caseId, reportId, request.reason(), actor, correlation).report().id()
                        .value().toString()));
        // FILED has committed (or its key replayed it): now, outside every transaction and AS THE PLATFORM, the report to
        // the authority - again on every retry, which claims only a permit that has come, so it never files twice. A
        // failure is the driver's to count; the filing stands and the inquiry leg asks.
        driver.submitIfDue(reportId);
        return receipt(reportId);
    }

    /** {@code rawReportId} of {@code rawCaseId}, read for the AML desk and recorded before it is answered. */
    public RiskReportView read(String rawCaseId, String rawReportId) {
        RiskCaseId caseId = caseId(rawCaseId);
        RegulatoryReportId reportId = reportId(rawReportId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        RegulatoryReport served;
        try {
            served = flow.door(RiskSpans.REPORT, () -> transactions.inSnapshot(
                    snapshot -> reports.read(snapshot, caseId, reportId, actor)));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            transactions.inTransaction(uow -> {
                reports.recordRefusal(uow, reportId, refused, actor, correlation);
                return null;
            });
            throw selfDealing();
        } catch (RegulatoryReports.ReportNotFound none) {
            throw notFound();
        }
        transactions.inTransaction(uow -> {
            reports.recordRead(uow, served, actor, correlation);
            return null;
        });
        return view(served);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored report id; a refusal stores nothing, and a
     * refused self-approval or self-dealing is recorded {@code FAILED} in a transaction of its own first.
     */
    private String keyed(Actor actor, BiConsumer<Connection, RiskAssessments.SelfDealingRefused> recordDealing,
            String idempotencyKey, String fingerprinted, Function<Connection, String> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        // The fingerprint is a digest: the narrative it covers is never stored in the idempotency record.
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> refusalsRecorded(actor, recordDealing,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow ->
                        CommandResult.succeeded(StoredResponse.of(act.apply(uow).getBytes(StandardCharsets.UTF_8),
                                "text/plain"))))));
        return new String(outcome.body().orElseThrow(() -> new IllegalStateException(
                "a regulatory report act stores its report's id")), StandardCharsets.UTF_8);
    }

    /** The receipt of an act just performed or replayed - the report as it stands now. */
    private RiskReportView receipt(RegulatoryReportId reportId) {
        return transactions.inTransaction(uow -> view(reports.current(uow, reportId).orElseThrow(() ->
                new IllegalStateException("a report this door acted on is not found"))));
    }

    /**
     * {@code work}, a refused self-approval or self-dealing recorded {@code FAILED} in a transaction of its own after the
     * act rolled back ({@code INV-RSK-07}), then refused as before.
     */
    private <R> R refusalsRecorded(Actor actor,
            BiConsumer<Connection, RiskAssessments.SelfDealingRefused> recordDealing, Supplier<R> work) {
        try {
            return work.get();
        } catch (RegulatoryReports.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                reports.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            transactions.inTransaction(uow -> {
                recordDealing.accept(uow, dealing);
                return null;
            });
            throw dealing;
        }
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (Cases.CaseNotFound | RegulatoryReports.ReportNotFound unknown) {
            throw notFound();
        } catch (RegulatoryReports.SelfApprovalRefused self) {
            throw refused(RiskErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            throw selfDealing();
        } catch (RegulatoryReports.ReportNotFileable notFileable) {
            throw refused(RiskErrorCode.REPORT_NOT_FILEABLE, notFileable);
        } catch (RegulatoryReports.ReportRequestInvalid invalid) {
            throw refused(RiskErrorCode.REPORT_REQUEST_INVALID, invalid);
        } catch (RiskReasons.ReasonRequired reason) {
            throw refused(RiskErrorCode.REASON_REQUIRED, reason);
        }
    }

    static RiskReportView view(RegulatoryReport report) {
        return new RiskReportView(
                report.id().value().toString(),
                report.caseId().value().toString(),
                report.status().name(),
                report.content().jurisdiction(),
                report.content().formatVersion(),
                report.content().reportingEntityReference(),
                "PARTY",
                report.content().subject().toString(),
                report.content().accounts().stream().map(UUID::toString).toList(),
                report.content().transactions().stream().map(UUID::toString).toList(),
                report.content().grounds(),
                report.content().narrative(),
                report.content().attachments().stream().map(UUID::toString).toList(),
                report.providerReference().orElse(null),
                report.rejectionCode().orElse(null),
                report.attempts(),
                report.draftedAt().map(Instant::toString).orElse(null),
                report.approvedAt().map(Instant::toString).orElse(null),
                report.filedAt().map(Instant::toString).orElse(null),
                report.concludedAt().map(Instant::toString).orElse(null));
    }

    private static List<UUID> attachments(List<String> raw) {
        List<UUID> attachments = new ArrayList<>();
        if (raw == null) {
            return attachments;
        }
        for (String reference : raw) {
            try {
                attachments.add(UUID.fromString(Objects.requireNonNull(reference, "reference")));
            } catch (IllegalArgumentException | NullPointerException malformed) {
                throw new ApiException(RiskErrorCode.REPORT_REQUEST_INVALID, "A regulatory report command was refused",
                        "an attachment is named by its identifier");
            }
        }
        return attachments;
    }

    private static ApiException refused(RiskErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A regulatory report command was refused", cause.getMessage());
    }

    private static ApiException selfDealing() {
        return new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A regulatory report act was refused",
                "a holder never acts on or reads a report of their own party, nor one whose subject the platform cannot"
                        + " place; nothing was done.");
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk record answers the request", "no such record.");
    }

    private static RiskCaseId caseId(String raw) {
        try {
            return RiskCaseId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static RegulatoryReportId reportId(String raw) {
        try {
            return RegulatoryReportId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a regulatory report act runs inside a correlation scope"))
                .correlationId();
    }
}
