package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The case, its acts and its openings - T-case (`P13-TSK-016`; {@code risk V012}; {@code PHASE_13_PLAN.md} sections
 * 3.1, 4, 5, 7.1, 7.2, 10, 11, 12.8; {@code RISK_LIFECYCLES.md} section 4.6; ADR-0110 decisions 2-7; {@code INV-RSK-06},
 * {@code INV-RSK-07}, {@code INV-RSK-08}, {@code INV-AUD-04}).
 *
 * <h2>The system's openings (S, on the caller's unit of work)</h2>
 *
 * <ul>
 *   <li><strong>{@link #fold}</strong> - `-015`'s {@link AlertFolding}, for an alert born in the raising transaction
 *       (T-eval's, the profile leg's) or queued before this task landed (the drain, {@link #drain}): (R4) the subject's
 *       open case of the alert's {@link AlertKind#caseKind} {@code FOR UPDATE} - else one opened under
 *       {@code risk_case_one_open} with the alert as its opening (a racer losing the unique finds the winner's row
 *       next) - then (R5) the alert {@code OPEN → ATTACHED} through {@link RiskAlertStore#attach} (the store, never
 *       {@link Alerts}: a folding holding {@code Alerts} would be a construction cycle), the case's revision advanced
 *       and its {@code ALERT_ATTACHED} step written. The system never suppresses: a duplicate attaches.</li>
 *   <li><strong>{@link #openFor}</strong> - `-004`'s {@link CaseOpener}, inside T-eval's money transaction after the
 *       seal and the alerts: the party's open case of the decision's kind ({@code ATO} when the sealed hits show the
 *       account-takeover anchors, else {@code FRAUD} - {@code DESIGN-016} D5) locked or opened, the assessment its
 *       opening or an {@code ASSESSMENT_ATTACHED} step. <strong>One ATO case</strong>: the anchor's alert folded first
 *       (the same transaction already holds the party's {@code ATO} case), so the opener attaches the assessment to
 *       that case, or opens it when the raise failed behind its savepoint. A failure here aborts the caller - fail
 *       closed: a {@code REVIEW} or {@code HOLD} never commits without its case.</li>
 * </ul>
 *
 * <p>The system's acts are recorded under the acting principal ({@link SecurityContext#require()}: T-eval's caller, a
 * leg's {@code SYSTEM} - never {@code Actor.SYSTEM} read directly): every opening audited {@code risk.CaseOpened} and
 * announced {@code risk.CaseOpened}; an attach is its history step only (`-015`'s rule: the system's dedupe is a fact,
 * not an audited act).
 *
 * <h2>A person's acts (P, each in a transaction of its own)</h2>
 *
 * <p>{@link #open} by hand, {@link #take}, {@link #note}, {@link #attachEvidence}, {@link #escalate},
 * {@link #proposeDisposition}, {@link #approveDisposition}, {@link #rejectDisposition}: a person - an authenticated
 * human, {@code CUSTOMER}-typed as the session establishes every identity, or {@code EMPLOYEE}; never the platform, a
 * service or a merchant - placed in a party (fail closed), then <strong>(R4) the case row {@code FOR UPDATE}</strong>, the subject's accountable party
 * placed and not the actor's ({@link RiskSubjectParties#accountablePartyOf}; fail closed), the case open, <strong>the
 * revision compared with the one the body carries</strong> ({@link CaseRevisionStale} - ADR-0110 decision 5: the lock
 * serialises, the revision stops a stale screen overwriting a newer act), the act's own rules, then one conditional step
 * ({@code revision + 1}), the action, the history row, the audit and the event - one transaction, so both of two
 * concurrent investigators' earlier acts are kept and the loser writes nothing.
 *
 * <p><strong>Four eyes</strong> ({@link DispositionRules}): a {@code CONFIRMED_*}, {@code REFERRED} or prohibiting
 * disposition is proposed on a taken case and approved by a second person - the proposer refused
 * {@link SelfApprovalRefused} (recorded {@code FAILED} by the caller in a transaction of its own, and
 * {@code risk_case_disposition_four_eyes} holding every writer alone); a one-person close is decided by its own
 * proposal, from {@code OPEN} too. <strong>{@code PROHIBITED} is never written here</strong>: an approved prohibiting
 * disposition is applied to the profile by {@link ProfileRecompute#applyDisposition} in a transaction of its own after
 * the case commits - the profile head (R8) is never taken under the case row (R4), the profile leg holding R8 before
 * its folds (`-013`'s order R8 → R4 → R5).
 *
 * <p>Nothing here logs; the events carry the case's id, kind, status and disposition code; the audit summaries codes
 * and counts - never a subject, a note or a reason ({@code INV-RSK-08}).
 */
public final class Cases {

    public static final String OPENED_EVENT = "risk.CaseOpened";
    public static final String ESCALATED_EVENT = "risk.CaseEscalated";
    public static final String DISPOSED_EVENT = "risk.CaseDisposed";
    public static final String PRODUCER = "risk";
    public static final int EVENT_VERSION = 1;
    public static final String AGGREGATE_TYPE = "risk_case";

    /** The audit target type of every act on a case. */
    public static final String TARGET_TYPE = "risk_case";

    /** How often an opening re-judges after losing the one-open unique to a case disposed before it could lock it. */
    static final int OPEN_ATTEMPTS = 3;

    private final RiskCaseStore store;
    private final RiskAlertStore alerts;
    private final RiskActingParty actingParties;
    private final RiskSubjectParties subjectParties;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final CaseObserver observer;

    public Cases(RiskCaseStore store, RiskAlertStore alerts, RiskActingParty actingParties,
            RiskSubjectParties subjectParties, AuditWriter<Connection> audit, OutboxWriter<Connection> outbox,
            IdGenerator ids, Clock clock, CaseObserver observer) {
        this.store = Objects.requireNonNull(store, "store");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.subjectParties = Objects.requireNonNull(subjectParties, "subjectParties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    // ------------------------------------------------------------------ outcomes

    /** An opening's answer: the case, and whether this call opened it (false: the subject's standing case). */
    public record Opened(RiskCase riskCase, boolean opened) {
        public Opened {
            Objects.requireNonNull(riskCase, "riskCase");
        }
    }

    /** A person's act's answer: the case after it; {@code converged} when nothing needed writing. */
    public record Acted(RiskCase riskCase, boolean converged) {
        public Acted {
            Objects.requireNonNull(riskCase, "riskCase");
        }
    }

    /**
     * A disposition act's answer: the case after it, the disposition's id, and {@code replayed} when the same approver's
     * retry converged on the disposition they had approved.
     */
    public record Decided(RiskCase riskCase, UUID dispositionId, boolean replayed) {
        public Decided {
            Objects.requireNonNull(riskCase, "riskCase");
            Objects.requireNonNull(dispositionId, "dispositionId");
        }

        /** True when the case is disposed by an approved disposition that sets {@code PROHIBITED}. */
        public boolean prohibits() {
            return riskCase.status() == CaseStatus.DISPOSED
                    && riskCase.disposition().filter(RiskCase.DispositionSlot::prohibits).isPresent();
        }
    }

    /** What a person opens a case on: an open alert, or a kind and a subject. */
    public record OpenRequest(Optional<RiskAlertId> alert, Optional<CaseKind> kind, Optional<SignalSubject> subject) {
        public OpenRequest {
            Objects.requireNonNull(alert, "alert");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subject, "subject");
        }

        public static OpenRequest fromAlert(RiskAlertId alert) {
            return new OpenRequest(Optional.of(Objects.requireNonNull(alert, "alert")), Optional.empty(),
                    Optional.empty());
        }

        public static OpenRequest of(CaseKind kind, SignalSubject subject) {
            return new OpenRequest(Optional.empty(), Optional.of(Objects.requireNonNull(kind, "kind")),
                    Optional.of(Objects.requireNonNull(subject, "subject")));
        }
    }

    // ------------------------------------------------------------------ refusals

    /** No case answers the request ({@code 404 risk.NotFound}). */
    public static final class CaseNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CaseNotFound() {
            super("no risk record answers the request");
        }
    }

    /** The body's revision is not the case's ({@code 409 risk.CaseRevisionStale}) - nothing was written. */
    public static final class CaseRevisionStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CaseRevisionStale() {
            super("the case changed since it was read; read it again and resend with its current revision");
        }
    }

    /** The case is disposed ({@code 409 risk.CaseNotOpen}) - a case never re-opens. */
    public static final class CaseNotOpen extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CaseNotOpen() {
            super("the case is disposed; a new case may reference it");
        }
    }

    /** The case's state does not admit the act now ({@code 409 risk.CaseActNotAdmitted}). */
    public static final class CaseActNotAdmitted extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CaseActNotAdmitted(String detail) {
            super(detail);
        }
    }

    /** The act needs the case taken first ({@code 422 risk.CaseNotTaken}). */
    public static final class CaseNotTaken extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CaseNotTaken(String detail) {
            super(detail);
        }
    }

    /** The act is not admitted as asked ({@code 422 risk.CaseRequestInvalid}) - nothing was done. */
    public static final class CaseRequestInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CaseRequestInvalid(String detail) {
            super(detail);
        }
    }

    /** The proposer tried to approve their own disposition ({@code 403 risk.SelfApprovalRefused}; {@code INV-RSK-07}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(RiskCase riskCase, RiskCase.DispositionSlot slot) {
            super("a " + slot.disposition() + " disposition is approved by a second RISK_CASE_APPROVE holder; the"
                    + " proposer may reject it");
            this.target = riskCase.id().value().toString();
            this.summary = "case=" + riskCase.id().value() + ", kind=" + riskCase.kind().name() + ", disposition="
                    + slot.disposition().name() + ", act=disposition:approve";
        }

        /** The case whose disposition the approver proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the case, its kind, the disposition and the act - never the subject. */
        public String summary() {
            return summary;
        }
    }

    // ================================================================== the system's openings (S)

    /**
     * Folds {@code born} - an {@code OPEN} alert of a kind that opens a case - into its subject's open case of the kind,
     * or a case opened for it: `-015`'s {@link AlertFolding}, on the caller's unit of work, (R4) then (R5).
     *
     * @throws IllegalArgumentException an operational alert - it opens no case
     * @throws Alerts.AlertNotOpen the alert was attached to another case or suppressed meanwhile (the drain's race) -
     *     the caller rolls back what this call wrote
     */
    public AlertFolding.Folded fold(Connection unitOfWork, RiskAlert born) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(born, "born");
        CaseKind kind = born.kind().caseKind().orElseThrow(() -> new IllegalArgumentException(
                "an operational alert opens no case and folds into none"));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = CorrelationId.of(born.correlationId());
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            Optional<RiskCase> open = store.lockOpen(unitOfWork, born.subject(), kind);
            if (open.isPresent()) {
                return attachedTo(unitOfWork, open.get(), born, actor, correlation);
            }
            Optional<RiskCase> opened = born(unitOfWork, kind, born.subject(), Optional.of(born.id()),
                    Optional.empty(), actor, correlation, "alert");
            if (opened.isPresent()) {
                attachEdge(unitOfWork, born.id(), opened.get().id());
                return AlertFolding.Folded.attached(opened.get().id());
            }
            // A racer opened the subject's case first: the next lockOpen finds it (a fresh statement sees its commit).
        }
        throw new IllegalStateException("the subject's open case of the kind kept being disposed under the folding");
    }

    /**
     * Opens - or joins - the case {@code decided} calls for: `-004`'s {@link CaseOpener}, inside T-eval's transaction.
     * The assessment is the case's opening, or attached to the party's open case of the kind as a step of its own.
     */
    public Opened openFor(Connection unitOfWork, CaseOpener.Decided decided) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(decided, "decided");
        SignalSubject subject = SignalSubject.of(SubjectKind.PARTY, decided.party());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = decided.correlation();
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            Optional<RiskCase> open = store.lockOpen(unitOfWork, subject, decided.kind());
            if (open.isPresent()) {
                RiskCase found = open.get();
                if (found.openingAssessment().filter(decided.assessment()::equals).isPresent()) {
                    return new Opened(found, false);
                }
                RiskCase attached = stepped(unitOfWork, found, RiskCaseStore.Step.of(found));
                store.appendEvent(unitOfWork, event(attached, CaseEventKind.ASSESSMENT_ATTACHED, actor, Optional.empty(),
                        Optional.empty(), Optional.of(decided.assessment()), correlation));
                return new Opened(attached, false);
            }
            Optional<RiskCase> opened = born(unitOfWork, decided.kind(), subject, Optional.empty(),
                    Optional.of(decided.assessment()), actor, correlation, "assessment");
            if (opened.isPresent()) {
                return new Opened(opened.get(), true);
            }
        }
        throw new IllegalStateException("the party's open case of the kind kept being disposed under the opening");
    }

    /**
     * The drain's step (`DESIGN-016` D7), in a transaction of the caller's own: {@code alertId} folded when it is still
     * {@code OPEN}, of a case-opening kind, with no suppression proposal standing - else nothing is written and the
     * answer is {@link AlertFolding.Folded#QUEUED}.
     */
    public AlertFolding.Folded drain(Connection unitOfWork, RiskAlertId alertId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(alertId, "alertId");
        Optional<RiskAlert> queued = alerts.find(unitOfWork, alertId);
        if (queued.isEmpty() || !queued.get().open() || queued.get().kind().operational()
                || queued.get().suppression().isPresent()) {
            return AlertFolding.Folded.QUEUED;
        }
        return fold(unitOfWork, queued.get());
    }

    /** Up to {@code limit} alerts born {@code OPEN} and still waiting for a case, oldest first - the drain's backlog. */
    public List<RiskAlert> queued(Connection unitOfWork, int limit) {
        return alerts.queued(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit);
    }

    /** Up to {@code limit} approved prohibiting dispositions whose profile version is not yet written, oldest first. */
    public List<RiskCaseStore.PendingProhibition> prohibitionsDue(Connection unitOfWork, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a re-drive reads at least one disposition: " + limit);
        }
        return store.prohibitionsDue(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit);
    }

    /** {@code caseId}'s pending prohibition, if its approved disposition sets {@code PROHIBITED} - the door's follow-up. */
    public Optional<RiskCaseStore.PendingProhibition> prohibitionOf(Connection unitOfWork, RiskCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.find(unitOfWork, Objects.requireNonNull(caseId, "caseId"))
                .filter(found -> found.status() == CaseStatus.DISPOSED)
                .flatMap(found -> found.disposition().filter(RiskCase.DispositionSlot::prohibits)
                        .map(slot -> new RiskCaseStore.PendingProhibition(found.id(),
                                UUID.fromString(found.subject().ref()), slot.id())));
    }

    // ================================================================== a person's acts (P)

    /**
     * Opens a case by hand - on an open alert (its kind's case, its subject) or on a kind and a subject - or answers the
     * subject's standing case of the kind (an open alert attached to it). Audited {@code risk.CaseOpened} with the
     * reason when opened.
     *
     * @throws RiskReasons.ReasonRequired a missing, unbounded or instrument-bearing reason
     * @throws RiskAssessments.SelfDealingRefused the operator's own party's subject, or an operator or subject placed in
     *     no party
     * @throws CaseNotFound no such alert
     * @throws CaseRequestInvalid an operational alert; {@code COLLECTIONS}; a subject the kind does not take
     * @throws Alerts.AlertNotOpen the alert is suppressed, or attached to a case that is disposed
     */
    public Opened open(Connection unitOfWork, OpenRequest request, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        UUID own = placed(unitOfWork, actor, "case-open");
        if (request.alert().isPresent()) {
            return openFromAlert(unitOfWork, request.alert().get(), reasoned, actor, own, correlation);
        }
        CaseKind kind = request.kind().orElseThrow(() -> new CaseRequestInvalid("a case is opened on an alert, or on"
                + " a kind and a subject"));
        SignalSubject subject = request.subject().orElseThrow(() -> new CaseRequestInvalid("a case is opened on an"
                + " alert, or on a kind and a subject"));
        if (!admittedByHand(kind, subject.kind())) {
            throw new CaseRequestInvalid("a " + kind + " case is opened by hand on "
                    + (kind == CaseKind.MERCHANT_ABUSE ? "a MERCHANT" : "a PARTY")
                    + (kind == CaseKind.COLLECTIONS ? " - never: a collections case opens on a default (-025)" : ""));
        }
        notOwn(unitOfWork, subject, own, "case-open");
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            Optional<RiskCase> open = store.lockOpen(unitOfWork, subject, kind);
            if (open.isPresent()) {
                return new Opened(open.get(), false);
            }
            Optional<RiskCase> opened = born(unitOfWork, kind, subject, Optional.empty(), Optional.empty(), actor,
                    correlation, "hand", Optional.of(reasoned));
            if (opened.isPresent()) {
                return new Opened(opened.get(), true);
            }
        }
        throw new IllegalStateException("the subject's open case of the kind kept being disposed under the opening");
    }

    /**
     * Takes {@code caseId} - {@code OPEN → IN_INVESTIGATION} - or takes it over from another investigator; the current
     * investigator's own take converges with nothing written. Audited {@code risk.CaseTaken}.
     */
    public Acted take(Connection unitOfWork, RiskCaseId caseId, int revision, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        RiskCase locked = judged(unitOfWork, caseId, actor, "case-take");
        locked.judgeOpen();
        locked.judgeRevision(revision);
        if (locked.takenBy(actor)) {
            return new Acted(locked, true);
        }
        RiskCase.Person investigator = RiskCase.Person.of(actor);
        RiskCase taken = written(unitOfWork, locked,
                RiskCaseStore.Step.of(locked).withStatus(locked.afterTake()).takenBy(investigator), ids.next(),
                CaseActionKind.TAKEN, CaseEventKind.TAKEN, actor, Optional.empty(), AlertEvidence.NONE,
                Optional.empty(), false, Optional.empty(), correlation);
        append(unitOfWork, actor, RiskAuditAction.CASE_TAKEN, taken, Optional.empty(),
                "kind=" + taken.kind().name() + ", status=" + taken.status().name() + ", over="
                        + locked.taken(),
                correlation);
        if (taken.status() != locked.status()) {
            observer.moved(taken.kind(), taken.status());
        }
        return new Acted(taken, false);
    }

    /** Notes an open case with a screened text. Audited {@code risk.CaseNoted}. */
    public Acted note(Connection unitOfWork, RiskCaseId caseId, int revision, String text, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(text);
        RiskCase locked = judged(unitOfWork, caseId, actor, "case-note");
        locked.judgeOpen();
        locked.judgeRevision(revision);
        RiskCase noted = written(unitOfWork, locked, RiskCaseStore.Step.of(locked), ids.next(), CaseActionKind.NOTE,
                CaseEventKind.NOTED, actor, Optional.of(reasoned), AlertEvidence.NONE, Optional.empty(), false,
                Optional.empty(), correlation);
        append(unitOfWork, actor, RiskAuditAction.CASE_NOTED, noted, Optional.of(reasoned),
                "kind=" + noted.kind().name() + ", act=note", correlation);
        return new Acted(noted, false);
    }

    /**
     * Attaches evidence to an open case by reference, with a screened text saying why. Audited {@code risk.CaseNoted}.
     *
     * @throws CaseRequestInvalid no reference at all
     */
    public Acted attachEvidence(Connection unitOfWork, RiskCaseId caseId, int revision, AlertEvidence evidence,
            String text, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(correlation, "correlation");
        if (evidence.isEmpty()) {
            throw new CaseRequestInvalid("evidence is attached by at least one signal, assessment or journal entry"
                    + " reference");
        }
        String reasoned = RiskReasons.reasoned(text);
        RiskCase locked = judged(unitOfWork, caseId, actor, "case-evidence");
        locked.judgeOpen();
        locked.judgeRevision(revision);
        // The action's text is the reason the evidence was attached; the references themselves are the act.
        RiskCase attached = written(unitOfWork, locked, RiskCaseStore.Step.of(locked), ids.next(),
                CaseActionKind.EVIDENCE_ATTACHED, CaseEventKind.EVIDENCE_ATTACHED, actor, Optional.of(reasoned),
                evidence, Optional.empty(), false, Optional.empty(), correlation);
        append(unitOfWork, actor, RiskAuditAction.CASE_NOTED, attached, Optional.of(reasoned),
                "kind=" + attached.kind().name() + ", act=evidence, signals=" + evidence.signals().size()
                        + ", assessments=" + evidence.assessments().size() + ", journalEntries="
                        + evidence.journalEntries().size(),
                correlation);
        return new Acted(attached, false);
    }

    /**
     * Escalates a taken case with a reason to {@code queue}, one level up. Audited and announced
     * {@code risk.CaseEscalated}.
     */
    public Acted escalate(Connection unitOfWork, RiskCaseId caseId, int revision, String queue, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        CaseQueue target = CaseQueue.named(queue).orElseThrow(() -> new CaseRequestInvalid(
                "an escalation names one of the queues " + Arrays.toString(CaseQueue.values())));
        String reasoned = RiskReasons.reasoned(reason);
        RiskCase locked = judged(unitOfWork, caseId, actor, "case-escalate");
        locked.judgeOpen();
        locked.judgeRevision(revision);
        CaseLevel raised = locked.judgeEscalation(target);
        RiskCase escalated = written(unitOfWork, locked, RiskCaseStore.Step.of(locked).escalated(raised, target),
                ids.next(), CaseActionKind.ESCALATED, CaseEventKind.ESCALATED, actor, Optional.of(reasoned),
                AlertEvidence.NONE, Optional.empty(), false, Optional.of(target), correlation);
        append(unitOfWork, actor, RiskAuditAction.CASE_ESCALATED, escalated, Optional.of(reasoned),
                "kind=" + escalated.kind().name() + ", level=" + raised.name() + ", queue=" + target.name(),
                correlation);
        announce(unitOfWork, ESCALATED_EVENT, escalated, Optional.empty(), correlation);
        if (locked.status() != CaseStatus.ESCALATED) {
            observer.moved(escalated.kind(), CaseStatus.ESCALATED);
        }
        return new Acted(escalated, false);
    }

    /**
     * Proposes {@code caseId}'s disposition: a one-person close ({@code CLOSED_FALSE_POSITIVE},
     * {@code CLOSED_NO_ACTION}, prohibiting nothing) is decided by this act - the case {@code DISPOSED}, from
     * {@code OPEN} too; a two persons' one ({@code CONFIRMED_*}, {@code REFERRED}, or {@code prohibit}) needs the case
     * taken and stands for a second person. Audited {@code risk.DispositionProposed} (and {@code risk.CaseDisposed}).
     *
     * @throws CaseRequestInvalid a disposition outside the vocabulary; {@code prohibit} of a non-confirming disposition
     *     or a subject that is no party
     * @throws CaseNotTaken a two persons' disposition on an untaken case
     * @throws CaseActNotAdmitted a proposal already stands
     */
    public Decided proposeDisposition(Connection unitOfWork, RiskCaseId caseId, int revision, String disposition,
            boolean prohibit, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        Disposition code = Arrays.stream(Disposition.values()).filter(value -> value.name().equals(disposition))
                .findFirst().orElseThrow(() -> new CaseRequestInvalid("a disposition is one of "
                        + Arrays.toString(Disposition.values())));
        String reasoned = RiskReasons.reasoned(reason);
        RiskCase locked = judged(unitOfWork, caseId, actor, "disposition-propose");
        locked.judgeOpen();
        locked.judgeRevision(revision);
        locked.judgeProposal(code, prohibit);
        boolean secondPerson = DispositionRules.needsSecondPerson(code, prohibit);
        UUID dispositionId = ids.next();
        RiskCase.Person proposer = RiskCase.Person.of(actor);
        RiskCase.DispositionSlot slot = new RiskCase.DispositionSlot(dispositionId, code, prohibit, proposer,
                Optional.empty(), secondPerson ? Optional.empty() : Optional.of(proposer), Optional.empty());
        RiskCaseStore.Step step = RiskCaseStore.Step.of(locked).withDisposition(Optional.of(slot));
        // The proposal's action first: its id is the disposition's, which the slot's foreign key names.
        RiskCase after = written(unitOfWork, locked, secondPerson ? step : step.withStatus(CaseStatus.DISPOSED),
                dispositionId, CaseActionKind.DISPOSITION_PROPOSED,
                secondPerson ? CaseEventKind.DISPOSITION_PROPOSED : CaseEventKind.DISPOSED, actor,
                Optional.of(reasoned), AlertEvidence.NONE, Optional.of(code), prohibit, Optional.empty(), correlation);
        append(unitOfWork, actor, RiskAuditAction.DISPOSITION_PROPOSED, after, Optional.of(reasoned),
                "kind=" + after.kind().name() + ", disposition=" + code.name() + ", secondPerson="
                        + (secondPerson ? "REQUIRED" : "NOT_REQUIRED") + ", prohibits=" + prohibit,
                correlation);
        if (!secondPerson) {
            disposed(unitOfWork, after, actor, correlation);
        }
        return new Decided(after, dispositionId, false);
    }

    /**
     * Approves the standing disposition {@code dispositionId} of {@code caseId} by a second person - the case
     * {@code DISPOSED}. The same approver's retry converges. {@code PROHIBITED} is not written here: the caller applies
     * it after this transaction commits ({@link ProfileRecompute#applyDisposition}).
     *
     * @throws CaseNotOpen the case is disposed by another decision
     * @throws CaseActNotAdmitted the disposition named is not the standing one
     * @throws SelfApprovalRefused the approver proposed it
     */
    public Decided approveDisposition(Connection unitOfWork, RiskCaseId caseId, UUID dispositionId, int revision,
            String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(dispositionId, "dispositionId");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RiskCase locked = judged(unitOfWork, caseId, actor, "disposition-approve");
        if (!locked.open()) {
            RiskCase.DispositionSlot decided = locked.disposition().orElseThrow();
            if (decided.id().equals(dispositionId) && decided.approvedBy().filter(person -> person.is(actor))
                    .isPresent() && !decided.proposedBy().is(actor)) {
                return new Decided(locked, dispositionId, true);
            }
            throw new CaseNotOpen();
        }
        locked.judgeRevision(revision);
        RiskCase.DispositionSlot proposal = locked.judgeDecision(dispositionId);
        if (proposal.proposedBy().is(actor)) {
            throw new SelfApprovalRefused(locked, proposal);
        }
        RiskCase.DispositionSlot approved = new RiskCase.DispositionSlot(proposal.id(), proposal.disposition(),
                proposal.prohibits(), proposal.proposedBy(), proposal.proposedAt(), Optional.of(RiskCase.Person.of(
                        actor)), Optional.empty());
        RiskCase after = written(unitOfWork, locked, RiskCaseStore.Step.of(locked).withStatus(CaseStatus.DISPOSED)
                .withDisposition(Optional.of(approved)), ids.next(), CaseActionKind.DISPOSITION_APPROVED,
                CaseEventKind.DISPOSED, actor, Optional.of(reasoned), AlertEvidence.NONE, Optional.empty(), false,
                Optional.empty(), correlation);
        append(unitOfWork, actor, RiskAuditAction.DISPOSITION_APPROVED, after, Optional.of(reasoned),
                "kind=" + after.kind().name() + ", disposition=" + proposal.disposition().name() + ", prohibits="
                        + proposal.prohibits(),
                correlation);
        disposed(unitOfWork, after, actor, correlation);
        return new Decided(after, dispositionId, false);
    }

    /**
     * Rejects the standing disposition {@code dispositionId} - anyone, the proposer's withdrawal included: the slot
     * cleared, the case open. Audited {@code risk.DispositionRejected}.
     */
    public Decided rejectDisposition(Connection unitOfWork, RiskCaseId caseId, UUID dispositionId, int revision,
            String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(dispositionId, "dispositionId");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RiskCase locked = judged(unitOfWork, caseId, actor, "disposition-reject");
        locked.judgeOpen();
        locked.judgeRevision(revision);
        RiskCase.DispositionSlot proposal = locked.judgeDecision(dispositionId);
        RiskCase after = written(unitOfWork, locked, RiskCaseStore.Step.of(locked).withDisposition(Optional.empty()),
                ids.next(), CaseActionKind.DISPOSITION_REJECTED, CaseEventKind.DISPOSITION_REJECTED, actor,
                Optional.of(reasoned), AlertEvidence.NONE, Optional.empty(), false, Optional.empty(), correlation);
        append(unitOfWork, actor, RiskAuditAction.DISPOSITION_REJECTED, after, Optional.of(reasoned),
                "kind=" + after.kind().name() + ", disposition=" + proposal.disposition().name() + ", byProposer="
                        + proposal.proposedBy().is(actor),
                correlation);
        return new Decided(after, dispositionId, false);
    }

    /** {@code caseId} as it stands now - the receipt of the act its caller has just performed; no lock, no audit. */
    public Optional<RiskCase> current(Connection unitOfWork, RiskCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.find(unitOfWork, Objects.requireNonNull(caseId, "caseId"));
    }

    // ------------------------------------------------------------------ the refusals, recorded

    /**
     * Records a refused self-approval {@code FAILED} ({@code risk.SelfApprovalRefused}) on {@code unitOfWork} - the
     * caller's own transaction, after the act rolled back, so the attempt is kept ({@code INV-RSK-07}).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(),
                refused.summary(), correlation);
    }

    /**
     * Records a refused act {@code FAILED} ({@code risk.SelfDealingRefused}) on {@code unitOfWork} - a transaction of its
     * own, after the act was abandoned. Its target the case (or, for an opening, the alert or {@code new}); the summary
     * the act and the cause - never the party.
     */
    public void recordRefusal(Connection unitOfWork, String target, RiskAssessments.SelfDealingRefused refused,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(refused, "refused");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, TARGET_TYPE, target, Optional.empty(), AuditOutcome.FAILED,
                correlation, Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }

    // ================================================================== plumbing

    private Opened openFromAlert(Connection unitOfWork, RiskAlertId alertId, String reasoned, Actor actor, UUID own,
            CorrelationId correlation) {
        RiskAlert alert = alerts.find(unitOfWork, alertId).orElseThrow(CaseNotFound::new);
        CaseKind kind = alert.kind().caseKind().orElseThrow(() -> new CaseRequestInvalid(
                "an operational alert opens no case"));
        notOwn(unitOfWork, alert.subject(), own, "case-open");
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            Optional<RiskCase> open = store.lockOpen(unitOfWork, alert.subject(), kind);
            RiskAlert now = alerts.find(unitOfWork, alertId).orElseThrow(CaseNotFound::new);
            if (now.status() == AlertStatus.ATTACHED) {
                // Already in a case: that case is the answer while it is open - a disposed one is never re-opened.
                if (open.isPresent() && now.attachedCase().filter(open.get().id()::equals).isPresent()) {
                    return new Opened(open.get(), false);
                }
                throw new Alerts.AlertNotOpen(now.status());
            }
            if (now.status() != AlertStatus.OPEN) {
                throw new Alerts.AlertNotOpen(now.status());
            }
            if (open.isPresent()) {
                attachedTo(unitOfWork, open.get(), now, actor, correlation);
                return new Opened(store.find(unitOfWork, open.get().id()).orElseThrow(), false);
            }
            Optional<RiskCase> opened = born(unitOfWork, kind, now.subject(), Optional.of(alertId), Optional.empty(),
                    actor, correlation, "hand", Optional.of(reasoned));
            if (opened.isPresent()) {
                attachEdge(unitOfWork, alertId, opened.get().id());
                return new Opened(opened.get(), true);
            }
        }
        throw new IllegalStateException("the subject's open case of the kind kept being disposed under the opening");
    }

    /** {@code alert} attached to the locked {@code target}: the edge, then the step and its history row. */
    private AlertFolding.Folded attachedTo(Connection unitOfWork, RiskCase target, RiskAlert alert, Actor actor,
            CorrelationId correlation) {
        Optional<RiskAlert> attached = alerts.attach(unitOfWork, alert.id(), target.id());
        if (attached.isEmpty()) {
            RiskAlert now = alerts.find(unitOfWork, alert.id()).orElseThrow(() -> new IllegalStateException(
                    "an alert being folded is not found"));
            if (now.status() == AlertStatus.ATTACHED && now.attachedCase().filter(target.id()::equals).isPresent()) {
                // Another fold attached it to this very case first (the drain's race): nothing to write.
                return AlertFolding.Folded.attached(target.id());
            }
            throw new Alerts.AlertNotOpen(now.status());
        }
        RiskCase stepped = stepped(unitOfWork, target, RiskCaseStore.Step.of(target));
        store.appendEvent(unitOfWork, event(stepped, CaseEventKind.ALERT_ATTACHED, actor, Optional.empty(),
                Optional.of(alert.id()), Optional.empty(), correlation));
        return AlertFolding.Folded.attached(target.id());
    }

    /** The alert's own edge onto a case this transaction just opened for it. */
    private void attachEdge(Connection unitOfWork, RiskAlertId alertId, RiskCaseId caseId) {
        if (alerts.attach(unitOfWork, alertId, caseId).isEmpty()) {
            RiskAlert now = alerts.find(unitOfWork, alertId).orElseThrow(() -> new IllegalStateException(
                    "an alert being folded is not found"));
            throw new Alerts.AlertNotOpen(now.status());
        }
    }

    private Optional<RiskCase> born(Connection unitOfWork, CaseKind kind, SignalSubject subject,
            Optional<RiskAlertId> alert, Optional<RiskAssessmentId> assessment, Actor actor, CorrelationId correlation,
            String from) {
        return born(unitOfWork, kind, subject, alert, assessment, actor, correlation, from, Optional.empty());
    }

    /**
     * The case born under the one-open unique with its opening history row, audited and announced - or empty when a
     * racer's open case of the kind already stands (nothing written).
     */
    private Optional<RiskCase> born(Connection unitOfWork, CaseKind kind, SignalSubject subject,
            Optional<RiskAlertId> alert, Optional<RiskAssessmentId> assessment, Actor actor, CorrelationId correlation,
            String from, Optional<String> reason) {
        Optional<RiskCaseId> predecessor = store.latestDisposed(unitOfWork, subject, kind);
        Optional<RiskCase> inserted = store.insert(unitOfWork, new RiskCaseStore.NewCase(RiskCaseId.next(ids), kind,
                subject, SlaDeadlines.hours(kind), alert, assessment, predecessor, correlation.value()));
        if (inserted.isEmpty()) {
            return Optional.empty();
        }
        RiskCase opened = inserted.get();
        store.appendEvent(unitOfWork, event(opened, CaseEventKind.OPENED, actor, Optional.empty(), alert, assessment,
                correlation));
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), RiskAuditAction.CASE_OPENED,
                TARGET_TYPE, opened.id().value().toString(), reason, AuditOutcome.SUCCEEDED, correlation,
                Optional.of("kind=" + kind.name() + ", from=" + from + ", predecessor="
                        + (predecessor.isPresent() ? "PRESENT" : "NONE"))));
        announce(unitOfWork, OPENED_EVENT, opened, Optional.empty(), correlation);
        observer.moved(kind, CaseStatus.OPEN);
        return Optional.of(opened);
    }

    /** The step from {@code from}'s revision, conditional on it - the lock holder's; anything else is a defect. */
    private RiskCase stepped(Connection unitOfWork, RiskCase from, RiskCaseStore.Step step) {
        return store.advance(unitOfWork, from.id(), from.revision(), step).orElseThrow(() -> new IllegalStateException(
                "the locked case moved under another writer: the FOR UPDATE protocol was bypassed"));
    }

    /**
     * A person's act: its action first (ordinal = the revision the step will reach - the slot's foreign key may name
     * it), then the conditional step, then the history row at that revision.
     */
    private RiskCase written(Connection unitOfWork, RiskCase locked, RiskCaseStore.Step step, UUID actionId,
            CaseActionKind kind, CaseEventKind eventKind, Actor actor, Optional<String> text, AlertEvidence evidence,
            Optional<Disposition> disposition, boolean prohibits, Optional<CaseQueue> queue,
            CorrelationId correlation) {
        CaseAction action = new CaseAction(actionId, locked.id(), locked.revision() + 1, kind,
                RiskCase.Person.of(actor), text, evidence, disposition, prohibits, queue, correlation.value(),
                Optional.empty());
        store.appendAction(unitOfWork, action);
        RiskCase after = stepped(unitOfWork, locked, step);
        store.appendEvent(unitOfWork, event(after, eventKind, actor, Optional.of(action.id()), Optional.empty(),
                Optional.empty(), correlation));
        return after;
    }

    private static CaseEvent event(RiskCase after, CaseEventKind kind, Actor actor, Optional<UUID> action,
            Optional<RiskAlertId> alert, Optional<RiskAssessmentId> assessment, CorrelationId correlation) {
        return new CaseEvent(after.id(), after.revision(), kind, after.status(), after.level(), after.queue(),
                RiskCase.Person.of(actor), action, alert, assessment, correlation.value(), Optional.empty());
    }

    /** The disposal's audit, event and meters - after the step that disposed it. */
    private void disposed(Connection unitOfWork, RiskCase after, Actor actor, CorrelationId correlation) {
        RiskCase.DispositionSlot slot = after.disposition().orElseThrow();
        append(unitOfWork, actor, RiskAuditAction.CASE_DISPOSED, after, Optional.empty(),
                "kind=" + after.kind().name() + ", disposition=" + slot.disposition().name() + ", prohibits="
                        + slot.prohibits(),
                correlation);
        announce(unitOfWork, DISPOSED_EVENT, after, Optional.of(slot.disposition()), correlation);
        observer.moved(after.kind(), CaseStatus.DISPOSED);
        Instant disposedAt = slot.disposedAt().orElse(after.openedAt());
        observer.resolved(after.kind(), Duration.between(after.openedAt(), disposedAt).isNegative() ? Duration.ZERO
                : Duration.between(after.openedAt(), disposedAt));
    }

    /** A person ({@link #person}) placed in a party - fail closed - before anything is read. */
    private UUID placed(Connection unitOfWork, Actor actor, String act) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(actor, "actor");
        if (!person(actor)) {
            throw new IllegalArgumentException("a case act is a person's - never the platform's, a service's or a"
                    + " merchant's");
        }
        return actingParties.partyOf(unitOfWork, actor).orElseThrow(() -> new RiskAssessments.SelfDealingRefused(
                RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE, act));
    }

    /** {@code subject}'s accountable party placed (fail closed) and not {@code own}. */
    private void notOwn(Connection unitOfWork, SignalSubject subject, UUID own, String act) {
        Optional<UUID> subjectParty = subject.kind() == SubjectKind.PARTY
                ? Optional.of(UUID.fromString(subject.ref()))
                : subjectParties.accountablePartyOf(unitOfWork, subject);
        if (subjectParty.isEmpty()) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.SUBJECT_UNPLACEABLE,
                    act);
        }
        if (subjectParty.get().equals(own)) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY, act);
        }
    }

    /**
     * A person's act, judged: a person placed in a party, then the case under its lock (R4) and its subject's
     * accountable party - placed and not the actor's.
     */
    private RiskCase judged(Connection unitOfWork, RiskCaseId caseId, Actor actor, String act) {
        Objects.requireNonNull(caseId, "caseId");
        UUID own = placed(unitOfWork, actor, act);
        RiskCase locked = store.lock(unitOfWork, caseId).orElseThrow(CaseNotFound::new);
        notOwn(unitOfWork, locked.subject(), own, act);
        return locked;
    }

    /**
     * True when {@code actor} is a person: an authenticated human - {@code CUSTOMER}-typed, as the platform's session
     * interceptor establishes every identity (the operator's permission is the door's), or {@code EMPLOYEE}.
     */
    static boolean person(Actor actor) {
        return actor.type() == ActorType.CUSTOMER || actor.type() == ActorType.EMPLOYEE;
    }

    /** The kinds a person opens by hand, each on its subject kind ({@code DESIGN-016} D15). */
    static boolean admittedByHand(CaseKind kind, SubjectKind subject) {
        return switch (kind) {
            case FRAUD, ATO, AML -> subject == SubjectKind.PARTY;
            case MERCHANT_ABUSE -> subject == SubjectKind.MERCHANT;
            case COLLECTIONS -> false;
        };
    }

    private void append(Connection unitOfWork, Actor actor, RiskAuditAction action, RiskCase riskCase,
            Optional<String> reason, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                riskCase.id().value().toString(), reason, AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    /** {@code risk.CaseOpened} / {@code CaseEscalated} / {@code CaseDisposed} - the case's id, kind, status and code. */
    private void announce(Connection unitOfWork, String type, RiskCase riskCase, Optional<Disposition> disposition,
            CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("caseId", riskCase.id().value().toString())
                .with("kind", riskCase.kind().name())
                .with("status", riskCase.status().name());
        if (disposition.isPresent()) {
            payload = payload.with("disposition", disposition.get().name());
        }
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), type, EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION,
                        riskCase.id(), AGGREGATE_TYPE, Instant.now(clock), PRODUCER, correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(), EventPayload.MEDIA_TYPE);
    }
}
