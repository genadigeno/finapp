package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.risk.testing.RiskPolicyFixtures;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-act over fake owners (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 7.1 T-act, 7.2 (R6); ADR-0112 decisions 4-6;
 * {@code INV-RSK-02}, {@code INV-IDEM-01}; `P13-TSK-012`'s {@code OverrideActionTest}, folded): the claim - a permit that
 * has come, moved a lease ahead, one attempt counted, so a second claimer finds nothing; the dispatch - each kind to its
 * owner's door under {@code risk.action:<id>} and the action's reason code; the record - the owner's answer once,
 * {@code DONE}/{@code ALREADY} concluding {@code DONE} and {@code REFUSED} {@code FAILED}, never retried; a throw recording
 * nothing and the next claim presenting the same key; an override's folded action keyed by the override's own id. The
 * database's arbiters are {@code RiskActionDatabaseTest}'s (written and owed, C5).
 */
@DisplayName("T-act: one claim per lease, one key per action, the owner's answer recorded once (P13-TSK-017)")
class ActionDispatchTest {

    private static final long THRESHOLD = RiskPolicyFixtures.OVERRIDE_THRESHOLD_MINOR;

    private final OverrideWorld world = new OverrideWorld();

    @Test
    @DisplayName("an approved override's release is issued under the override's id and dispatched once under"
            + " risk.action:<override id> and its reason code: DONE recorded, audited risk.ActionDispatched and"
            + " risk.ActionConcluded as the platform, announced risk.RiskActionCompleted")
    void anOverridesReleaseIsDispatchedOnceUnderItsOwnKey() {
        DecisionOverride approved = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        RiskAction issued = world.issuedFor(approved);
        assertThat(issued.id().value()).isEqualTo(approved.id().value());
        assertThat(issued.key()).isEqualTo("risk.action:" + approved.id().value()).isEqualTo(approved.actionKey());
        assertThat(issued.source()).isEqualTo(ActionSource.OVERRIDE);
        assertThat(issued.kind()).isEqualTo(ActionKind.RELEASE_HELD_WITHDRAWAL);
        assertThat(issued.status()).isEqualTo(ActionStatus.APPROVED);

        RiskAction concluded = world.drive(issued.id()).orElseThrow();
        assertThat(concluded.status()).isEqualTo(ActionStatus.DONE);
        assertThat(concluded.outcome()).contains(ActionOutcome.DONE);
        assertThat(concluded.attempts()).isEqualTo(1);
        assertThat(concluded.nextAttemptAt()).isEmpty();
        assertThat(world.payments.calls).containsExactly("release|" + approved.target().orElseThrow() + "|"
                + issued.key() + "|FALSE_POSITIVE");
        assertThat(world.payments.actors).as("the platform executes; the officers decided")
                .containsExactly(Actor.SYSTEM);
        DecisionOverride read = world.overrides.find(EvaluationWorld.UOW, approved.id()).orElseThrow();
        assertThat(read.actionOutcome()).as("the override reads its action's state").contains(ActionOutcome.DONE);
        assertThat(read.awaitsOutcome()).isFalse();
        assertThat(world.audited("risk.ActionDispatched")).singleElement().satisfies(record -> {
            assertThat(record.actor()).isEqualTo(Actor.SYSTEM);
            assertThat(record.targetType()).isEqualTo("risk_action");
            assertThat(record.targetId()).isEqualTo(approved.id().value().toString());
        });
        assertThat(world.audited("risk.ActionConcluded")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.actor()).isEqualTo(Actor.SYSTEM);
            assertThat(record.changeSummary()).contains("kind=RELEASE_HELD_WITHDRAWAL, source=OVERRIDE, outcome=DONE,"
                    + " attempts=1");
        });
        assertThat(world.events).extracting(event -> event.eventType())
                .containsExactly("risk.RiskActionRequested", "risk.RiskActionCompleted");
        assertThat(world.announced("risk.RiskActionCompleted")).singleElement().satisfies(payload ->
                assertThat(payload).containsEntry("outcome", "DONE").containsEntry("status", "DONE")
                        .containsEntry("overrideId", approved.id().value().toString())
                        .containsEntry("kind", "RELEASE_HELD_WITHDRAWAL").doesNotContainKey("reason"));
        assertThat(world.payments.effects()).isEqualTo(1);
    }

    @Test
    @DisplayName("each kind reaches its own owner's door, the target the case's own subject or the judged movement:"
            + " identity's three (given the approver as the deciding person), merchant's suspension, payments' four")
    void eachKindReachesItsDoor() {
        for (Object[] row : List.of(
                new Object[] {RiskOperation.WITHDRAWAL, OverrideResolution.REFUSE, "deny"},
                new Object[] {RiskOperation.CARD_PAYMENT, OverrideResolution.PROCEED, "capture"},
                new Object[] {RiskOperation.CARD_PAYMENT, OverrideResolution.REFUSE, "void"})) {
            world.payments.calls.clear();
            DecisionOverride approved = approved((RiskOperation) row[0], (OverrideResolution) row[1]);
            world.drive(RiskActionId.ofOverride(approved.id())).orElseThrow();
            assertThat(world.payments.calls).singleElement().asString()
                    .startsWith(row[2] + "|" + approved.target().orElseThrow() + "|risk.action:" + approved.id().value());
        }
        for (Object[] row : List.of(
                new Object[] {ActionKind.SUSPEND_IDENTITY, "suspend-identity"},
                new Object[] {ActionKind.REINSTATE_IDENTITY, "reinstate-identity"},
                new Object[] {ActionKind.REVOKE_SESSIONS, "revoke-sessions"})) {
            world.payments.calls.clear();
            world.payments.deciders.clear();
            OverrideWorld.Taken taken = world.partyCase(CaseKind.ATO);
            RiskAction proposed = world.propose(taken, (ActionKind) row[0], Optional.empty()).action();
            Actor approver = world.analyst();
            world.approve(proposed, approver);
            world.drive(proposed.id()).orElseThrow();
            assertThat(world.payments.calls).singleElement().isEqualTo(row[1] + "|" + taken.riskCase().subject().ref()
                    + "|risk.action:" + proposed.id().value() + "|RISK_CASE_ATO");
            if (row[0] != ActionKind.REVOKE_SESSIONS) {
                assertThat(world.payments.deciders).as("identity is given the decider, never the subject")
                        .containsExactly(approver.id());
            }
        }
        world.payments.calls.clear();
        OverrideWorld.Taken merchant = world.merchantCase();
        RiskAction suspension = world.propose(merchant, ActionKind.SUSPEND_MERCHANT, Optional.empty()).action();
        world.approve(suspension, world.analyst());
        world.drive(suspension.id()).orElseThrow();
        assertThat(world.payments.calls).singleElement().isEqualTo("suspend-merchant|"
                + merchant.riskCase().subject().ref() + "|risk.action:" + suspension.id().value()
                + "|RISK_CASE_MERCHANT_ABUSE");
    }

    @Test
    @DisplayName("the owner's refusals are outcomes, recorded word for word and never retried: ALREADY concludes DONE"
            + " (the state already held), REFUSED concludes FAILED; neither is ever claimed again")
    void theOutcomesAreRecordedWordForWord() {
        DecisionOverride expired = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        world.payments.elsewhere.put(expired.target().orElseThrow(), "expiry");
        DecisionOverride lapsed = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        world.payments.lapsed.add(lapsed.target().orElseThrow());

        RiskAction already = world.drive(RiskActionId.ofOverride(expired.id())).orElseThrow();
        RiskAction refused = world.drive(RiskActionId.ofOverride(lapsed.id())).orElseThrow();
        assertThat(already.status()).isEqualTo(ActionStatus.DONE);
        assertThat(already.outcome()).contains(ActionOutcome.ALREADY);
        assertThat(refused.status()).as("a refusal is a recorded failure - re-proposable, never re-dispatched")
                .isEqualTo(ActionStatus.FAILED);
        assertThat(refused.outcome()).contains(ActionOutcome.REFUSED);
        assertThat(world.payments.effects()).as("nothing moved").isZero();

        world.advance(Duration.ofDays(30));
        assertThat(world.driveNextDue()).as("scenario 7: risk retries nothing the owner answered").isEmpty();
        assertThat(world.payments.calls).hasSize(2);
    }

    @Test
    @DisplayName("a crash between the approval's commit and the dispatch: the permit is due at once, so the schedule's"
            + " claim takes it once (attempt 1) - a second claimer within the lease finds nothing - and records DONE")
    void aCrashBetweenApprovalAndDispatchIsReDrivenOnce() {
        OverrideWorld.Held held = world.heldCase(RiskOperation.WITHDRAWAL);
        RiskAction proposed = world.propose(held.taken(), ActionKind.RELEASE_HELD_WITHDRAWAL,
                Optional.of(held.movement())).action();
        world.approve(proposed, world.analyst());
        // The approving instance died before its own drive: any instance's schedule claims the due permit.

        RiskAction claimed = OverrideWorld.asSystem(() -> world.actions.claimNextDue(EvaluationWorld.UOW,
                OverrideWorld.LEASE)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(proposed.id());
        assertThat(claimed.status()).isEqualTo(ActionStatus.DISPATCHED);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.nextAttemptAt()).contains(world.now().plus(OverrideWorld.LEASE));
        assertThat(world.drive(proposed.id())).as("the door's late claim within the lease finds nothing").isEmpty();
        assertThat(world.driveNextDue()).as("nor does another schedule's").isEmpty();

        ActionOutcome outcome = world.actions.dispatch(claimed);
        assertThat(OverrideWorld.asSystem(() -> world.actions.conclude(EvaluationWorld.UOW, claimed, outcome,
                OverrideWorld.correlation()))).isPresent();
        world.advance(Duration.ofDays(1));
        assertThat(world.driveNextDue()).isEmpty();
        assertThat(world.payments.calls).hasSize(1);
        assertThat(world.payments.effects()).isEqualTo(1);
    }

    @Test
    @DisplayName("INV-IDEM-01: a crash between the dispatch and the record - past the lease the re-claim (attempt 2)"
            + " presents the SAME key, the owner replays DONE: two calls, one effect, the outcome recorded once and the"
            + " late record refused")
    void aReDrivePresentsTheSameKey() {
        DecisionOverride approved = approved(RiskOperation.WITHDRAWAL, OverrideResolution.PROCEED);
        RiskAction first = OverrideWorld.asSystem(() -> world.actions.claim(EvaluationWorld.UOW,
                RiskActionId.ofOverride(approved.id()), OverrideWorld.LEASE)).orElseThrow();
        assertThat(world.actions.dispatch(first)).isEqualTo(ActionOutcome.DONE);
        // ...and the instance died before its record.
        world.advance(OverrideWorld.LEASE.plusSeconds(1));
        RiskAction redriven = OverrideWorld.asSystem(() -> world.actions.claimNextDue(EvaluationWorld.UOW,
                OverrideWorld.LEASE)).orElseThrow();
        assertThat(redriven.attempts()).isEqualTo(2);
        assertThat(redriven.key()).isEqualTo(first.key());
        assertThat(world.actions.dispatch(redriven)).isEqualTo(ActionOutcome.DONE);
        assertThat(world.payments.calls).hasSize(2).allSatisfy(call -> assertThat(call).contains(first.key()));
        assertThat(world.payments.effects()).as("INV-IDEM-01: one effect").isEqualTo(1);
        assertThat(OverrideWorld.asSystem(() -> world.actions.conclude(EvaluationWorld.UOW, redriven,
                ActionOutcome.DONE, OverrideWorld.correlation()))).isPresent();
        assertThat(OverrideWorld.asSystem(() -> world.actions.conclude(EvaluationWorld.UOW, first, ActionOutcome.DONE,
                OverrideWorld.correlation()))).as("the late record loses").isEmpty();
        assertThat(world.audited("risk.ActionConcluded")).singleElement()
                .satisfies(record -> assertThat(record.changeSummary().orElseThrow()).contains("attempts=2"));
        assertThat(world.audited("risk.ActionDispatched")).as("the first attempt is the dispatch; a re-claim is counted")
                .hasSize(1);
    }

    @Test
    @DisplayName("a door that throws (a flight still holding the key, an unreachable owner) records nothing: the action"
            + " stays DISPATCHED under its advanced permit and the next claim re-drives the same key to DONE")
    void aThrowingDoorRecordsNothing() {
        DecisionOverride approved = approved(RiskOperation.CARD_PAYMENT, OverrideResolution.PROCEED);
        RiskActionId id = RiskActionId.ofOverride(approved.id());
        RiskAction claimed = OverrideWorld.asSystem(() -> world.actions.claim(EvaluationWorld.UOW, id,
                OverrideWorld.LEASE)).orElseThrow();
        world.payments.failures.push(new IllegalStateException("an authorisation still in flight"));
        assertThatThrownBy(() -> world.actions.dispatch(claimed)).isInstanceOf(IllegalStateException.class);
        RiskAction standing = world.actionStore.find(EvaluationWorld.UOW, id).orElseThrow();
        assertThat(standing.status()).isEqualTo(ActionStatus.DISPATCHED);
        assertThat(standing.outcome()).isEmpty();
        assertThat(standing.nextAttemptAt()).isPresent();

        world.advance(OverrideWorld.LEASE.plusSeconds(1));
        RiskAction again = world.driveNextDue().orElseThrow();
        assertThat(again.key()).isEqualTo(claimed.key());
        assertThat(again.outcome()).contains(ActionOutcome.DONE);
        assertThat(world.payments.effects()).isEqualTo(1);
    }

    @Test
    @DisplayName("a resubmission issues nothing; a case's PROPOSED or REJECTED action is never claimed; a concluded"
            + " action is never claimed again however late")
    void onlyAnApprovedActionIsEverClaimed() {
        RiskAssessment review = world.sealed(RiskOperation.TRANSFER, RiskDecision.REVIEW, 100_00, THRESHOLD);
        DecisionOverride proposed = world.propose(review, OverrideResolution.PROCEED, world.officer());
        world.approve(proposed, world.officer());
        OverrideWorld.Taken taken = world.partyCase(CaseKind.FRAUD);
        RiskAction standing = world.propose(taken, ActionKind.REVOKE_SESSIONS, Optional.empty()).action();
        world.advance(Duration.ofDays(1));
        assertThat(world.driveNextDue()).isEmpty();
        assertThat(world.drive(standing.id())).as("a proposal awaits a second person").isEmpty();
        assertThat(world.payments.calls).isEmpty();
        assertThat(world.actionStore.all()).singleElement().satisfies(action ->
                assertThat(action.status()).isEqualTo(ActionStatus.PROPOSED));
    }

    @Test
    @DisplayName("a kind with no owning command is never dispatched - refused loudly if ever reached")
    void anUnavailableKindIsNeverDispatched() {
        ActionDispatch dispatch = new ActionDispatch(world.payments, world.payments, world.payments, world.payments);
        for (ActionKind kind : List.of(ActionKind.SUSPEND_ACCOUNT, ActionKind.REINSTATE_ACCOUNT,
                ActionKind.BLOCK_BENEFICIARY, ActionKind.RESCREEN_PARTY)) {
            RiskAction planted = new RiskAction(RiskActionId.of(EvaluationWorld.IDS.next()), ActionSource.CASE,
                    Optional.of(RiskCaseId.of(EvaluationWorld.IDS.next())), Optional.empty(), kind, kind.target(),
                    UUID.randomUUID(), "RISK_CASE_FRAUD", ActionStatus.DISPATCHED,
                    new RiskCase.Person("p", com.finapp.platform.security.ActorType.CUSTOMER), Optional.of(world.now()),
                    Optional.of(new RiskCase.Person("q", com.finapp.platform.security.ActorType.CUSTOMER)),
                    Optional.of(world.now()), Optional.of(UUID.randomUUID()), Optional.of(world.now()), 1,
                    Optional.of(world.now()), Optional.empty(), Optional.empty(), "flow");
            assertThatThrownBy(() -> dispatch.dispatch(planted)).as(kind.name())
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(world.payments.calls).isEmpty();
    }

    // ------------------------------------------------------------------

    /** A HOLD of {@code operation} below the threshold, overridden {@code resolution} and approved by a second holder. */
    private DecisionOverride approved(RiskOperation operation, OverrideResolution resolution) {
        RiskAssessment held = world.sealed(operation, RiskDecision.HOLD, 250_00, THRESHOLD, UUID.randomUUID());
        DecisionOverride proposed = world.propose(held, resolution, world.officer());
        return world.approve(proposed, world.officer()).override();
    }
}
