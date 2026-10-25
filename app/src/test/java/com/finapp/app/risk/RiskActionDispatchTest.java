package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ActionKind;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.ActionStatus;
import com.finapp.risk.CaseKind;
import com.finapp.risk.DecisionOverride;
import com.finapp.risk.IdentityActions;
import com.finapp.risk.MerchantActions;
import com.finapp.risk.RiskAction;
import com.finapp.risk.RiskActionId;
import com.finapp.risk.RiskCase;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-act as {@code app} drives it (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 7.1 T-act, 7.2 (R6); ADR-0112
 * decisions 4-6; {@code INV-RSK-02}, {@code INV-IDEM-01}) - the driver over the real machine, with payments' held doors
 * faked to refuse any caller payments refuses: <strong>the routed defect's proof</strong> - `-016`'s gate found that
 * `-012`'s approval door dispatched under the operator's {@code CUSTOMER}-typed session actor, which payments refuses,
 * so the first attempt always threw; an approval's dispatch now runs as the platform and succeeds on its
 * <strong>first</strong> attempt. Also: the key {@code risk.action:<id>} and the action's reason code at the owner's door,
 * on no transaction; the owner's answer recorded once and counted; a crash between the approval and its dispatch
 * re-driven once by the schedule's drive; an override's folded action presenting {@code risk.action:<override id>}.
 */
@DisplayName("T-act as the platform: an approval's dispatch succeeds on its first attempt, one key, one record"
        + " (P13-TSK-017)")
class RiskActionDispatchTest {

    /** Identity's doors, for a suite that dispatches none of them. */
    static final IdentityActions NO_IDENTITY = new IdentityActions() {
        @Override
        public ActionOutcome suspend(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
            throw new AssertionError("no identity action in this suite");
        }

        @Override
        public ActionOutcome reinstate(UUID partyId, String decidedBy, String idempotencyKey, String reason) {
            throw new AssertionError("no identity action in this suite");
        }

        @Override
        public ActionOutcome revokeSessions(UUID partyId, String idempotencyKey, String reason) {
            throw new AssertionError("no identity action in this suite");
        }
    };

    /** Merchant's door, for a suite that dispatches none. */
    static final MerchantActions NO_MERCHANT = (merchantId, idempotencyKey, reason) -> {
        throw new AssertionError("no merchant action in this suite");
    };

    private final RiskActionWorld world = new RiskActionWorld();

    @Test
    @DisplayName("the routed defect: a CUSTOMER-typed analyst's approval of a held withdrawal's release is dispatched"
            + " AS THE PLATFORM after the approval commits - payments admits it on the FIRST attempt: DONE, one attempt,"
            + " under risk.action:<id> and RISK_CASE_FRAUD, on no transaction; the approver recorded as the decider")
    void anApprovalsDispatchSucceedsOnTheFirstAttempt() {
        RiskActionWorld.Held held = world.heldWithdrawalCase();
        RiskActionDesk.RiskActionView proposed = world.propose(held.taken(), "RELEASE_HELD_WITHDRAWAL",
                held.movement().toString());
        Actor approver = world.analyst();
        assertThat(approver.type()).as("as the session interceptor builds every identity").isEqualTo(ActorType.CUSTOMER);

        RiskActionDesk.RiskActionView approved = world.approve(proposed, approver, "approve-1");

        assertThat(approved.status()).isEqualTo("DONE");
        assertThat(approved.outcome()).isEqualTo("DONE");
        assertThat(approved.attempts()).as("the first attempt succeeded - no re-drive was needed").isEqualTo(1);
        assertThat(world.owners.calls).containsExactly("release|" + held.movement() + "|risk.action:"
                + proposed.actionId() + "|RISK_CASE_FRAUD|depth=0|SYSTEM");
        assertThat(world.count(RiskActionMetrics.ACTION_FAILED, "kind", "RELEASE_HELD_WITHDRAWAL")).isZero();
        RiskAction action = world.actionOf(proposed.actionId());
        assertThat(action.decidedBy()).as("risk records the person who decided")
                .contains(RiskCase.Person.of(approver));
        assertThat(world.audited("risk.ActionApproved")).singleElement()
                .satisfies(record -> assertThat(record.actor()).isEqualTo(approver));
        assertThat(world.audited("risk.ActionDispatched")).singleElement()
                .satisfies(record -> assertThat(record.actor()).isEqualTo(Actor.SYSTEM));
        assertThat(world.audited("risk.ActionConcluded")).singleElement()
                .satisfies(record -> assertThat(record.actor()).isEqualTo(Actor.SYSTEM));
        assertThat(world.events).containsSubsequence("risk.RiskActionRequested", "risk.RiskActionCompleted");
        assertThat(SecurityContext.current()).as("the platform's scope closed behind the drive").isEmpty();
    }

    @Test
    @DisplayName("identity's doors are given the action's party, the approver as the deciding person and the platform as"
            + " the acting principal - REVOKE_SESSIONS and SUSPEND_IDENTITY, the C10 pair")
    void identitysDoorsAreCalledAsThePlatform() {
        RiskActionWorld.Taken ato = world.partyCase(CaseKind.ATO);
        RiskActionDesk.RiskActionView revoke = world.propose(ato, "REVOKE_SESSIONS", null);
        RiskActionDesk.RiskActionView suspend = world.propose(ato, "SUSPEND_IDENTITY", null);
        Actor approver = world.analyst();
        world.approve(revoke, approver, "a-revoke");
        world.approve(suspend, approver, "a-suspend");
        String party = ato.riskCase().subject().ref();
        assertThat(world.owners.calls).containsExactly(
                "revoke-sessions|" + party + "|risk.action:" + revoke.actionId() + "|RISK_CASE_ATO|depth=0|SYSTEM",
                "suspend-identity|" + party + "|risk.action:" + suspend.actionId() + "|RISK_CASE_ATO|depth=0|SYSTEM");
        assertThat(world.owners.deciders).containsExactly(approver.id());
    }

    @Test
    @DisplayName("the owner's answer is recorded once and counted: ALREADY concludes DONE(ALREADY); a replay of the"
            + " approval's key finds nothing due - no second call")
    void theAnswerIsRecordedOnce() {
        RiskActionWorld.Held held = world.heldWithdrawalCase();
        world.owners.scripted.put(held.movement(), ActionOutcome.ALREADY);
        RiskActionDesk.RiskActionView proposed = world.propose(held.taken(), "DENY_HELD_WITHDRAWAL",
                held.movement().toString());
        Actor approver = world.analyst();
        RiskCaseController.RiskCaseDecisionRequest body = new RiskCaseController.RiskCaseDecisionRequest(
                world.caseOf(proposed.caseId()).revision(), "confirmed");
        RiskActionDesk.RiskActionView approved = RiskActionWorld.as(approver, () -> world.desk.approve("approve-again",
                proposed.caseId(), proposed.actionId(), body));
        assertThat(approved.status()).isEqualTo("DONE");
        assertThat(approved.outcome()).isEqualTo("ALREADY");

        RiskActionDesk.RiskActionView replayed = RiskActionWorld.as(approver, () -> world.desk.approve("approve-again",
                proposed.caseId(), proposed.actionId(), body));
        assertThat(replayed.outcome()).isEqualTo("ALREADY");
        assertThat(world.owners.calls).hasSize(1);
        assertThat(world.audited("risk.ActionConcluded")).hasSize(1);
        assertThat(world.registry.get(RiskActionMetrics.ACTION).tag("kind", "DENY_HELD_WITHDRAWAL")
                .tag("outcome", "already").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a crash between the approval's commit and its drive: the approval committed alone; the schedule's"
            + " drive claims the due permit once and records DONE; the next tick finds nothing")
    void aCrashBetweenApprovalAndDispatchIsReDrivenOnce() {
        RiskActionWorld.Held held = world.heldWithdrawalCase();
        RiskActionDesk.RiskActionView proposed = world.propose(held.taken(), "RELEASE_HELD_WITHDRAWAL",
                held.movement().toString());
        Actor approver = world.analyst();
        // The approval's transaction alone - the instance died before the door's drive.
        RiskCase current = world.caseOf(proposed.caseId());
        RiskActionWorld.as(approver, () -> world.transactions.inTransaction(uow -> world.actions.approve(uow,
                current.id(), RiskActionId.of(UUID.fromString(proposed.actionId())), current.revision(), "ok",
                approver, RiskActionWorld.correlation())));
        assertThat(world.owners.calls).isEmpty();

        assertThat(world.driver.driveDue()).isEqualTo(1);
        assertThat(world.driver.driveDue()).as("concluded - nothing due").isZero();
        assertThat(world.owners.calls).singleElement().asString().endsWith("|depth=0|SYSTEM");
        assertThat(world.owners.effects).isEqualTo(1);
        assertThat(world.actionOf(proposed.actionId()).status()).isEqualTo(ActionStatus.DONE);
    }

    @Test
    @DisplayName("a dispatch that throws is counted action.failed and records nothing; within the lease nothing is due;"
            + " past it the schedule re-drives the SAME key to DONE - one effect")
    void aThrowingDispatchIsReDrivenUnderTheSameKey() {
        RiskActionWorld.Held held = world.heldWithdrawalCase();
        RiskActionDesk.RiskActionView proposed = world.propose(held.taken(), "RELEASE_HELD_WITHDRAWAL",
                held.movement().toString());
        world.owners.failures.push(new IllegalStateException("the push rail timed out"));
        RiskActionDesk.RiskActionView approved = world.approve(proposed, world.analyst(), "approve-throws");
        assertThat(approved.status()).isEqualTo("DISPATCHED");
        assertThat(approved.outcome()).isNull();
        assertThat(world.count(RiskActionMetrics.ACTION_FAILED, "kind", "RELEASE_HELD_WITHDRAWAL")).isEqualTo(1);
        assertThat(world.driver.driveDue()).as("the lease holds it").isZero();

        world.actionStore.now = world.actionStore.now.plus(RiskActionWorld.LEASE).plusSeconds(1);
        assertThat(world.driver.driveDue()).isEqualTo(1);
        RiskAction done = world.actionOf(proposed.actionId());
        assertThat(done.status()).isEqualTo(ActionStatus.DONE);
        assertThat(done.attempts()).isEqualTo(2);
        assertThat(world.owners.calls).hasSize(2).allSatisfy(call -> assertThat(call)
                .contains("|risk.action:" + proposed.actionId() + "|"));
        assertThat(world.owners.effects).isEqualTo(1);
    }

    @Test
    @DisplayName("the owner answers but the record does not commit: the approval still answers 202 DISPATCHED, the failure"
            + " is counted action.failed, nothing is recorded; past the lease the schedule re-drives the SAME key, the"
            + " owner replays its answer and the record lands - one effect")
    void aRecordThatFailsIsReDrivenUnderTheSameKey() {
        RiskActionWorld.Held held = world.heldWithdrawalCase();
        RiskActionDesk.RiskActionView proposed = world.propose(held.taken(), "RELEASE_HELD_WITHDRAWAL",
                held.movement().toString());
        world.owners.afterAnswer = () -> {
            // The owner committed its effect; the transaction that records it is lost.
            world.failTransactions.push(new IllegalStateException("the record's connection was lost"));
            world.owners.afterAnswer = () -> {};
        };
        RiskActionDesk.RiskActionView approved = world.approve(proposed, world.analyst(), "approve-record-lost");
        assertThat(approved.status()).as("the approval committed; the door does not fail").isEqualTo("DISPATCHED");
        assertThat(approved.outcome()).isNull();
        assertThat(world.count(RiskActionMetrics.ACTION_FAILED, "kind", "RELEASE_HELD_WITHDRAWAL")).isEqualTo(1);
        assertThat(world.owners.effects).isEqualTo(1);

        world.actionStore.now = world.actionStore.now.plus(RiskActionWorld.LEASE).plusSeconds(1);
        assertThat(world.driver.driveDue()).isEqualTo(1);
        RiskAction done = world.actionOf(proposed.actionId());
        assertThat(done.status()).isEqualTo(ActionStatus.DONE);
        assertThat(done.outcome()).contains(ActionOutcome.DONE);
        assertThat(done.attempts()).isEqualTo(2);
        assertThat(world.owners.calls).hasSize(2).allSatisfy(call -> assertThat(call)
                .contains("|risk.action:" + proposed.actionId() + "|"));
        assertThat(world.owners.effects).as("INV-IDEM-01: the owner replayed its answer").isEqualTo(1);
    }

    @Test
    @DisplayName("an override's folded action keeps its key: approved by two officers, it is dispatched under"
            + " risk.action:<override id> as the platform, on its first attempt")
    void anOverridesFoldedActionKeepsItsKey() {
        DecisionOverride approved = world.approvedOverride();
        RiskActionId id = RiskActionWorld.actionId(approved.id());
        assertThat(world.driver.driveIfDue(id)).contains(ActionOutcome.DONE);
        assertThat(world.owners.calls).singleElement().isEqualTo("release|" + approved.target().orElseThrow()
                + "|risk.action:" + approved.id().value() + "|FALSE_POSITIVE|depth=0|SYSTEM");
        RiskAction action = world.actionStore.find(RiskEvaluationWorld.UNTOUCHABLE, id).orElseThrow();
        assertThat(action.attempts()).isEqualTo(1);
        assertThat(action.key()).isEqualTo(approved.actionKey());
        assertThat(world.overrideStore.find(RiskEvaluationWorld.UNTOUCHABLE, approved.id()).orElseThrow()
                .actionOutcome()).as("the override reads its action's outcome").contains(ActionOutcome.DONE);
        assertThat(Optional.of(ActionKind.RELEASE_HELD_WITHDRAWAL)).contains(action.kind());
    }
}
