package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Alerts born once, and their machine (`P13-TSK-015`; {@code risk V010}; {@code PHASE_13_PLAN.md} sections 3.1, 4, 5,
 * 7.1, 7.2, 10, 11; {@code RISK_LIFECYCLES.md} section 4.5; ADR-0110 decisions 1 and 3; {@code INV-RSK-06},
 * {@code INV-RSK-07}, {@code INV-RSK-08}, {@code INV-AUD-04}).
 *
 * <ul>
 *   <li><strong>{@link #raise} / {@link #raiseAll}</strong> - S, on the caller's unit of work (T-eval inside the judged
 *       command's transaction after its locks; T-mon's own), READ COMMITTED. Each alert's birth is <em>one</em>
 *       statement, {@code INSERT … ON CONFLICT ON CONSTRAINT risk_alert_born_once DO NOTHING}: the unique on (subject,
 *       rule, window start) is the arbiter, so a replayed event, a reprocessed history or ten instances raising one alert
 *       write one row; a conflict reads the standing row in a second statement and writes nothing.
 *       {@code risk.AlertRaised} is written to the outbox <strong>only when born</strong>, in the same transaction.
 *       <strong>The order:</strong> a transaction's alerts are raised in {@link AlertKey} order and every birth comes
 *       before any fold - a birth is an insert at (R3)'s position, a fold takes the case (R4) and then the alert (R5) -
 *       so two raisers of overlapping alerts meet on the unique in one order, and none holds a case while it waits on
 *       another's uncommitted alert. A transaction raises through one {@code raiseAll}. A failed birth - not a
 *       conflict, which is an answer - throws {@link RiskStorageException} and leaves the caller's transaction
 *       aborted: a raiser inside T-eval takes its raise behind a savepoint of its own (`-004`'s shape) wherever the
 *       judged command must survive it.</li>
 *   <li><strong>The folding</strong> ({@link AlertFolding}, `-016`'s) - each born alert of a kind that opens a case,
 *       never an operational one, never one found standing; {@link AlertFolding#NONE} until `-016`, the alert queued
 *       {@code OPEN}. {@link #attach} is the alert's own edge the folding (and `-016`'s case door) calls:
 *       {@code OPEN → ATTACHED}. <strong>The system never suppresses</strong>: a duplicate attaches.</li>
 *   <li><strong>The suppression machine</strong> - P only, a person ({@code CUSTOMER}- or {@code EMPLOYEE}-typed since
 *       `P13-TSK-018`, {@code risk V014}), four eyes, reasons required (the door is `-018`'s: {@code RISK_INVESTIGATE}
 *       proposes, {@code RISK_CASE_APPROVE} approves from a {@code MULTI_FACTOR} session): {@link #proposeSuppression}
 *       ({@code OPEN}, no live proposal, a closed {@link SuppressionReason} and a screened text),
 *       {@link #approveSuppression} (a holder other than the proposer → {@code SUPPRESSED}; the proposer refused
 *       {@link SelfApprovalRefused}, recorded {@code FAILED} by {@link #recordRefusal(Connection, SelfApprovalRefused,
 *       Actor, CorrelationId)} in a transaction of its own, and {@code risk_alert_suppression_four_eyes} holding every
 *       writer alone), {@link #rejectSuppression} (anyone - the proposal cleared, the alert {@code OPEN}). Each act
 *       under the alert's row lock (R5), audited {@code risk.AlertSuppressionProposed}, {@code risk.AlertSuppressed},
 *       {@code risk.AlertSuppressionRejected}.</li>
 * </ul>
 *
 * <p><strong>Self-dealing fails closed on every person's act</strong> ({@code PHASE_13_PLAN.md} section 11): an actor
 * the platform cannot place in a party is refused before anything is read; then, under the row's lock, an alert whose
 * subject's accountable party cannot be placed ({@link RiskSubjectParties#accountablePartyOf} - a {@code PARTY} subject
 * is its own, a merchant its organisation's) or is the actor's own is refused before anything is written
 * ({@link RiskAssessments.SelfDealingRefused}, recorded {@code FAILED}).
 * Nothing here logs; the event, the audit summaries and the meters carry ids and closed codes only - never a reason, a
 * score, a rule's parameters or the evidence ({@code INV-RSK-08}).
 */
public final class Alerts {

    public static final String EVENT_TYPE = "risk.AlertRaised";
    public static final String PRODUCER = "risk";
    public static final int EVENT_VERSION = 1;
    public static final String AGGREGATE_TYPE = "risk_alert";

    /** The audit target type of every person's act on an alert. */
    public static final String TARGET_TYPE = "risk_alert";

    private final RiskAlertStore store;
    private final AlertFolding folding;
    private final RiskActingParty actingParties;
    private final RiskSubjectParties subjectParties;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final Spans spans;
    private final AlertObserver observer;

    public Alerts(RiskAlertStore store, AlertFolding folding, RiskActingParty actingParties,
            RiskSubjectParties subjectParties, AuditWriter<Connection> audit, OutboxWriter<Connection> outbox,
            IdGenerator ids, Clock clock, Spans spans, AlertObserver observer) {
        this.store = Objects.requireNonNull(store, "store");
        this.folding = Objects.requireNonNull(folding, "folding");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.subjectParties = Objects.requireNonNull(subjectParties, "subjectParties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.spans = Objects.requireNonNull(spans, "spans");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    // ------------------------------------------------------------------ outcomes

    /** What a raise did. */
    public enum Outcome {
        /** The alert was born by this raise, announced and handed to the folding. */
        BORN,
        /** Its key was already raised: nothing written, nothing announced, nothing folded - the standing row answered. */
        ALREADY
    }

    /** A raise's answer: the alert as it stands after the raise, what the raise did, and what the folding did. */
    public record Raised(RiskAlert alert, Outcome outcome, AlertFolding.Folded folded) {
        public Raised {
            Objects.requireNonNull(alert, "alert");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(folded, "folded");
            if (outcome == Outcome.ALREADY && folded.outcome() != AlertFolding.Folding.QUEUED) {
                throw new IllegalArgumentException("only a born alert is folded");
            }
        }
    }

    /** A person's decision on a suppression - the alert after it; {@code replayed} when the same approver's retry converged. */
    public record Decided(RiskAlert alert, boolean replayed) {
        public Decided {
            Objects.requireNonNull(alert, "alert");
        }
    }

    // ------------------------------------------------------------------ refusals

    /** No alert answers the request ({@code 404 risk.NotFound}). */
    public static final class AlertNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AlertNotFound() {
            super("no risk record answers the request");
        }
    }

    /** The alert is attached to a case or suppressed ({@code 409 risk.AlertNotOpen}). */
    public static final class AlertNotOpen extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AlertNotOpen(AlertStatus status) {
            super("the alert is no longer open: it is " + status.name());
        }
    }

    /**
     * The alert's suppression is not in the state the act needs ({@code 409 risk.SuppressionStale}): a proposal already
     * awaits a decision, or none does.
     */
    public static final class SuppressionStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SuppressionStale(String detail) {
            super(detail);
        }
    }

    /** The proposer tried to approve their own suppression ({@code 403 risk.SelfApprovalRefused}; {@code INV-RSK-07}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(RiskAlert alert) {
            super("an alert's suppression is approved by a second RISK_CASE_APPROVE holder; the proposer may reject it");
            this.target = alert.id().value().toString();
            this.summary = "alert=" + alert.id().value() + ", kind=" + alert.kind().name()
                    + ", act=alert-suppression:approve";
        }

        /** The alert whose suppression the approver proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the alert, its kind and the act - never the subject or a person. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ raise

    /** Raises {@code alert} on the caller's unit of work - {@link #raiseAll} of one. */
    public Raised raise(Connection unitOfWork, NewAlert alert) {
        return raiseAll(unitOfWork, List.of(Objects.requireNonNull(alert, "alert"))).get(0);
    }

    /**
     * Raises every alert of {@code alerts} on the caller's unit of work: births in {@link AlertKey} order, each born
     * alert announced, then each born alert of a kind that opens a case folded, in the same order. The answers come
     * back in {@code alerts}' order. Two alerts of one key in one call are one birth and one {@code ALREADY}.
     */
    public List<Raised> raiseAll(Connection unitOfWork, List<NewAlert> alerts) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        List<NewAlert> asked = List.copyOf(Objects.requireNonNull(alerts, "alerts"));
        return spans.within(RiskSpans.ALERT_RAISE, Map.of(), () -> raised(unitOfWork, asked));
    }

    private List<Raised> raised(Connection unitOfWork, List<NewAlert> asked) {
        Integer[] order = new Integer[asked.size()];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, Comparator.comparing(i -> asked.get(i).key()));

        // Every birth first, in key order.
        RiskAlert[] standing = new RiskAlert[asked.size()];
        Outcome[] outcomes = new Outcome[asked.size()];
        for (int i : order) {
            NewAlert alert = asked.get(i);
            Optional<RiskAlert> born = store.insert(unitOfWork, RiskAlertId.next(ids), alert);
            if (born.isPresent()) {
                standing[i] = born.get();
                outcomes[i] = Outcome.BORN;
                announce(unitOfWork, born.get(), alert);
                observer.raised(alert.kind(), alert.severity());
            } else {
                standing[i] = store.findByKey(unitOfWork, alert.key()).orElseThrow(() -> new IllegalStateException(
                        "an alert's birth conflicted and no alert stands for its key"));
                outcomes[i] = Outcome.ALREADY;
            }
        }

        // Then the folds - a born alert of a kind that opens a case only, in the same order.
        AlertFolding.Folded[] folds = new AlertFolding.Folded[asked.size()];
        for (int i : order) {
            folds[i] = AlertFolding.Folded.QUEUED;
            if (outcomes[i] != Outcome.BORN || standing[i].kind().operational()) {
                continue;
            }
            AlertFolding.Folded folded = Objects.requireNonNull(folding.fold(unitOfWork, standing[i]), "folded");
            folds[i] = folded;
            if (folded.outcome() == AlertFolding.Folding.ATTACHED) {
                RiskAlert attached = store.find(unitOfWork, standing[i].id()).orElseThrow();
                if (attached.status() != AlertStatus.ATTACHED || !attached.attachedCase().equals(folded.caseId())) {
                    throw new IllegalStateException("the folding answered ATTACHED and the alert is " + attached.status());
                }
                standing[i] = attached;
            }
        }

        List<Raised> answers = new ArrayList<>(asked.size());
        for (int i = 0; i < asked.size(); i++) {
            answers.add(new Raised(standing[i], outcomes[i], folds[i]));
        }
        return List.copyOf(answers);
    }

    /** {@code risk.AlertRaised} - ids and closed codes only: the alert, its kind, severity and subject. */
    private void announce(Connection unitOfWork, RiskAlert born, NewAlert alert) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        born.id(),
                        AGGREGATE_TYPE,
                        Instant.now(clock),
                        PRODUCER,
                        alert.correlation(),
                        alert.causation()),
                EventPayload.of()
                        .with("alertId", born.id().value().toString())
                        .with("kind", born.kind().name())
                        .with("severity", born.severity().name())
                        .with("subjectKind", born.subject().kind().name())
                        .with("subjectRef", born.subject().ref())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // ------------------------------------------------------------------ attach (-016's edge)

    /**
     * {@code OPEN → ATTACHED} to {@code caseId} - the alert's own edge, called by `-016`'s folding after it holds the
     * case (R4) and by its case door. The same case's retry converges.
     *
     * @throws AlertNotFound no such alert
     * @throws AlertNotOpen the alert is suppressed, or attached to another case
     * @throws IllegalArgumentException an operational alert - it opens no case
     */
    public RiskAlert attach(Connection unitOfWork, RiskAlertId alertId, RiskCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(caseId, "caseId");
        RiskAlert row = store.find(unitOfWork, alertId).orElseThrow(AlertNotFound::new);
        if (row.kind().operational()) {
            throw new IllegalArgumentException("an operational alert opens no case and attaches to none");
        }
        if (!row.open()) {
            return converged(row, caseId);
        }
        Optional<RiskAlert> attached = store.attach(unitOfWork, alertId, caseId);
        if (attached.isPresent()) {
            return attached.get();
        }
        return converged(store.find(unitOfWork, alertId).orElseThrow(AlertNotFound::new), caseId);
    }

    private static RiskAlert converged(RiskAlert row, RiskCaseId caseId) {
        if (row.status() == AlertStatus.ATTACHED && row.attachedCase().filter(caseId::equals).isPresent()) {
            return row;
        }
        throw new AlertNotOpen(row.status());
    }

    // ------------------------------------------------------------------ the suppression machine (-018's door)

    /**
     * Proposes to suppress {@code alertId} by {@code actor} with a reason code of {@link SuppressionReason} and a text:
     * under the row's lock, {@code OPEN} and with no live proposal; audited {@code risk.AlertSuppressionProposed}.
     *
     * @throws RiskReasons.ReasonRequired a code outside the vocabulary, or a missing, unbounded or instrument-bearing text
     * @throws RiskAssessments.SelfDealingRefused the actor's own party's alert, or an actor or subject placed in no party
     * @throws AlertNotFound no such alert
     * @throws AlertNotOpen the alert is attached or suppressed
     * @throws SuppressionStale a proposal already awaits a decision
     */
    public RiskAlert proposeSuppression(Connection unitOfWork, RiskAlertId alertId, String reasonCode, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        SuppressionReason code = SuppressionReason.named(reasonCode).orElseThrow(() -> new RiskReasons.ReasonRequired(
                "a suppression's reason code is one of the closed vocabulary: "
                        + Arrays.toString(SuppressionReason.values())));
        String reasoned = RiskReasons.reasoned(reason);
        RiskAlert row = judged(unitOfWork, alertId, actor, "alert-suppression-propose");
        if (!row.open()) {
            throw new AlertNotOpen(row.status());
        }
        if (row.suppression().isPresent()) {
            throw new SuppressionStale("a suppression already awaits a decision on this alert; decide that one");
        }
        RiskAlert proposed = store.propose(unitOfWork, alertId, code, reasoned, actor).orElseThrow(() ->
                new IllegalStateException("the locked alert moved under another writer: the FOR UPDATE protocol was"
                        + " bypassed"));
        append(unitOfWork, actor, RiskAuditAction.ALERT_SUPPRESSION_PROPOSED, proposed, reasoned,
                "kind=" + proposed.kind().name() + ", reasonCode=" + code.name(), correlation);
        return proposed;
    }

    /**
     * Approves the standing proposal to suppress {@code alertId} by {@code actor} with {@code reason}: under the row's
     * lock, {@code OPEN} with a live proposal, the approver not its proposer → {@code SUPPRESSED}, audited
     * {@code risk.AlertSuppressed} and counted {@code finapp.risk.alert.suppressed{reason_code}}. The same approver's
     * retry converges.
     *
     * @throws RiskReasons.ReasonRequired a missing, unbounded or instrument-bearing reason
     * @throws RiskAssessments.SelfDealingRefused the actor's own party's alert, or an actor or subject placed in no party
     * @throws AlertNotFound no such alert
     * @throws AlertNotOpen the alert is attached, or suppressed by somebody else
     * @throws SuppressionStale no suppression awaits a decision
     * @throws SelfApprovalRefused the approver proposed it
     */
    public Decided approveSuppression(Connection unitOfWork, RiskAlertId alertId, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RiskAlert row = judged(unitOfWork, alertId, actor, "alert-suppression-approve");
        if (row.status() == AlertStatus.SUPPRESSED
                && row.decision().filter(decided -> decided.decidedBy().equals(actor.id())).isPresent()) {
            return new Decided(row, true);
        }
        if (!row.open()) {
            throw new AlertNotOpen(row.status());
        }
        RiskAlert.Suppression proposal = row.suppression().orElseThrow(() -> new SuppressionStale(
                "no suppression awaits a decision on this alert"));
        if (proposal.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row);
        }
        RiskAlert suppressed = store.approve(unitOfWork, alertId, actor, reasoned).orElseThrow(() ->
                new IllegalStateException("the locked alert moved under another writer: the FOR UPDATE protocol was"
                        + " bypassed"));
        append(unitOfWork, actor, RiskAuditAction.ALERT_SUPPRESSED, suppressed, reasoned,
                "kind=" + suppressed.kind().name() + ", reasonCode=" + proposal.reasonCode().name(), correlation);
        observer.suppressed(proposal.reasonCode());
        return new Decided(suppressed, false);
    }

    /**
     * Rejects the standing proposal to suppress {@code alertId} by {@code actor} - anyone, the proposer's withdrawal
     * included - with {@code reason}: the proposal cleared, the alert {@code OPEN}, audited
     * {@code risk.AlertSuppressionRejected} (the trail keeps the proposal and its rejection).
     *
     * @throws RiskReasons.ReasonRequired a missing, unbounded or instrument-bearing reason
     * @throws RiskAssessments.SelfDealingRefused the actor's own party's alert, or an actor or subject placed in no party
     * @throws AlertNotFound no such alert
     * @throws AlertNotOpen the alert is attached or suppressed
     * @throws SuppressionStale no suppression awaits a decision
     */
    public Decided rejectSuppression(Connection unitOfWork, RiskAlertId alertId, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RiskAlert row = judged(unitOfWork, alertId, actor, "alert-suppression-reject");
        if (!row.open()) {
            throw new AlertNotOpen(row.status());
        }
        RiskAlert.Suppression proposal = row.suppression().orElseThrow(() -> new SuppressionStale(
                "no suppression awaits a decision on this alert"));
        RiskAlert rejected = store.reject(unitOfWork, alertId).orElseThrow(() ->
                new IllegalStateException("the locked alert moved under another writer: the FOR UPDATE protocol was"
                        + " bypassed"));
        append(unitOfWork, actor, RiskAuditAction.ALERT_SUPPRESSION_REJECTED, rejected, reasoned,
                "kind=" + rejected.kind().name() + ", reasonCode=" + proposal.reasonCode().name() + ", byProposer="
                        + proposal.proposedBy().equals(actor.id()),
                correlation);
        return new Decided(rejected, false);
    }

    /** {@code alertId} as it stands now - the receipt of the act its caller has just performed; no lock, no audit. */
    public Optional<RiskAlert> current(Connection unitOfWork, RiskAlertId alertId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.find(unitOfWork, Objects.requireNonNull(alertId, "alertId"));
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
     * own, after the act was abandoned. Its target the alert; the summary the act and the cause - never the party.
     */
    public void recordRefusal(Connection unitOfWork, RiskAlertId alertId, RiskAssessments.SelfDealingRefused refused,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(refused, "refused");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, TARGET_TYPE, alertId.value().toString(), Optional.empty(),
                AuditOutcome.FAILED, correlation,
                Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * A person's act, judged: a person - an authenticated human, {@code CUSTOMER}-typed as the platform's session
     * establishes every identity, or {@code EMPLOYEE}; never the system, a service or a merchant ({@link Cases#person},
     * `P13-TSK-018`'s widening, {@code risk V014}) - placed in a party (fail closed), then the alert under its lock (R5)
     * and its subject's accountable party - placed (fail closed) and not the actor's.
     */
    private RiskAlert judged(Connection unitOfWork, RiskAlertId alertId, Actor actor, String act) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(actor, "actor");
        if (!Cases.person(actor)) {
            throw new IllegalArgumentException("an alert's suppression is a person's act - never the platform's, a"
                    + " service's or a merchant's");
        }
        Optional<UUID> own = actingParties.partyOf(unitOfWork, actor);
        if (own.isEmpty()) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE, act);
        }
        RiskAlert row = store.lock(unitOfWork, alertId).orElseThrow(AlertNotFound::new);
        Optional<UUID> subjectParty = partyOf(unitOfWork, row.subject());
        if (subjectParty.isEmpty()) {
            throw new RiskAssessments.SelfDealingRefused(
                    RiskAssessments.SelfDealingRefused.Cause.SUBJECT_UNPLACEABLE, act);
        }
        if (subjectParty.get().equals(own.get())) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY, act);
        }
        return row;
    }

    /**
     * The party accountable for {@code subject} - a party is its own; every other kind through the accountable join
     * ({@link RiskSubjectParties#accountablePartyOf}: since `P13-TSK-018` a merchant's organisation party too, the join
     * `-016` built for the case - so a {@code MERCHANT_ABUSE} alert is suppressible, `DESIGN-018` D6).
     */
    private Optional<UUID> partyOf(Connection unitOfWork, SignalSubject subject) {
        if (subject.kind() == SubjectKind.PARTY) {
            return Optional.of(UUID.fromString(subject.ref()));
        }
        return subjectParties.accountablePartyOf(unitOfWork, subject);
    }

    private void append(Connection unitOfWork, Actor actor, RiskAuditAction action, RiskAlert alert, String reason,
            String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                alert.id().value().toString(), Optional.of(reason), AuditOutcome.SUCCEEDED, correlation,
                Optional.of(summary)));
    }
}
