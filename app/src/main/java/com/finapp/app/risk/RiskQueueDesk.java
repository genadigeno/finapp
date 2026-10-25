package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AlertQueue;
import com.finapp.risk.CaseAction;
import com.finapp.risk.CaseEvent;
import com.finapp.risk.CaseWorklist;
import com.finapp.risk.Cases;
import com.finapp.risk.Queues;
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
 * reasons - {@code DATA_CLASSIFICATION.md}) - and never a person: who proposed, approved or took is withheld. Nothing
 * here is logged.
 */
public final class RiskQueueDesk {

    private final AlertQueue alerts;
    private final CaseWorklist cases;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskQueueDesk(AlertQueue alerts, CaseWorklist cases, RiskTransactions transactions, RiskFlowScope flow) {
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.cases = Objects.requireNonNull(cases, "cases");
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
     * reason code, the proposer's text and the decider's, and the database's stamps - never who.
     */
    public record RiskSuppressionView(String status, String reasonCode, String reason, String proposedAt,
            String decisionReason, String decidedAt) {}

    /** A page of the alert queue: the alerts, oldest first, and the cursor to the next page ({@code null} at the end). */
    public record RiskAlertPageView(List<RiskAlertView> alerts, String next) {}

    /** A page of the case queue: the cases' heads, oldest first, and the cursor to the next page. */
    public record RiskCasePageView(List<RiskCaseDesk.RiskCaseView> cases, String next) {}

    /**
     * One case's file: its head, the alerts attached to it, its investigator actions, its history, its downstream
     * actions and every evidence reference it holds - each a kind and an id, resolved by its own audited read.
     */
    public record RiskCaseFileView(RiskCaseDesk.RiskCaseView riskCase, List<RiskAlertView> alerts,
            List<RiskCaseActionView> actions, List<RiskCaseEventView> history,
            List<RiskActionDesk.RiskActionView> riskActions, List<RiskEvidenceView> evidence) {}

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

    /** A page of the alert queue, read and recorded. */
    public RiskAlertPageView alerts(String status, String kind, String after, Integer limit) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        AlertQueue.Filter filter = asked(() -> AlertQueue.Filter.named(Optional.ofNullable(status),
                Optional.ofNullable(kind)));
        int size = asked(() -> Queues.size(Optional.ofNullable(limit)));
        Optional<RiskAlertId> cursor = cursor(after, RiskAlertId::of);
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
                page.next().map(id -> id.value().toString()).orElse(null));
    }

    // ------------------------------------------------------------------ the case queue and the file

    /** A page of the case queue, read and recorded. */
    public RiskCasePageView cases(String status, String kind, String after, Integer limit) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        CaseWorklist.Filter filter = asked(() -> CaseWorklist.Filter.named(Optional.ofNullable(status),
                Optional.ofNullable(kind)));
        int size = asked(() -> Queues.size(Optional.ofNullable(limit)));
        Optional<RiskCaseId> cursor = cursor(after, RiskCaseId::of);
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
                page.next().map(id -> id.value().toString()).orElse(null));
    }

    /** {@code rawCaseId}'s file, read and recorded. */
    public RiskCaseFileView file(String rawCaseId) {
        RiskCaseId caseId = caseId(rawCaseId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        CaseWorklist.CaseFile file;
        try {
            file = flow.door(RiskSpans.CASE_READ, () -> transactions.inSnapshot(
                    snapshot -> cases.read(snapshot, caseId, actor)));
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
            cases.record(uow, file, actor, correlation);
            return null;
        });
        return view(file);
    }

    // ------------------------------------------------------------------ the views

    static RiskAlertView view(RiskAlert alert) {
        RiskSuppressionView suppression = alert.suppression().map(proposal -> new RiskSuppressionView(
                alert.decision().isPresent() ? "DECIDED" : "PROPOSED",
                proposal.reasonCode().name(),
                proposal.reason(),
                proposal.proposedAt().toString(),
                alert.decision().map(RiskAlert.Decision::reason).orElse(null),
                alert.decision().map(decision -> decision.decidedAt().toString()).orElse(null))).orElse(null);
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
        int revision = file.riskCase().revision();
        return new RiskCaseFileView(
                RiskCaseDesk.view(file.riskCase(), false),
                file.alerts().stream().map(RiskQueueDesk::view).toList(),
                file.actions().stream().map(RiskQueueDesk::action).toList(),
                file.history().stream().map(RiskQueueDesk::event).toList(),
                file.riskActions().stream().map(action -> RiskActionDesk.view(action, revision)).toList(),
                file.evidence().stream().map(ref -> new RiskEvidenceView(ref.kind().name(), ref.id().toString()))
                        .toList());
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
     * The cursor as {@code typed}'s identifier - one that is not an identifier of the kind (not a UUID, or not the
     * UUIDv7 every risk row is born with) names no row.
     */
    private static <I> Optional<I> cursor(String after, java.util.function.Function<UUID, I> typed) {
        if (after == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(typed.apply(UUID.fromString(after)));
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(RiskErrorCode.QUEUE_REQUEST_INVALID, "A risk queue read was refused",
                    "the cursor names no row of the queue; page again from the start");
        }
    }

    private static ApiException selfDealing() {
        return new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk read was refused",
                "a holder never reads a case or a queue of their own party, nor one whose subject the platform cannot"
                        + " place; nothing was served.");
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

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk read runs inside a correlation scope"))
                .correlationId();
    }
}
