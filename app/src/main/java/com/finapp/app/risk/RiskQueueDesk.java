package com.finapp.app.risk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AlertQueue;
import com.finapp.risk.Alerts;
import com.finapp.risk.CaseAction;
import com.finapp.risk.CaseEvent;
import com.finapp.risk.CaseWorklist;
import com.finapp.risk.Cases;
import com.finapp.risk.Queues;
import com.finapp.risk.RegulatoryReport;
import com.finapp.risk.RegulatoryReportStore;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAlertId;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The investigator's reads (`P13-TSK-018`; {@code PHASE_13_PLAN.md} sections 9, 11; {@code INV-RSK-08};
 * {@code DESIGN-018} D3-D8; `-004`'s {@code RiskAssessmentDesk} replay shape): the alert queue, the case queue and one
 * case's file, behind {@code RISK_INVESTIGATE}.
 *
 * <p>Each read is one read-only {@code REPEATABLE READ} snapshot - a page is one instant, every row wholly in or out -
 * and then its record, {@code risk.AlertRead} or {@code risk.CaseRead}, committed in a transaction of its own
 * <strong>before</strong> the answer: nothing is served unrecorded. A read takes no idempotency key; a repeated read is
 * a second serving, recorded again. A filter outside the vocabulary, a page below one row and a cursor naming no row
 * are {@code 422 risk.QueueRequestInvalid}; a page above the bound is the bound. The reader's own party's rows - or
 * rows whose party cannot be placed - are withheld from a page; a file of one is {@code 403 risk.SelfDealingRefused}, the
 * attempt recorded {@code FAILED} in a transaction of its own; an unknown or malformed case id is the uniform
 * {@code 404 risk.NotFound}.
 *
 * <p><strong>What is served.</strong> Ids, codes, statuses, the database's stamps, the subject by reference, evidence
 * <em>by reference</em> (an assessment is read through {@code GET /v1/operator/risk/assessments/{id}}, audited
 * {@code risk.AssessmentRead}), the person-written texts the analyst's desk alone may see (a note, a suppression's
 * reasons - {@code DATA_CLASSIFICATION.md}) - and of the persons only a proposal's proposer (`P17-TSK-109`, B4.5: so the
 * console disables the proposer's own approval in advance; {@code risk.SelfApprovalRefused} stays the control): who
 * approved, decided or took stays withheld. A case's regulatory reports are listed in its file only to a reader who also
 * holds {@code AML_REPORT_APPROVE} (`P17-TSK-109`, the owner's tipping-off decision of 2026-11-18): an analyst's file never
 * says one exists. Nothing here is logged.
 */
public final class RiskQueueDesk {

    private final AlertQueue alerts;
    private final CaseWorklist cases;
    private final RegulatoryReportStore reports;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskQueueDesk(AlertQueue alerts, CaseWorklist cases, RegulatoryReportStore reports, RiskTransactions transactions,
            RiskFlowScope flow) {
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    // ------------------------------------------------------------------ views

    /**
     * One alert as the analyst's desk shows it: its kind, severity, subject by reference, the rule code and the window
     * that raised it, the policy version, its evidence by reference, its state - the case it attached to, the
     * suppression's proposal and decision without their persons - and the database's stamps. Instants ISO-8601 UTC.
     */
    public record RiskAlertView(String alertId, String kind, String severity, String subjectKind, String subjectRef,
            String ruleCode, String windowKind, String windowStart, String policyVersionId, List<String> signalIds,
            List<String> assessmentIds, List<String> journalEntryIds, String status, String caseId, String attachedAt,
            RiskSuppressionView suppression, String raisedAt) {}

    /**
     * An alert's suppression: {@code PROPOSED} (awaiting a second person) or {@code DECIDED} (the alert suppressed), the
     * reason code, the proposer's text and the decider's, the database's stamps, and who proposed it (`P17-TSK-109`: the
     * proposer's actor id, so the console disables their own approval) - never who decided.
     */
    public record RiskSuppressionView(String status, String reasonCode, String reason, String proposedAt,
            String decisionReason, String decidedAt, String proposedBy) {}

    /**
     * A page of the alert queue, a section 7 collection (`P17-TSK-042`; read "{@code alerts} and {@code next}, the raw id of
     * the last alert, {@code null} at the end" until then): the alerts, oldest first, and the opaque cursor to the next page -
     * absent, not null, at the end.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RiskAlertPageView(List<RiskAlertView> items, String nextCursor) {}

    /** A page of the case queue, a section 7 collection (`P17-TSK-042`): the cases' heads, oldest first, and the next cursor. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RiskCasePageView(List<RiskCaseDesk.RiskCaseView> items, String nextCursor) {}

    /** The queues' collection names, inside their cursors (`P17-TSK-042`). */
    static final String ALERT_QUEUE = "risk.alerts";
    static final String CASE_QUEUE = "risk.cases";

    /**
     * One case's file: its head, the alerts attached to it, its investigator actions, its history, its downstream
     * actions and every evidence reference it holds - each a kind and an id, resolved by its own audited read - and, to a
     * reader who also holds {@code AML_REPORT_APPROVE} alone (`P17-TSK-109`), its regulatory reports' heads: absent for
     * everyone else, so an analyst's file is what it always was.
     */
    public record RiskCaseFileView(RiskCaseDesk.RiskCaseView riskCase, List<RiskAlertView> alerts,
            List<RiskCaseActionView> actions, List<RiskCaseEventView> history,
            List<RiskActionDesk.RiskActionView> riskActions, List<RiskEvidenceView> evidence,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<RiskCaseReportView> reports) {}

    /**
     * One regulatory report of a case, by reference (`P17-TSK-109`): its id, status, jurisdiction and drafting stamp - never
     * its narrative, which the report's own audited read serves ({@code GET .../cases/{caseId}/reports/{reportId}}).
     */
    public record RiskCaseReportView(String reportId, String status, String jurisdiction, String draftedAt) {}

    /**
     * One investigator action: its ordinal (the revision it produced), its kind, its text (a note, the evidence's or an
     * escalation's reason), the evidence it attached by reference, the disposition it proposed, the queue it routed
     * to, and the database's stamp - never who acted.
     */
    public record RiskCaseActionView(int ordinal, String kind, String text, List<String> signalIds,
            List<String> assessmentIds, List<String> journalEntryIds, String disposition, boolean prohibits,
            String queue, String recordedAt) {}

    /** One step of the case's history: the revision, the step's kind, the state it reached, what it names - never who. */
    public record RiskCaseEventView(int revision, String kind, String status, String level, String queue,
            String actionId, String alertId, String assessmentId, String recordedAt) {}

    /** One evidence reference: {@code ASSESSMENT}, {@code SIGNAL} or {@code JOURNAL_ENTRY}, and the id. */
    public record RiskEvidenceView(String kind, String id) {}

    // ------------------------------------------------------------------ the alert queue

    /**
     * A page of the alert queue, read and recorded: {@code limit} {@value com.finapp.risk.Queues#DEFAULT_PAGE} unless named,
     * {@value com.finapp.risk.Queues#PAGE_BOUND} at most and refused above, never reduced (`P17-TSK-042`); the opaque
     * {@code cursor} of the page before.
     */
    public RiskAlertPageView alerts(String status, String kind, String rawCursor, String limit) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        AlertQueue.Filter filter = asked(() -> AlertQueue.Filter.named(Optional.ofNullable(status),
                Optional.ofNullable(kind)));
        int size = OperatorCollection.limit(limit, Queues.DEFAULT_PAGE, Queues.PAGE_BOUND);
        Optional<RiskAlertId> cursor = cursor(ALERT_QUEUE, rawCursor, RiskAlertId::of);
        AlertQueue.Page page;
        try {
            page = flow.door(RiskSpans.QUEUE, () -> asked(() -> transactions.inSnapshot(
                    snapshot -> alerts.page(snapshot, filter, cursor, size, actor))));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            transactions.inTransaction(uow -> {
                alerts.recordRefusal(uow, filter, refused, actor, correlation);
                return null;
            });
            throw selfDealing();
        }
        // Recorded before it is answered, in a transaction of its own: a page nobody recorded was never served.
        transactions.inTransaction(uow -> {
            alerts.record(uow, page, actor, correlation);
            return null;
        });
        return new RiskAlertPageView(page.alerts().stream().map(RiskQueueDesk::view).toList(),
                page.next().map(id -> OperatorCollection.cursor(ALERT_QUEUE, id.value().toString())).orElse(null));
    }

    /**
     * {@code rawAlertId} read and recorded (`P17-TSK-020`, gap G-90): the queue's own judgement of one row, so a link opens only
     * what a page would serve. An alert of the reader's own party, or one that cannot be placed, is {@code 403
     * risk.SelfDealingRefused} recorded {@code FAILED}, as the case file answers one; an unknown or malformed id the uniform
     * {@code 404 risk.NotFound}.
     */
    public RiskAlertView alert(String rawAlertId) {
        RiskAlertId alertId = alertId(rawAlertId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        RiskAlert alert;
        try {
            alert = flow.door(RiskSpans.QUEUE, () -> transactions.inSnapshot(
                    snapshot -> alerts.read(snapshot, alertId, actor)));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            transactions.inTransaction(uow -> {
                alerts.recordRefusal(uow, alertId, refused, actor, correlation);
                return null;
            });
            throw selfDealing();
        } catch (Alerts.AlertNotFound none) {
            throw notFound();
        }
        // Recorded before it is answered, in a transaction of its own: an alert nobody recorded was never served.
        transactions.inTransaction(uow -> {
            alerts.record(uow, alert, actor, correlation);
            return null;
        });
        return view(alert);
    }

    // ------------------------------------------------------------------ the case queue and the file

    /** A page of the case queue, read and recorded: the alert queue's limit and cursor rules (`P17-TSK-042`). */
    public RiskCasePageView cases(String status, String kind, String rawCursor, String limit) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        CaseWorklist.Filter filter = asked(() -> CaseWorklist.Filter.named(Optional.ofNullable(status),
                Optional.ofNullable(kind)));
        int size = OperatorCollection.limit(limit, Queues.DEFAULT_PAGE, Queues.PAGE_BOUND);
        Optional<RiskCaseId> cursor = cursor(CASE_QUEUE, rawCursor, RiskCaseId::of);
        CaseWorklist.Page page;
        try {
            page = flow.door(RiskSpans.QUEUE, () -> asked(() -> transactions.inSnapshot(
                    snapshot -> cases.page(snapshot, filter, cursor, size, actor))));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            transactions.inTransaction(uow -> {
                cases.recordRefusal(uow, filter, refused, actor, correlation);
                return null;
            });
            throw selfDealing();
        }
        transactions.inTransaction(uow -> {
            cases.record(uow, page, actor, correlation);
            return null;
        });
        return new RiskCasePageView(page.cases().stream().map(riskCase -> RiskCaseDesk.view(riskCase, false)).toList(),
                page.next().map(id -> OperatorCollection.cursor(CASE_QUEUE, id.value().toString())).orElse(null));
    }

    /** {@code rawCaseId}'s file, read and recorded - without its reports. */
    public RiskCaseFileView file(String rawCaseId) {
        return file(rawCaseId, false);
    }

    /**
     * {@code rawCaseId}'s file, read and recorded; with its regulatory reports' heads when {@code withReports} - the door's
     * judgement that the reader also holds {@code AML_REPORT_APPROVE} (`P17-TSK-109`), read on the file's own snapshot.
     */
    public RiskCaseFileView file(String rawCaseId, boolean withReports) {
        RiskCaseId caseId = caseId(rawCaseId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Filed filed;
        try {
            filed = flow.door(RiskSpans.CASE_READ, () -> transactions.inSnapshot(snapshot -> {
                CaseWorklist.CaseFile read = cases.read(snapshot, caseId, actor);
                return new Filed(read, withReports ? Optional.of(reports.ofCase(snapshot, caseId)) : Optional.empty());
            }));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            transactions.inTransaction(uow -> {
                cases.recordRefusal(uow, caseId, refused, actor, correlation);
                return null;
            });
            throw selfDealing();
        } catch (Cases.CaseNotFound none) {
            throw notFound();
        }
        transactions.inTransaction(uow -> {
            if (filed.reports().isPresent()) {
                cases.record(uow, filed.file(), filed.reports().get().size(), actor, correlation);
            } else {
                cases.record(uow, filed.file(), actor, correlation);
            }
            return null;
        });
        return view(filed.file(), filed.reports());
    }

    /** A file as read, and its reports when they were asked for. */
    private record Filed(CaseWorklist.CaseFile file, Optional<List<RegulatoryReport>> reports) {}

    // ------------------------------------------------------------------ the views

    static RiskAlertView view(RiskAlert alert) {
        RiskSuppressionView suppression = alert.suppression().map(proposal -> new RiskSuppressionView(
                alert.decision().isPresent() ? "DECIDED" : "PROPOSED",
                proposal.reasonCode().name(),
                proposal.reason(),
                proposal.proposedAt().toString(),
                alert.decision().map(RiskAlert.Decision::reason).orElse(null),
                alert.decision().map(decision -> decision.decidedAt().toString()).orElse(null),
                proposal.proposedBy())).orElse(null);
        return new RiskAlertView(
                alert.id().value().toString(),
                alert.kind().name(),
                alert.severity().name(),
                alert.subject().kind().name(),
                alert.subject().ref(),
                alert.rule().code(),
                alert.window().kind().name(),
                alert.window().start().toString(),
                alert.policyVersion().map(version -> version.value().toString()).orElse(null),
                alert.evidence().signals().stream().map(id -> id.value().toString()).toList(),
                alert.evidence().assessments().stream().map(id -> id.value().toString()).toList(),
                alert.evidence().journalEntries().stream().map(UUID::toString).toList(),
                alert.status().name(),
                alert.attachedCase().map(id -> id.value().toString()).orElse(null),
                alert.attachedAt().map(Instant::toString).orElse(null),
                suppression,
                alert.raisedAt().toString());
    }

    static RiskCaseFileView view(CaseWorklist.CaseFile file) {
        return view(file, Optional.empty());
    }

    static RiskCaseFileView view(CaseWorklist.CaseFile file, Optional<List<RegulatoryReport>> reports) {
        int revision = file.riskCase().revision();
        return new RiskCaseFileView(
                RiskCaseDesk.view(file.riskCase(), false),
                file.alerts().stream().map(RiskQueueDesk::view).toList(),
                file.actions().stream().map(RiskQueueDesk::action).toList(),
                file.history().stream().map(RiskQueueDesk::event).toList(),
                file.riskActions().stream().map(action -> RiskActionDesk.view(action, revision)).toList(),
                file.evidence().stream().map(ref -> new RiskEvidenceView(ref.kind().name(), ref.id().toString()))
                        .toList(),
                reports.map(served -> served.stream().map(report -> new RiskCaseReportView(
                        report.id().value().toString(),
                        report.status().name(),
                        report.content().jurisdiction(),
                        report.draftedAt().map(Instant::toString).orElse(null))).toList()).orElse(null));
    }

    private static RiskCaseActionView action(CaseAction action) {
        return new RiskCaseActionView(
                action.ordinal(),
                action.kind().name(),
                action.text().orElse(null),
                action.evidence().signals().stream().map(id -> id.value().toString()).toList(),
                action.evidence().assessments().stream().map(id -> id.value().toString()).toList(),
                action.evidence().journalEntries().stream().map(UUID::toString).toList(),
                action.disposition().map(Enum::name).orElse(null),
                action.prohibits(),
                action.queue().map(Enum::name).orElse(null),
                action.recordedAt().map(Instant::toString).orElse(null));
    }

    private static RiskCaseEventView event(CaseEvent event) {
        return new RiskCaseEventView(
                event.revision(),
                event.kind().name(),
                event.status().name(),
                event.level().name(),
                event.queue().name(),
                event.action().map(UUID::toString).orElse(null),
                event.alert().map(id -> id.value().toString()).orElse(null),
                event.assessment().map(id -> id.value().toString()).orElse(null),
                event.recordedAt().map(Instant::toString).orElse(null));
    }

    // ------------------------------------------------------------------ plumbing

    /** {@code work}, the queue's refusal of a read it cannot take as asked in the API's words. */
    private static <R> R asked(java.util.function.Supplier<R> work) {
        try {
            return work.get();
        } catch (Queues.QueueRequestInvalid invalid) {
            throw new ApiException(RiskErrorCode.QUEUE_REQUEST_INVALID, "A risk queue read was refused",
                    invalid.getMessage());
        }
    }

    /**
     * The row an opaque {@code cursor} of {@code collection} names (`P17-TSK-042`): the last row the page before served, as
     * {@code typed}'s identifier - the module resumes from that row's own key. A cursor this collection did not write, or one
     * whose identifier is not of the kind (not a UUID, or not the UUIDv7 every risk row is born with), is refused
     * {@code 422 api.ValidationFailed}; a well-formed one naming no row stays the module's {@code risk.QueueRequestInvalid}.
     */
    static <I> Optional<I> cursor(String collection, String cursor, java.util.function.Function<UUID, I> typed) {
        return OperatorCollection.key(collection, cursor, 1).map(key -> {
            try {
                return typed.apply(UUID.fromString(key.get(0)));
            } catch (IllegalArgumentException malformed) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    private static ApiException selfDealing() {
        return new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk read was refused",
                "a holder never reads a case or a queue of their own party, nor one whose subject the platform cannot"
                        + " place; nothing was served.");
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk record answers the request", "no such record.");
    }

    private static RiskAlertId alertId(String raw) {
        try {
            return RiskAlertId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static RiskCaseId caseId(String raw) {
        try {
            return RiskCaseId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk read runs inside a correlation scope"))
                .correlationId();
    }
}
