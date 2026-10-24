package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The action an approved override issues, in T-act's shape (`P13-TSK-012`; {@code PHASE_13_PLAN.md} sections 7.1 T-act,
 * 7.2 (R6); ADR-0112 decisions 3-7; {@code INV-RSK-02}, {@code INV-IDEM-01}) - until `-017` folds it into the
 * {@code risk_action} machine and its schedule. Three steps, each its caller's own transaction or none:
 *
 * <ol>
 *   <li><strong>The issue</strong> - recorded by the approval itself ({@link DecisionOverrides#approve}): the action,
 *       its permit and its first attempt on the override row, {@code risk.RiskActionRequested} beside them, committed
 *       before anything is attempted. A re-drive finds it by {@link #pending} (the approval door's retry) or claims it by
 *       {@link #claimNextDue} (the re-drive leg: {@code SKIP LOCKED} on the permit, which it re-stamps and commits
 *       before dispatching).</li>
 *   <li><strong>The dispatch</strong> - {@link #dispatch}, on <strong>no connection and inside no transaction</strong>:
 *       payments' keyed door ({@link HeldWithdrawalActions}, {@link HeldPaymentActions}) runs in payments' own
 *       transactions on payments' lock order, with no risk row held at all, under the one key every attempt of this
 *       override presents - {@code risk.action:<override id>} - and the override's frozen reason code, so payments'
 *       fingerprint is the same each time and any number of attempts is one effect.</li>
 *   <li><strong>The record</strong> - {@link #conclude}, a transaction of its own: the outcome written by a conditional
 *       edge (once, whoever records it - the door or the leg), audited {@code risk.ActionConcluded} and announced
 *       {@code risk.RiskActionCompleted}. A refusal by payments ({@code ALREADY}: the row left its hold by another hand,
 *       the expiry among them; {@code REFUSED}: the hold's validity lapsed, or no such movement) is an outcome, never an
 *       error; risk retries nothing the domain answered (scenario 7). Anything payments THROWS - an attempt in flight, a
 *       storage failure - records nothing: the permit stands and the next attempt re-drives the same key.</li>
 * </ol>
 *
 * <p>Risk never writes payments' row and never touches a hold; the effect is payments' command, audited by payments
 * as the caller's act.
 */
public final class OverrideActions {

    /** Every attempt of one override's action is keyed by this prefix and the override's id (ADR-0112 decision 4). */
    public static final String KEY_PREFIX = "risk.action:";

    /** On an approval that issues an action: the override's id, the kind, the target - ids and codes only. */
    public static final String REQUESTED_EVENT = "risk.RiskActionRequested";

    /** On the outcome's record: the same, and the outcome. */
    public static final String COMPLETED_EVENT = "risk.RiskActionCompleted";

    static final int EVENT_VERSION = 1;

    private final DecisionOverrideStore store;
    private final HeldWithdrawalActions withdrawals;
    private final HeldPaymentActions payments;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public OverrideActions(DecisionOverrideStore store, HeldWithdrawalActions withdrawals, HeldPaymentActions payments,
            AuditWriter<Connection> audit, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One issued action awaiting its outcome - what a dispatch needs, and nothing of the reasons or the people. */
    public record Issued(DecisionOverrideId override, RiskAssessmentId assessment, ActionKind kind,
            OverrideEffect effect, UUID target, OverrideReason reasonCode, int attempts, String correlationId) {
        public Issued {
            Objects.requireNonNull(override, "override");
            Objects.requireNonNull(assessment, "assessment");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(correlationId, "correlationId");
        }

        /** The one key every attempt presents to payments. */
        public String key() {
            return keyOf(override);
        }

        static Optional<Issued> of(DecisionOverride row) {
            if (!row.awaitsOutcome()) {
                return Optional.empty();
            }
            return Optional.of(new Issued(row.id(), row.assessment(), row.effect().action().orElseThrow(), row.effect(),
                    row.target().orElseThrow(), row.reasonCode(), row.actionAttempts(), row.correlationId()));
        }
    }

    /** {@code risk.action:<override id>}. */
    public static String keyOf(DecisionOverrideId override) {
        return KEY_PREFIX + Objects.requireNonNull(override, "override").value();
    }

    /** {@code id}'s issued action, when it still awaits its outcome - no lock (the approval door's re-drive). */
    public Optional<Issued> pending(Connection unitOfWork, DecisionOverrideId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.find(unitOfWork, id).flatMap(Issued::of);
    }

    /**
     * The oldest issued action whose permit has come, claimed and its permit moved {@code lease} ahead on
     * {@code unitOfWork} - the caller commits the claim before dispatching (the re-drive leg).
     */
    public Optional<Issued> claimNextDue(Connection unitOfWork, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.claimDue(unitOfWork, lease).map(row -> Issued.of(row).orElseThrow(() -> new IllegalStateException(
                "a claimed permit belongs to an action awaiting its outcome: the permit CHECK was bypassed")));
    }

    /**
     * The action through its owning domain's door - on <strong>no connection</strong>: the caller holds no transaction
     * (payments runs its own, and joining one of risk's would hold risk rows across a send).
     *
     * @return payments' answer, word for word
     * @throws RuntimeException whatever payments throws - an attempt in flight, a storage failure: nothing is recorded,
     *     the permit stands, and the next attempt re-drives the same key
     */
    public ActionOutcome dispatch(Issued issued) {
        Objects.requireNonNull(issued, "issued");
        String key = issued.key();
        String reason = issued.reasonCode().name();
        return switch (issued.effect()) {
            case RELEASE_HELD_WITHDRAWAL -> withdrawals.release(issued.target(), key, reason);
            case DENY_HELD_WITHDRAWAL -> withdrawals.deny(issued.target(), key, reason);
            case CAPTURE_HELD_PAYMENT -> payments.capture(issued.target(), key, reason);
            case VOID_HELD_PAYMENT -> payments.voidHold(issued.target(), key, reason);
            case RESUBMISSION -> throw new IllegalStateException("a resubmission dispatches nothing");
        };
    }

    /**
     * Records {@code outcome} on {@code issued}'s override, once: {@code risk.ActionConcluded} and
     * {@code risk.RiskActionCompleted} beside it on {@code unitOfWork}. False - nothing written - when an outcome is
     * already recorded (the door and the leg recorded the same answer; the first stands).
     */
    public boolean conclude(Connection unitOfWork, Issued issued, ActionOutcome outcome, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(issued, "issued");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        if (!store.conclude(unitOfWork, issued.override(), outcome)) {
            return false;
        }
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.ACTION_CONCLUDED, DecisionOverrides.TARGET_TYPE, issued.override().value().toString(),
                Optional.empty(), AuditOutcome.SUCCEEDED, correlation, Optional.of("kind=" + issued.kind().name()
                        + ", outcome=" + outcome.name() + ", attempts=" + issued.attempts())));
        announce(outbox, ids, clock, unitOfWork, COMPLETED_EVENT, issued.override(), issued.assessment(), issued.effect(),
                issued.target(), Optional.of(outcome), correlation);
        return true;
    }

    /** The action's event - ids and codes only ({@code INV-RSK-08}): never a reason, an amount or a person. */
    static void announce(OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock, Connection unitOfWork,
            String type, DecisionOverrideId override, RiskAssessmentId assessment, OverrideEffect effect, UUID target,
            Optional<ActionOutcome> outcome, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("source", "OVERRIDE")
                .with("overrideId", override.value().toString())
                .with("assessmentId", assessment.value().toString())
                .with("kind", effect.action().orElseThrow().name())
                .with("targetKind", effect.targetKind().orElseThrow())
                .with("targetId", target.toString());
        if (outcome.isPresent()) {
            payload = payload.with("outcome", outcome.get().name());
        }
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), type, EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION, override,
                        DecisionOverrides.TARGET_TYPE, clock.instant(), RiskPolicyAdministration.PRODUCER, correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(), EventPayload.MEDIA_TYPE);
    }
}
