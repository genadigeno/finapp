package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.RiskPolicyFixtures;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The action an approved override issues, hermetic (`P13-TSK-012`; T-act's shape, ADR-0112 decisions 4-7;
 * {@code INV-RSK-02}, {@code INV-IDEM-01}): dispatched through payments' keyed doors under the override's one key
 * {@code risk.action:<override id>} with its frozen reason code, the outcome {@code DONE}, {@code ALREADY} or
 * {@code REFUSED} recorded once, audited {@code risk.ActionConcluded} and announced {@code risk.RiskActionCompleted};
 * the re-drive after a crash between the approval's commit and the dispatch, or between the dispatch and the record,
 * issuing nothing twice; a concluded action never re-driven; a door that throws recording nothing and leaving the
 * permit for the next attempt. Payments' doors are faked as payments applies them ({@link OverrideWorld.Payments}); the
 * real doors against PostgreSQL are {@code WithdrawalHoldDatabaseTest}'s and {@code DecisionOverrideDatabaseTest}'s
 * (written, owed - C5).
 */
@DisplayName("the override's issued action: one effect per key, its outcome recorded once (P13-TSK-012)")
class OverrideActionTest {

    private static final long THRESHOLD = RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR;
    private static final Actor PLATFORM = new Actor("risk-monitoring", ActorType.SYSTEM);

    private final OverrideWorld world = new OverrideWorld();

    @Test
    @DisplayName("the release is issued once, under risk.action:<override id> and the reason code: DONE recorded,"
            + " audited risk.ActionConcluded and announced risk.RiskActionCompleted; nothing awaits any longer")
    void theReleaseIsIssuedOnceUnderTheOverridesKey() {
        DecisionOverride approved = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        OverrideActions.Issued issued = world.actions.pending(EvaluationWorld.UOW, approved.id()).orElseThrow();
        assertThat(issued.key()).isEqualTo("risk.action:" + approved.id().value());
        assertThat(issued.kind()).isEqualTo(ActionKind.RELEASE_HELD_WITHDRAWAL);

        ActionOutcome outcome = world.actions.dispatch(issued);
        assertThat(outcome).isEqualTo(ActionOutcome.DONE);
        assertThat(world.payments.calls).containsExactly("release|" + approved.target().orElseThrow() + "|"
                + issued.key() + "|FALSE_POSITIVE");
        Actor approver = new Actor(approved.decidedBy().orElseThrow(), ActorType.EMPLOYEE);
        assertThat(world.actions.conclude(EvaluationWorld.UOW, issued, outcome, approver, OverrideWorld.correlation()))
                .isTrue();

        DecisionOverride concluded = world.overrides.find(EvaluationWorld.UOW, approved.id()).orElseThrow();
        assertThat(concluded.actionOutcome()).contains(ActionOutcome.DONE);
        assertThat(concluded.actionDueAt()).isEmpty();
        assertThat(concluded.actionConcludedAt()).isPresent();
        assertThat(world.actions.pending(EvaluationWorld.UOW, approved.id())).isEmpty();
        assertThat(world.audited("risk.ActionConcluded")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.actor()).isEqualTo(approver);
            assertThat(record.targetId()).isEqualTo(approved.id().value().toString());
            assertThat(record.changeSummary()).contains("kind=RELEASE_HELD_WITHDRAWAL, outcome=DONE, attempts=1");
        });
        assertThat(world.events).extracting(event -> event.eventType())
                .containsExactly("risk.RiskActionRequested", "risk.RiskActionCompleted");
        assertThat(world.payloads.get(1)).containsEntry("outcome", "DONE").containsEntry("kind",
                "RELEASE_HELD_WITHDRAWAL");
        assertThat(world.payments.effects()).isEqualTo(1);
    }

    @Test
    @DisplayName("each effect reaches its own door: the denial payments' deny, the capture and the void the held"
            + " payment's - the target the judged command")
    void eachEffectReachesItsDoor() {
        for (Object[] row : List.of(
                new Object[] {RiskOperation.WITHDRAWAL, OverrideResolution.REFUSE, "deny"},
                new Object[] {RiskOperation.CARD_PAYMENT, OverrideResolution.PROCEED, "capture"},
                new Object[] {RiskOperation.CARD_PAYMENT, OverrideResolution.REFUSE, "void"})) {
            world.payments.calls.clear();
            DecisionOverride approved = approved((RiskOperation) row[0], (OverrideResolution) row[1]);
            OverrideActions.Issued issued = world.actions.pending(EvaluationWorld.UOW, approved.id()).orElseThrow();
            assertThat(world.actions.dispatch(issued)).isEqualTo(ActionOutcome.DONE);
            assertThat(world.payments.calls).singleElement().asString()
                    .startsWith(row[2] + "|" + approved.target().orElseThrow() + "|risk.action:");
        }
    }

    @Test
    @DisplayName("payments' refusals are outcomes, recorded word for word: ALREADY (the row left its hold by another"
            + " hand - the expiry won) and REFUSED (the validity lapsed)")
    void theOutcomesAreRecordedWordForWord() {
        DecisionOverride expired = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        world.payments.elsewhere.put(expired.target().orElseThrow(), "expiry");
        DecisionOverride lapsed = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        world.payments.lapsed.add(lapsed.target().orElseThrow());
        for (DecisionOverride override : List.of(expired, lapsed)) {
            OverrideActions.Issued issued = world.actions.pending(EvaluationWorld.UOW, override.id()).orElseThrow();
            world.actions.conclude(EvaluationWorld.UOW, issued, world.actions.dispatch(issued), PLATFORM,
                    OverrideWorld.correlation());
        }
        assertThat(world.overrides.find(EvaluationWorld.UOW, expired.id()).orElseThrow().actionOutcome())
                .contains(ActionOutcome.ALREADY);
        assertThat(world.overrides.find(EvaluationWorld.UOW, lapsed.id()).orElseThrow().actionOutcome())
                .contains(ActionOutcome.REFUSED);
        assertThat(world.payments.effects()).as("nothing moved").isZero();
    }

    @Test
    @DisplayName("a crash between the approval's commit and the dispatch: nothing is due within the lease; past it the"
            + " re-drive claims the permit (attempt 2), dispatches once and records DONE; no claim after")
    void aCrashBetweenApprovalAndReleaseReDrivesOnce() {
        DecisionOverride approved = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        // The approval committed; the instance died before its dispatch. Within the lease the door owns the attempt.
        assertThat(world.actions.claimNextDue(EvaluationWorld.UOW, OverrideWorld.LEASE)).isEmpty();
        world.overrides.now = world.overrides.now.plus(OverrideWorld.LEASE).plusSeconds(1);

        OverrideActions.Issued claimed = world.actions.claimNextDue(EvaluationWorld.UOW, OverrideWorld.LEASE)
                .orElseThrow();
        assertThat(claimed.override()).isEqualTo(approved.id());
        assertThat(claimed.attempts()).isEqualTo(2);
        assertThat(world.actions.claimNextDue(EvaluationWorld.UOW, OverrideWorld.LEASE))
                .as("re-stamped by the claim: another instance claims it only after the next lease").isEmpty();
        assertThat(world.actions.conclude(EvaluationWorld.UOW, claimed, world.actions.dispatch(claimed), PLATFORM,
                OverrideWorld.correlation())).isTrue();

        world.overrides.now = world.overrides.now.plus(Duration.ofDays(1));
        assertThat(world.actions.claimNextDue(EvaluationWorld.UOW, OverrideWorld.LEASE)).isEmpty();
        assertThat(world.payments.calls).hasSize(1);
        assertThat(world.payments.effects()).isEqualTo(1);
        assertThat(world.audited("risk.ActionConcluded")).singleElement()
                .satisfies(record -> assertThat(record.changeSummary().orElseThrow()).contains("attempts=2"));
    }

    @Test
    @DisplayName("a crash between the dispatch and the record: the re-drive presents the SAME key, payments replays"
            + " DONE - two calls, one effect - and the outcome is recorded once; a second record is refused")
    void aCrashBetweenDispatchAndRecordReplaysTheKey() {
        DecisionOverride approved = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        OverrideActions.Issued first = world.actions.pending(EvaluationWorld.UOW, approved.id()).orElseThrow();
        assertThat(world.actions.dispatch(first)).isEqualTo(ActionOutcome.DONE);
        // ...and the instance died before its record.
        world.overrides.now = world.overrides.now.plus(OverrideWorld.LEASE).plusSeconds(1);
        OverrideActions.Issued redriven = world.actions.claimNextDue(EvaluationWorld.UOW, OverrideWorld.LEASE)
                .orElseThrow();
        assertThat(world.actions.dispatch(redriven)).isEqualTo(ActionOutcome.DONE);
        assertThat(world.payments.calls).hasSize(2).allSatisfy(call -> assertThat(call).contains(first.key()));
        assertThat(world.payments.effects()).as("INV-IDEM-01: one effect").isEqualTo(1);
        assertThat(world.actions.conclude(EvaluationWorld.UOW, redriven, ActionOutcome.DONE, PLATFORM,
                OverrideWorld.correlation())).isTrue();
        assertThat(world.actions.conclude(EvaluationWorld.UOW, first, ActionOutcome.DONE, PLATFORM,
                OverrideWorld.correlation())).as("the door's late record loses to the leg's").isFalse();
        assertThat(world.audited("risk.ActionConcluded")).hasSize(1);
    }

    @Test
    @DisplayName("a concluded action is never re-driven: no pending action, no due permit however late, no second"
            + " dispatch")
    void aConcludedOverrideIsNeverReDriven() {
        DecisionOverride approved = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        OverrideActions.Issued issued = world.actions.pending(EvaluationWorld.UOW, approved.id()).orElseThrow();
        world.actions.conclude(EvaluationWorld.UOW, issued, world.actions.dispatch(issued), PLATFORM,
                OverrideWorld.correlation());
        world.overrides.now = world.overrides.now.plus(Duration.ofDays(30));
        assertThat(world.actions.claimNextDue(EvaluationWorld.UOW, OverrideWorld.LEASE)).isEmpty();
        assertThat(world.actions.pending(EvaluationWorld.UOW, approved.id())).isEmpty();
        assertThat(world.payments.calls).hasSize(1);
    }

    @Test
    @DisplayName("a door that throws (a flight still holding the key, a storage failure) records nothing: the permit"
            + " stands and the next attempt re-drives the same key to DONE")
    void aThrowingDoorRecordsNothing() {
        DecisionOverride approved = approved(RiskOperation.CARD_PAYMENT, OverrideResolution.PROCEED);
        OverrideActions.Issued issued = world.actions.pending(EvaluationWorld.UOW, approved.id()).orElseThrow();
        world.payments.failures.push(new IllegalStateException("an authorisation still in flight"));
        assertThatThrownBy(() -> world.actions.dispatch(issued)).isInstanceOf(IllegalStateException.class);
        DecisionOverride standing = world.overrides.find(EvaluationWorld.UOW, approved.id()).orElseThrow();
        assertThat(standing.actionOutcome()).isEmpty();
        assertThat(standing.actionDueAt()).isPresent();

        world.overrides.now = world.overrides.now.plus(OverrideWorld.LEASE).plusSeconds(1);
        OverrideActions.Issued again = world.actions.claimNextDue(EvaluationWorld.UOW, OverrideWorld.LEASE)
                .orElseThrow();
        assertThat(again.key()).isEqualTo(issued.key());
        assertThat(world.actions.dispatch(again)).isEqualTo(ActionOutcome.DONE);
        assertThat(world.payments.effects()).isEqualTo(1);
    }

    @Test
    @DisplayName("a resubmission dispatches nothing: no pending action, nothing due")
    void aResubmissionDispatchesNothing() {
        RiskAssessment review = world.sealed(RiskOperation.TRANSFER, RiskDecision.REVIEW, 100_00, THRESHOLD);
        DecisionOverride proposed = world.propose(review, OverrideResolution.PROCEED, world.officer());
        DecisionOverride approved = world.approve(proposed, world.officer()).override();
        assertThat(approved.effect()).isEqualTo(OverrideEffect.RESUBMISSION);
        assertThat(world.actions.pending(EvaluationWorld.UOW, approved.id())).isEmpty();
        world.overrides.now = world.overrides.now.plus(Duration.ofDays(1));
        assertThat(world.actions.claimNextDue(EvaluationWorld.UOW, OverrideWorld.LEASE)).isEmpty();
        assertThat(world.payments.calls).isEmpty();
        assertThat(Optional.of(OverrideEffect.RESUBMISSION).flatMap(OverrideEffect::action)).isEmpty();
    }

    // ------------------------------------------------------------------

    /** A HOLD of {@code operation} below the threshold, overridden {@code resolution} and approved by a second holder. */
    private DecisionOverride approved(RiskOperation operation, OverrideResolution resolution) {
        RiskAssessment held = world.sealed(operation, RiskDecision.HOLD, 250_00, THRESHOLD, UUID.randomUUID());
        DecisionOverride proposed = world.propose(held, resolution, world.officer());
        return world.approve(proposed, world.officer()).override();
    }
}
