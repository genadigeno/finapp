package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The case queue and the case file - read models over {@code risk_case}, {@code risk_case_action},
 * {@code risk_case_event}, {@code risk_alert} and {@code risk_action}, no projection (`P13-TSK-018`; {@code risk V014}'s
 * {@code risk_case_queue}, {@code risk_case_open_queue}, {@code risk_alert_of_case}; {@code PHASE_13_PLAN.md} sections 9,
 * 11; {@code INV-RSK-08}; {@code DESIGN-018} D3-D8, D13 - named a worklist because {@link CaseQueue} is `-016`'s
 * routing vocabulary, which desk works a case).
 *
 * <ul>
 *   <li><strong>{@link #page}</strong> - one page on the caller's read-only snapshot: oldest first by the immutable key
 *       {@code (opened_at, id)}, of one {@link CaseStatus} or, unless named, every open one; optionally one
 *       {@link CaseKind}; after the case the cursor names; every case of the reader's own party - or whose party cannot
 *       be placed - withheld ({@link Queues}' scan). {@link #record(Connection, Page, Actor, CorrelationId)} appends
 *       {@code risk.CaseRead}, which the caller commits before it answers.</li>
 *   <li><strong>{@link #read}</strong> - one case's file on the snapshot: the case, the alerts attached to it, its
 *       investigator actions and history, its downstream actions ({@link RiskActions#ofCase}, `-017`'s), and every
 *       evidence reference it holds - <em>by reference only</em>: an assessment, a signal or a journal entry is named
 *       by its id and resolved by its own audited read, never embedded. A file of the reader's own party's subject, or
 *       one the platform cannot place, is refused ({@link RiskAssessments.SelfDealingRefused}, recorded {@code FAILED}
 *       by the caller). {@link #record(Connection, CaseFile, Actor, CorrelationId)} appends {@code risk.CaseRead}.</li>
 * </ul>
 *
 * Nothing here writes but the audit record; persons are in the rows and the desk withholds them; no report is ever part
 * of a file - its narrative is the AML desk's own permission, and the case's {@code REPORT_DRAFTED} act is withheld from
 * the file's investigator actions (`P13-TSK-021`, {@code DESIGN-021} D2), so the analyst never learns that one exists.
 */
public final class CaseWorklist {

    /** The audit target type of a page - the queue, its id the status read (or {@code OPEN_ANY}). */
    public static final String QUEUE_TARGET_TYPE = "risk_case_queue";

    /** The target id of a page of every open status. */
    public static final String OPEN_ANY = "OPEN_ANY";

    private static final String QUEUE_ACT = "case-queue-read";
    private static final String READ_ACT = "case-read";

    private final RiskCaseStore cases;
    private final RiskAlertStore alerts;
    private final RiskActions actions;
    private final RiskActingParty actingParties;
    private final RiskSubjectParties subjectParties;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public CaseWorklist(RiskCaseStore cases, RiskAlertStore alerts, RiskActions actions, RiskActingParty actingParties,
            RiskSubjectParties subjectParties, AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.subjectParties = Objects.requireNonNull(subjectParties, "subjectParties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ values

    /** What a page reads: one status (every open one unless named) and, when named, one kind. */
    public record Filter(Optional<CaseStatus> status, Optional<CaseKind> kind) {
        public Filter {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(kind, "kind");
        }

        /**
         * The filter {@code status} and {@code kind} name, exactly as the vocabularies spell them.
         *
         * @throws Queues.QueueRequestInvalid a name outside {@link CaseStatus} or {@link CaseKind}
         */
        public static Filter named(Optional<String> status, Optional<String> kind) {
            return new Filter(Queues.named(status, CaseStatus.class, "status"), Queues.named(kind, CaseKind.class,
                    "kind"));
        }

        /** The status read, as the audit's target names it. */
        public String target() {
            return status.map(Enum::name).orElse(OPEN_ANY);
        }
    }

    /**
     * One page: the cases served, oldest first; the cursor to the next page (the last case served - present only when
     * more remain); how many cases were withheld from the reader; and whether the scan's bound ended the page.
     */
    public record Page(Filter filter, Optional<RiskCaseId> after, List<RiskCase> cases, Optional<RiskCaseId> next,
            int withheld, boolean bounded) {
        public Page {
            Objects.requireNonNull(filter, "filter");
            Objects.requireNonNull(after, "after");
            cases = List.copyOf(cases);
            Objects.requireNonNull(next, "next");
        }
    }

    /** What an evidence reference names - resolved by its own audited read, never embedded. */
    public enum EvidenceKind {
        /** A sealed assessment - {@code GET /v1/operator/risk/assessments/{id}}, audited {@code risk.AssessmentRead}. */
        ASSESSMENT,
        /** A risk signal. */
        SIGNAL,
        /** A ledger journal entry - a monitored transaction's identity. */
        JOURNAL_ENTRY
    }

    /** One piece of evidence the case holds, by reference. */
    public record EvidenceRef(EvidenceKind kind, UUID id) {
        public EvidenceRef {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * One case's file: the case; the alerts attached to it, oldest first; its investigator actions, oldest first; its
     * history, revision 0 first; its downstream actions, oldest first; and every evidence reference it holds - its
     * opening assessment, its alerts' and its actions' references and the assessments its history names - each once, in
     * that order.
     */
    public record CaseFile(RiskCase riskCase, List<RiskAlert> alerts, List<CaseAction> actions, List<CaseEvent> history,
            List<RiskAction> riskActions, List<EvidenceRef> evidence) {
        public CaseFile {
            Objects.requireNonNull(riskCase, "riskCase");
            alerts = List.copyOf(alerts);
            actions = List.copyOf(actions);
            history = List.copyOf(history);
            riskActions = List.copyOf(riskActions);
            evidence = List.copyOf(evidence);
        }
    }

    // ------------------------------------------------------------------ the queue

    /**
     * One page of the case queue for {@code actor}, on the caller's read-only snapshot - nothing recorded here.
     *
     * @throws RiskAssessments.SelfDealingRefused the reader is placed in no party (fail closed)
     * @throws Queues.QueueRequestInvalid the cursor names no case
     * @throws IllegalArgumentException the reader is not a person
     */
    public Page page(Connection snapshot, Filter filter, Optional<RiskCaseId> after, int limit, Actor actor) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(after, "after");
        UUID own = Queues.reader(snapshot, actingParties, actor, QUEUE_ACT);
        if (after.isPresent() && cases.find(snapshot, after.get()).isEmpty()) {
            throw new Queues.QueueRequestInvalid("the cursor names no case; page again from the start");
        }
        Queues.Scanned<RiskCase, RiskCaseId> scanned = Queues.scan(limit, after,
                cursor -> cases.queue(snapshot, filter.status(), filter.kind(), cursor, own, limit + 1),
                RiskCase::id,
                riskCase -> Queues.visibleTo(snapshot, subjectParties, riskCase.subject(), own));
        return new Page(filter, after, scanned.served(), scanned.next(), scanned.withheld(), scanned.bounded());
    }

    /**
     * Records {@code page} served to {@code actor} - {@code risk.CaseRead} on {@code unitOfWork}, which the caller commits
     * before it answers. The summary the count, the filter, the cursor, the ids served, the withheld count and whether
     * more remain - never a subject or a note.
     */
    public void record(Connection unitOfWork, Page page, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), RiskAuditAction.CASE_READ,
                QUEUE_TARGET_TYPE, page.filter().target(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("page: " + page.cases().size() + " case(s), status=" + page.filter().target()
                        + ", kind=" + page.filter().kind().map(Enum::name).orElse("ANY")
                        + ", after=" + page.after().map(id -> id.value().toString()).orElse("none")
                        + ", ids=" + page.cases().stream().map(riskCase -> riskCase.id().value().toString())
                                .collect(Collectors.joining("|", "[", "]"))
                        + ", withheld=" + page.withheld() + ", more=" + page.next().isPresent()
                        + (page.bounded() ? ", bounded=true" : ""))));
    }

    /** Records a refused page {@code FAILED} ({@code risk.SelfDealingRefused}) - a transaction of the caller's own. */
    public void recordRefusal(Connection unitOfWork, Filter filter, RiskAssessments.SelfDealingRefused refused,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(filter, "filter");
        refused(unitOfWork, QUEUE_TARGET_TYPE, filter.target(), refused, actor, correlation);
    }

    // ------------------------------------------------------------------ the file

    /**
     * {@code caseId}'s file for {@code actor}, on the caller's read-only snapshot - nothing recorded here.
     *
     * @throws RiskAssessments.SelfDealingRefused the case's subject is the reader's own party's, or cannot be placed, or
     *     the reader is placed in no party (fail closed)
     * @throws Cases.CaseNotFound no such case
     * @throws IllegalArgumentException the reader is not a person
     */
    public CaseFile read(Connection snapshot, RiskCaseId caseId, Actor actor) {
        Objects.requireNonNull(caseId, "caseId");
        UUID own = Queues.reader(snapshot, actingParties, actor, READ_ACT);
        RiskCase riskCase = cases.find(snapshot, caseId).orElseThrow(Cases.CaseNotFound::new);
        Optional<UUID> subjectParty = Queues.accountableParty(snapshot, subjectParties, riskCase.subject());
        if (subjectParty.isEmpty()) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.SUBJECT_UNPLACEABLE,
                    READ_ACT);
        }
        if (subjectParty.get().equals(own)) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY, READ_ACT);
        }
        List<RiskAlert> attached = alerts.ofCase(snapshot, caseId);
        // The AML desk's REPORT_DRAFTED act is withheld (`P13-TSK-021`, DESIGN-021 D2; -018's routed rule): the analyst's
        // file never carries a report - not its narrative, its id, nor that one exists.
        List<CaseAction> investigated = cases.actions(snapshot, caseId).stream()
                .filter(action -> action.kind() != CaseActionKind.REPORT_DRAFTED).toList();
        List<CaseEvent> history = cases.events(snapshot, caseId);
        List<RiskAction> downstream = actions.ofCase(snapshot, caseId);
        return new CaseFile(riskCase, attached, investigated, history, downstream,
                evidence(riskCase, attached, investigated, history));
    }

    /**
     * Records {@code file} read by {@code actor} - {@code risk.CaseRead} on {@code unitOfWork}, which the caller commits
     * before it answers. Its target the case; the summary the kind, the status and the counts - never a note or a subject.
     */
    public void record(Connection unitOfWork, CaseFile file, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(correlation, "correlation");
        RiskCase riskCase = file.riskCase();
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), RiskAuditAction.CASE_READ,
                Cases.TARGET_TYPE, riskCase.id().value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED,
                correlation, Optional.of("kind=" + riskCase.kind().name() + ", status=" + riskCase.status().name()
                        + ", revision=" + riskCase.revision() + ", alerts=" + file.alerts().size() + ", actions="
                        + file.actions().size() + ", history=" + file.history().size() + ", riskActions="
                        + file.riskActions().size() + ", evidence=" + file.evidence().size())));
    }

    /** Records a refused file read {@code FAILED} ({@code risk.SelfDealingRefused}) - a transaction of the caller's own. */
    public void recordRefusal(Connection unitOfWork, RiskCaseId caseId, RiskAssessments.SelfDealingRefused refused,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(caseId, "caseId");
        refused(unitOfWork, Cases.TARGET_TYPE, caseId.value().toString(), refused, actor, correlation);
    }

    /**
     * The reader's own party, as {@link #read} places it (item 79, the Phase 13 gate): the evidence door judges each
     * reference it serves against it. Fails closed exactly as the file does.
     *
     * @throws RiskAssessments.SelfDealingRefused the reader is placed in no party
     */
    UUID readerParty(Connection snapshot, Actor actor) {
        return Queues.reader(snapshot, actingParties, actor, READ_ACT);
    }

    /**
     * Refuses {@code party} - one party a served reference names - when it cannot be placed or is the reader's
     * {@code own} (item 79): the same two refusals the file's subject meets.
     */
    static void judgeReferenceParty(Optional<UUID> party, UUID own) {
        if (party.isEmpty()) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.SUBJECT_UNPLACEABLE,
                    READ_ACT);
        }
        if (party.get().equals(own)) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY, READ_ACT);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Every reference the case holds, each once, in the order the file names them. */
    static List<EvidenceRef> evidence(RiskCase riskCase, List<RiskAlert> attached, List<CaseAction> investigated,
            List<CaseEvent> history) {
        Set<EvidenceRef> held = new LinkedHashSet<>();
        riskCase.openingAssessment().ifPresent(id -> held.add(new EvidenceRef(EvidenceKind.ASSESSMENT, id.value())));
        for (RiskAlert alert : attached) {
            add(held, alert.evidence());
        }
        for (CaseAction action : investigated) {
            add(held, action.evidence());
        }
        for (CaseEvent event : history) {
            event.assessment().ifPresent(id -> held.add(new EvidenceRef(EvidenceKind.ASSESSMENT, id.value())));
        }
        return List.copyOf(new ArrayList<>(held));
    }

    private static void add(Set<EvidenceRef> held, AlertEvidence evidence) {
        evidence.assessments().forEach(id -> held.add(new EvidenceRef(EvidenceKind.ASSESSMENT, id.value())));
        evidence.signals().forEach(id -> held.add(new EvidenceRef(EvidenceKind.SIGNAL, id.value())));
        evidence.journalEntries().forEach(id -> held.add(new EvidenceRef(EvidenceKind.JOURNAL_ENTRY, id)));
    }

    private void refused(Connection unitOfWork, String targetType, String target,
            RiskAssessments.SelfDealingRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, targetType, target, Optional.empty(), AuditOutcome.FAILED,
                correlation, Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }
}
