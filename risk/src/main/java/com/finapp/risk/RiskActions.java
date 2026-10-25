package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The downstream action machine (`P13-TSK-017`; {@code risk V013}; {@code PHASE_13_PLAN.md} sections 3.1, 4, 5, 7.1
 * T-case and T-act, 7.2 (R4, R6), 10, 11, 12.8; {@code RISK_LIFECYCLES.md} section 4.7; ADR-0112; {@code INV-RSK-02},
 * {@code INV-RSK-06}, {@code INV-RSK-07}, {@code INV-IDEM-01}, {@code INV-AUD-04}). The <strong>record</strong> of an
 * effect the owning domain executes - risk never writes the other half.
 *
 * <h2>T-case: a person's acts (each its caller's one transaction)</h2>
 *
 * <p>{@link #propose}, {@link #approve}, {@link #reject} are steps of the action's case, through {@link Cases}' own
 * protocol: a person placed in a party, (R4) the case row {@code FOR UPDATE}, its subject's accountable party not the
 * actor's (fail closed), the case open, the revision the body carries compared, the investigator action
 * ({@code ACTION_PROPOSED} / {@code _APPROVED} / {@code _REJECTED}, with the person's reasoned text), the conditional
 * step and its history row - then (R6) the action row: born {@code PROPOSED} under {@code risk_action_one_live}, its id the
 * {@code ACTION_PROPOSED} action's (so its key is fixed from birth); approved by a <strong>second person</strong> (the
 * proposer refused {@link SelfApprovalRefused}, recorded {@code FAILED} by the caller; {@code risk_action_four_eyes} for
 * every writer) under a permit due at once, announced {@code risk.RiskActionRequested}; rejected by anyone. What a case
 * may propose is {@link ActionRules}': never a target the case does not own - its own party or merchant, or a held
 * movement it holds. <strong>Nothing is dispatched in T-case</strong> - the approval commits first.
 *
 * <h2>T-ovr: an override's issued action ({@link #issued})</h2>
 *
 * <p>`P13-TSK-012`'s issued action folded into this machine: an approved override's dispatchable effect is born
 * {@code APPROVED} under the <strong>override's own id</strong> - so the key it presents payments,
 * {@code risk.action:<override id>}, is the one `-012` issued - in the approval's transaction, announced
 * {@code risk.RiskActionRequested}. Its four eyes are the override's (C7, {@code decision_override_four_eyes}).
 *
 * <h2>T-act: the platform's execution (three steps, the owner's command in none of risk's transactions)</h2>
 *
 * <ol>
 *   <li><strong>The claim</strong> ({@link #claim} for the approval door's own action, {@link #claimNextDue} for
 *       {@code RiskActionSchedule}) - (R6) the row {@code FOR UPDATE SKIP LOCKED} where its permit has come on the
 *       database clock, moved {@code DISPATCHED}, the permit a lease ahead, the attempt counted; the caller
 *       <strong>commits</strong> it, so ten claimers claim it once per lease. The first attempt audited
 *       {@code risk.ActionDispatched}.</li>
 *   <li><strong>The dispatch</strong> ({@link #dispatch} - {@link ActionDispatch}) on no connection: the owner's own
 *       transaction on its own lock order, no risk row held, under {@code risk.action:<id>} and the action's reason code,
 *       as the acting principal - the platform ({@code app}'s driver enters the system actor: a person decided, the
 *       platform executes).</li>
 *   <li><strong>The record</strong> ({@link #conclude}) - the owner's answer by a conditional edge, once, whoever records
 *       first: {@code DONE} ({@code DONE}, {@code ALREADY}) or {@code FAILED} ({@code REFUSED}), audited
 *       {@code risk.ActionConcluded} and announced {@code risk.RiskActionCompleted}. A refusal is an outcome; risk retries
 *       nothing the owner answered (scenario 7). Anything the owner throws records nothing: the permit stands and the next
 *       claim re-drives the same key, the owner replaying its first answer.</li>
 * </ol>
 *
 * <p>Nothing here logs; the events carry ids and codes; the audit summaries codes and counts - never the target's
 * subject, a person or a reason's text ({@code INV-RSK-08}).
 */
public final class RiskActions implements ActionIssuer {

    /** Every attempt of one action is keyed by this prefix and the action's id (ADR-0112 decision 4). */
    public static final String KEY_PREFIX = "risk.action:";

    /** On an approval - a case's or an override's: the action's id, source, kind and target - ids and codes only. */
    public static final String REQUESTED_EVENT = "risk.RiskActionRequested";

    /** On the record: the same, and the status and outcome. */
    public static final String COMPLETED_EVENT = "risk.RiskActionCompleted";

    public static final String AGGREGATE_TYPE = "risk_action";

    /** The audit target type of every act on an action. */
    public static final String TARGET_TYPE = "risk_action";

    static final int EVENT_VERSION = 1;

    private final RiskActionStore actions;
    private final Cases cases;
    private final RiskCaseStore caseStore;
    private final RiskAssessmentStore assessments;
    private final ActionDispatch dispatch;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public RiskActions(RiskActionStore actions, Cases cases, RiskCaseStore caseStore, RiskAssessmentStore assessments,
            ActionDispatch dispatch, AuditWriter<Connection> audit, OutboxWriter<Connection> outbox, IdGenerator ids,
            Clock clock) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@code risk.action:<id>}. */
    public static String keyOf(RiskActionId id) {
        return KEY_PREFIX + Objects.requireNonNull(id, "id").value();
    }

    // ------------------------------------------------------------------ outcomes

    /** A proposal's answer: the action, and the case after the step. */
    public record Proposed(RiskAction action, RiskCase riskCase) {
        public Proposed {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(riskCase, "riskCase");
        }
    }

    /** A decision's answer: the action and the case after it; {@code replayed} when the same decider's retry converged. */
    public record Decided(RiskAction action, RiskCase riskCase, boolean replayed) {
        public Decided {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(riskCase, "riskCase");
        }
    }

    // ------------------------------------------------------------------ refusals

    /** The case does not admit the action as asked ({@code 409 risk.ActionNotProposable}) - nothing was written. */
    public static final class ActionNotProposable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ActionNotProposable(String detail) {
            super(detail);
        }
    }

    /** The proposer tried to approve their own action ({@code 403 risk.SelfApprovalRefused}; {@code INV-RSK-07}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(RiskCase riskCase, RiskAction action) {
            super("a " + action.kind() + " action is approved by a second RISK_CASE_APPROVE holder; the proposer may"
                    + " reject it");
            this.target = action.id().value().toString();
            this.summary = "case=" + riskCase.id().value() + ", kind=" + action.kind().name() + ", act=action:approve";
        }

        /** The action the approver proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the case, the kind and the act - never the target's subject. */
        public String summary() {
            return summary;
        }
    }

    // ================================================================== T-case (P)

    /**
     * Proposes {@code kind} on {@code caseId} - on its own party or merchant, or on {@code target}, a held movement the
     * case holds. Audited {@code risk.ActionProposed} with the reason.
     *
     * @throws Cases.CaseRequestInvalid a kind outside the vocabulary; a held movement's action naming no movement
     * @throws ActionNotProposable the case does not admit the kind, the target is not the case's, or a live twin stands
     * @throws Cases.CaseNotTaken the case is {@code OPEN} - an action has an accountable investigator
     * @throws Cases.CaseNotOpen the case is disposed
     * @throws Cases.CaseRevisionStale the body's revision is not the case's
     * @throws RiskReasons.ReasonRequired a missing, unbounded or instrument-bearing reason
     * @throws RiskAssessments.SelfDealingRefused the analyst's own party's case, or an analyst placed in no party
     */
    public Proposed propose(Connection unitOfWork, RiskCaseId caseId, int revision, String kind, Optional<UUID> target,
            String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(correlation, "correlation");
        ActionKind asked = Arrays.stream(ActionKind.values()).filter(value -> value.name().equals(kind)).findFirst()
                .orElseThrow(() -> new Cases.CaseRequestInvalid("an action is one of "
                        + Arrays.toString(ActionKind.values())));
        String reasoned = RiskReasons.reasoned(reason);
        RiskCase locked = cases.lockForAct(unitOfWork, caseId, actor, "action-propose");
        locked.judgeOpen();
        locked.judgeRevision(revision);
        if (!locked.taken()) {
            throw new Cases.CaseNotTaken("an action is proposed on a case an investigator has taken");
        }
        if (!ActionRules.admits(locked.kind(), locked.subject().kind(), asked)) {
            throw new ActionNotProposable("a " + locked.kind() + " case of a " + locked.subject().kind()
                    + " does not propose " + asked + (asked.available() ? "" : " - the owning domain has no such"
                            + " command"));
        }
        UUID targetRef = targetOf(unitOfWork, locked, asked, target);
        if (actions.findLive(unitOfWork, caseId, asked, targetRef).isPresent()) {
            throw new ActionNotProposable("this action is already proposed, approved or dispatched on this case; decide"
                    + " or conclude that one");
        }
        UUID actionId = ids.next();
        RiskCase after = cases.stepWith(unitOfWork, locked, actionId, CaseActionKind.ACTION_PROPOSED,
                CaseEventKind.ACTION_PROPOSED, actor, reasoned, correlation);
        RiskAction proposed = actions.insertProposed(unitOfWork, new RiskActionStore.NewProposal(
                RiskActionId.of(actionId), caseId, asked, targetRef, ActionRules.reasonCode(locked.kind()), actor,
                correlation.value())).orElseThrow(() -> new ActionNotProposable("this action is already live on this"
                        + " case"));
        append(unitOfWork, actor, RiskAuditAction.ACTION_PROPOSED, proposed, Optional.of(reasoned),
                "case=" + caseId.value() + ", kind=" + asked.name() + ", targetKind=" + asked.target().name(),
                correlation);
        return new Proposed(proposed, after);
    }

    /**
     * Approves {@code actionId} of {@code caseId} by a second person: the action {@code APPROVED} under a permit due at
     * once, audited {@code risk.ActionApproved}, announced {@code risk.RiskActionRequested}. Nothing is dispatched here -
     * the caller dispatches after this transaction commits. The same approver's retry converges.
     *
     * @throws Cases.CaseNotFound no such action of the case
     * @throws Cases.CaseActNotAdmitted the action is no longer proposed
     * @throws SelfApprovalRefused the approver proposed it
     */
    public Decided approve(Connection unitOfWork, RiskCaseId caseId, RiskActionId actionId, int revision, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RiskCase locked = cases.lockForAct(unitOfWork, caseId, actor, "action-approve");
        RiskAction action = lockedOf(unitOfWork, caseId, actionId);
        if (action.status() != ActionStatus.PROPOSED) {
            if (action.status() != ActionStatus.REJECTED && action.decidedBy().filter(person -> person.is(actor))
                    .isPresent()) {
                return new Decided(action, locked, true);
            }
            throw new Cases.CaseActNotAdmitted("the action is no longer awaiting a decision: " + action.status());
        }
        locked.judgeOpen();
        locked.judgeRevision(revision);
        if (action.proposedBy().is(actor)) {
            throw new SelfApprovalRefused(locked, action);
        }
        UUID decision = ids.next();
        RiskCase after = cases.stepWith(unitOfWork, locked, decision, CaseActionKind.ACTION_APPROVED,
                CaseEventKind.ACTION_APPROVED, actor, reasoned, correlation);
        RiskAction approved = actions.approve(unitOfWork, actionId, actor, decision).orElseThrow(() ->
                new IllegalStateException("the locked action was decided by another writer: the FOR UPDATE protocol was"
                        + " bypassed"));
        append(unitOfWork, actor, RiskAuditAction.ACTION_APPROVED, approved, Optional.of(reasoned),
                "case=" + caseId.value() + ", kind=" + approved.kind().name(), correlation);
        announce(unitOfWork, REQUESTED_EVENT, approved, correlation);
        return new Decided(approved, after, false);
    }

    /**
     * Rejects {@code actionId} of {@code caseId} - anyone, the proposer's withdrawal included: nothing dispatched, the
     * live unique released. Audited {@code risk.ActionRejected}. The same person's retry converges.
     *
     * @throws Cases.CaseNotFound no such action of the case
     * @throws Cases.CaseActNotAdmitted the action is no longer proposed
     */
    public Decided reject(Connection unitOfWork, RiskCaseId caseId, RiskActionId actionId, int revision, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RiskCase locked = cases.lockForAct(unitOfWork, caseId, actor, "action-reject");
        RiskAction action = lockedOf(unitOfWork, caseId, actionId);
        if (action.status() != ActionStatus.PROPOSED) {
            if (action.status() == ActionStatus.REJECTED && action.decidedBy().filter(person -> person.is(actor))
                    .isPresent()) {
                return new Decided(action, locked, true);
            }
            throw new Cases.CaseActNotAdmitted("the action is no longer awaiting a decision: " + action.status());
        }
        locked.judgeOpen();
        locked.judgeRevision(revision);
        UUID decision = ids.next();
        RiskCase after = cases.stepWith(unitOfWork, locked, decision, CaseActionKind.ACTION_REJECTED,
                CaseEventKind.ACTION_REJECTED, actor, reasoned, correlation);
        RiskAction rejected = actions.reject(unitOfWork, actionId, actor, decision).orElseThrow(() ->
                new IllegalStateException("the locked action was decided by another writer: the FOR UPDATE protocol was"
                        + " bypassed"));
        append(unitOfWork, actor, RiskAuditAction.ACTION_REJECTED, rejected, Optional.of(reasoned),
                "case=" + caseId.value() + ", kind=" + rejected.kind().name() + ", byProposer="
                        + rejected.proposedBy().is(actor),
                correlation);
        return new Decided(rejected, after, false);
    }

    /** {@code id} as it stands now - the receipt of an act or a drive; no lock, no audit. */
    public Optional<RiskAction> current(Connection unitOfWork, RiskActionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return actions.find(unitOfWork, Objects.requireNonNull(id, "id"));
    }

    /** {@code caseId}'s actions, oldest first. */
    public List<RiskAction> ofCase(Connection unitOfWork, RiskCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return actions.ofCase(unitOfWork, Objects.requireNonNull(caseId, "caseId"));
    }

    // ================================================================== T-ovr (an override's issued action)

    /**
     * Issues {@code approved}'s dispatchable effect: born {@code APPROVED} under the override's own id, due at once,
     * announced {@code risk.RiskActionRequested} - on T-ovr's unit of work, after the approval's edge. A resubmission
     * issues nothing.
     */
    @Override
    public void issue(Connection unitOfWork, DecisionOverride approved, CorrelationId correlation) {
        issued(unitOfWork, approved, correlation);
    }

    /** {@link #issue}, answering the action - empty for a resubmission. */
    public Optional<RiskAction> issued(Connection unitOfWork, DecisionOverride approved, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(approved, "approved");
        Objects.requireNonNull(correlation, "correlation");
        if (approved.status() != OverrideStatus.APPROVED) {
            throw new IllegalStateException("only an approved override issues an action");
        }
        if (!approved.effect().dispatches()) {
            return Optional.empty();
        }
        RiskAction issued = actions.insertIssued(unitOfWork, new RiskActionStore.NewIssue(approved.id(),
                approved.effect().action().orElseThrow(), approved.target().orElseThrow(),
                approved.reasonCode().name(), new RiskCase.Person(approved.proposedBy(), approved.proposedByType()),
                new RiskCase.Person(approved.decidedBy().orElseThrow(), approved.decidedByType().orElseThrow()),
                approved.correlationId()));
        announce(unitOfWork, REQUESTED_EVENT, issued, correlation);
        return Optional.of(issued);
    }

    // ================================================================== T-act (S)

    /**
     * Claims {@code id} for its dispatch - the approval door's, after its approval committed: on {@code unitOfWork}
     * (the caller commits it before dispatching), its permit moved {@code lease} ahead and an attempt counted. Empty when
     * another claimer holds it, its permit has not come, or it is concluded.
     */
    public Optional<RiskAction> claim(Connection unitOfWork, RiskActionId id, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return actions.claim(unitOfWork, Objects.requireNonNull(id, "id"), lease).map(claimed -> dispatched(unitOfWork,
                claimed));
    }

    /** The oldest due action claimed, as {@link #claim} - {@code RiskActionSchedule}'s. */
    public Optional<RiskAction> claimNextDue(Connection unitOfWork, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return actions.claimNextDue(unitOfWork, lease).map(claimed -> dispatched(unitOfWork, claimed));
    }

    /**
     * {@code claimed} through its owner's door - on <strong>no connection</strong> (the caller holds no transaction).
     *
     * @throws RuntimeException whatever the owner throws: nothing recorded, the permit stands
     */
    public ActionOutcome dispatch(RiskAction claimed) {
        return dispatch.dispatch(claimed);
    }

    /**
     * Records {@code outcome} on {@code claimed}, once: {@code risk.ActionConcluded} and {@code risk.RiskActionCompleted}
     * beside it on {@code unitOfWork}. Empty - nothing written - when it is no longer awaiting a record (another drive
     * recorded first).
     */
    public Optional<RiskAction> conclude(Connection unitOfWork, RiskAction claimed, ActionOutcome outcome,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(correlation, "correlation");
        Optional<RiskAction> recorded = actions.conclude(unitOfWork, claimed.id(), outcome);
        recorded.ifPresent(concluded -> {
            append(unitOfWork, SecurityContext.require(), RiskAuditAction.ACTION_CONCLUDED, concluded, Optional.empty(),
                    "kind=" + concluded.kind().name() + ", source=" + concluded.source().name() + ", outcome="
                            + outcome.name() + ", attempts=" + concluded.attempts(),
                    correlation);
            announce(unitOfWork, COMPLETED_EVENT, concluded, correlation);
        });
        return recorded;
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

    // ================================================================== plumbing

    /** The first claim of an action is its dispatch, audited as the platform's; a re-claim is counted on the row. */
    private RiskAction dispatched(Connection unitOfWork, RiskAction claimed) {
        if (claimed.attempts() == 1) {
            append(unitOfWork, SecurityContext.require(), RiskAuditAction.ACTION_DISPATCHED, claimed, Optional.empty(),
                    "kind=" + claimed.kind().name() + ", source=" + claimed.source().name() + ", targetKind="
                            + claimed.targetKind().name(),
                    CorrelationId.of(claimed.correlationId()));
        }
        return claimed;
    }

    /** (R6) the action under the case's lock - only an action of that case answers. */
    private RiskAction lockedOf(Connection unitOfWork, RiskCaseId caseId, RiskActionId actionId) {
        Objects.requireNonNull(actionId, "actionId");
        return actions.lock(unitOfWork, actionId).filter(found -> found.caseId().filter(caseId::equals).isPresent())
                .orElseThrow(Cases.CaseNotFound::new);
    }

    /**
     * The target {@code kind} acts on: the case's own subject for a party's or merchant's action (a target named must be
     * it), else the held movement asked - one the case holds.
     */
    private UUID targetOf(Connection unitOfWork, RiskCase locked, ActionKind kind, Optional<UUID> asked) {
        if (ActionRules.actsOnTheSubject(kind)) {
            UUID subject = UUID.fromString(locked.subject().ref());
            if (asked.isPresent() && !asked.get().equals(subject)) {
                throw new ActionNotProposable("a " + kind + " acts on the case's own " + locked.subject().kind()
                        + "; it names no other target");
            }
            return subject;
        }
        UUID movement = asked.orElseThrow(() -> new Cases.CaseRequestInvalid("a held movement's action names the"
                + " movement it resolves"));
        RiskOperation operation = ActionRules.heldOperation(kind).orElseThrow();
        if (!heldBy(unitOfWork, locked, operation).contains(movement)) {
            throw new ActionNotProposable("the case holds no held " + operation + " of that id - its opening or an"
                    + " attached assessment sealed HOLD for it");
        }
        return movement;
    }

    /**
     * The movements of {@code operation} the case holds: its opening assessment and every one the system attached
     * ({@code ASSESSMENT_ATTACHED}), sealed {@code HOLD} - their command refs. A person's evidence is not a hold.
     */
    private Set<UUID> heldBy(Connection unitOfWork, RiskCase locked, RiskOperation operation) {
        Set<RiskAssessmentId> attached = new LinkedHashSet<>();
        locked.openingAssessment().ifPresent(attached::add);
        caseStore.events(unitOfWork, locked.id()).stream()
                .filter(event -> event.kind() == CaseEventKind.ASSESSMENT_ATTACHED || event.kind() == CaseEventKind.OPENED)
                .flatMap(event -> event.assessment().stream())
                .forEach(attached::add);
        Set<UUID> held = new LinkedHashSet<>();
        for (RiskAssessmentId id : attached) {
            assessments.read(unitOfWork, id)
                    .filter(sealed -> sealed.decision() == RiskDecision.HOLD && sealed.operation() == operation)
                    .flatMap(sealed -> uuid(sealed.commandRef().value()))
                    .ifPresent(held::add);
        }
        return held;
    }

    private static Optional<UUID> uuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    private void append(Connection unitOfWork, Actor actor, RiskAuditAction action, RiskAction riskAction,
            Optional<String> reason, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                riskAction.id().value().toString(), reason, AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    /** {@code risk.RiskActionRequested} / {@code risk.RiskActionCompleted} - ids and codes only ({@code INV-RSK-08}). */
    private void announce(Connection unitOfWork, String type, RiskAction action, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("actionId", action.id().value().toString())
                .with("source", action.source().name());
        if (action.caseId().isPresent()) {
            payload = payload.with("caseId", action.caseId().get().value().toString());
        }
        if (action.overrideId().isPresent()) {
            payload = payload.with("overrideId", action.overrideId().get().value().toString());
        }
        payload = payload
                .with("kind", action.kind().name())
                .with("targetKind", action.targetKind().name())
                .with("targetId", action.target().toString());
        if (action.outcome().isPresent()) {
            payload = payload.with("status", action.status().name()).with("outcome", action.outcome().get().name());
        }
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), type, EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION,
                        action.id(), AGGREGATE_TYPE, clock.instant(), RiskPolicyAdministration.PRODUCER, correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(), EventPayload.MEDIA_TYPE);
    }
}
