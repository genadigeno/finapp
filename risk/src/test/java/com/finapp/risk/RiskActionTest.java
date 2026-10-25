package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The action machine in T-case over in-memory stores (`P13-TSK-017`; {@code RISK_LIFECYCLES.md} section 4.7; ADR-0112
 * decisions 1-3; owner decision C10; {@code INV-RSK-06}, {@code INV-RSK-07}, {@code INV-AUD-04}): the machine's edges and
 * every invalid one; what each case kind proposes and on what (never a target the case does not hold); the gaps and the
 * rescreening refused; no credential reset; four eyes - the proposer refused, a second person approves, the same
 * approver's retry converges; a live twin refused and a {@code FAILED} action re-proposable; an untaken, disposed or
 * stale case refused; a disposition waiting for a standing proposal. The database's arbiters are
 * {@code RiskActionDatabaseTest}'s (written and owed, C5).
 */
@DisplayName("the risk action machine: four eyes, what a case proposes, one live per target (P13-TSK-017)")
class RiskActionTest {

    private final OverrideWorld world = new OverrideWorld();

    @Test
    @DisplayName("the machine: PROPOSED -> APPROVED | REJECTED; APPROVED -> DISPATCHED -> DISPATCHED | DONE | FAILED;"
            + " nothing out of DONE, FAILED or REJECTED - a FAILED action is never re-dispatched by itself")
    void theMachinesEdges() {
        assertThat(ActionStatus.PROPOSED.permittedTransitions())
                .containsExactlyInAnyOrder(ActionStatus.APPROVED, ActionStatus.REJECTED);
        assertThat(ActionStatus.APPROVED.permittedTransitions()).containsExactly(ActionStatus.DISPATCHED);
        assertThat(ActionStatus.DISPATCHED.permittedTransitions())
                .containsExactlyInAnyOrder(ActionStatus.DISPATCHED, ActionStatus.DONE, ActionStatus.FAILED);
        for (ActionStatus terminal : List.of(ActionStatus.DONE, ActionStatus.FAILED, ActionStatus.REJECTED)) {
            assertThat(terminal.terminal()).as(terminal.name()).isTrue();
            assertThat(terminal.live()).as(terminal.name() + " releases the live unique").isFalse();
        }
        for (ActionStatus from : ActionStatus.values()) {
            for (ActionStatus to : ActionStatus.values()) {
                boolean edge = switch (from) {
                    case PROPOSED -> to == ActionStatus.APPROVED || to == ActionStatus.REJECTED;
                    case APPROVED -> to == ActionStatus.DISPATCHED;
                    case DISPATCHED -> to == ActionStatus.DISPATCHED || to == ActionStatus.DONE
                            || to == ActionStatus.FAILED;
                    case DONE, FAILED, REJECTED -> false;
                };
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isEqualTo(edge);
            }
        }
        assertThat(ActionStatus.FAILED.canTransitionTo(ActionStatus.DISPATCHED)).isFalse();
        assertThat(ActionStatus.APPROVED.canTransitionTo(ActionStatus.PROPOSED)).isFalse();
        assertThat(ActionStatus.DISPATCHED.canTransitionTo(ActionStatus.APPROVED)).isFalse();
        assertThat(RiskAction.concludedBy(ActionOutcome.DONE)).isEqualTo(ActionStatus.DONE);
        assertThat(RiskAction.concludedBy(ActionOutcome.ALREADY)).isEqualTo(ActionStatus.DONE);
        assertThat(RiskAction.concludedBy(ActionOutcome.REFUSED)).isEqualTo(ActionStatus.FAILED);
    }

    @Test
    @DisplayName("C10: an ATO case proposes REVOKE_SESSIONS and SUSPEND_IDENTITY on its own party - its id the"
            + " investigator action's, its key risk.action:<id>, audited risk.ActionProposed; no credential reset exists")
    void anAtoCaseProposesTheC10Pair() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.ATO);
        RiskActions.Proposed revoke = world.propose(taken, ActionKind.REVOKE_SESSIONS, Optional.empty());
        RiskActions.Proposed suspend = world.propose(taken, ActionKind.SUSPEND_IDENTITY, Optional.empty());
        for (RiskActions.Proposed proposed : List.of(revoke, suspend)) {
            RiskAction action = proposed.action();
            assertThat(action.status()).isEqualTo(ActionStatus.PROPOSED);
            assertThat(action.source()).isEqualTo(ActionSource.CASE);
            assertThat(action.caseId()).contains(taken.riskCase().id());
            assertThat(action.targetKind()).isEqualTo(ActionTarget.PARTY);
            assertThat(action.target().toString()).as("the case's own party").isEqualTo(taken.riskCase().subject()
                    .ref());
            assertThat(action.reasonCode()).isEqualTo("RISK_CASE_ATO");
            assertThat(action.key()).isEqualTo("risk.action:" + action.id().value());
            assertThat(world.caseStore.allActions()).anySatisfy(investigator -> {
                assertThat(investigator.id()).isEqualTo(action.id().value());
                assertThat(investigator.kind()).isEqualTo(CaseActionKind.ACTION_PROPOSED);
                assertThat(investigator.text()).isPresent();
            });
        }
        assertThat(suspend.riskCase().revision()).as("each proposal a revision step").isEqualTo(
                revoke.riskCase().revision() + 1);
        assertThat(world.audited("risk.ActionProposed")).hasSize(2).allSatisfy(record -> {
            assertThat(record.actor()).isEqualTo(taken.investigator());
            assertThat(record.targetType()).isEqualTo("risk_action");
            assertThat(record.reason()).isPresent();
            assertThat(record.changeSummary().orElseThrow()).doesNotContain(taken.riskCase().subject().ref());
        });
        assertThat(Arrays.stream(ActionKind.values()).map(Enum::name))
                .as("C10: the platform never resets a credential").noneMatch(name -> name.contains("CREDENTIAL")
                        || name.contains("PASSWORD") || name.contains("RESET") || name.contains("REVERS"));
    }

    @Test
    @DisplayName("what a case proposes: identity's three on a party's case, SUSPEND_MERCHANT on a merchant-abuse case,"
            + " a held movement's resolution only where the case holds it; COLLECTIONS nothing; the gaps and the"
            + " rescreening never")
    void whatEachCaseKindProposes() {
        Set<ActionKind> identity = EnumSet.of(ActionKind.SUSPEND_IDENTITY, ActionKind.REINSTATE_IDENTITY,
                ActionKind.REVOKE_SESSIONS);
        Set<ActionKind> held = EnumSet.of(ActionKind.RELEASE_HELD_WITHDRAWAL, ActionKind.DENY_HELD_WITHDRAWAL,
                ActionKind.CAPTURE_HELD_PAYMENT, ActionKind.VOID_HELD_PAYMENT);
        for (CaseKind kind : CaseKind.values()) {
            for (SubjectKind subject : List.of(SubjectKind.PARTY, SubjectKind.MERCHANT)) {
                Set<ActionKind> admitted = Arrays.stream(ActionKind.values())
                        .filter(action -> ActionRules.admits(kind, subject, action))
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(ActionKind.class)));
                Set<ActionKind> expected = EnumSet.noneOf(ActionKind.class);
                if (subject == SubjectKind.PARTY && kind != CaseKind.MERCHANT_ABUSE && kind != CaseKind.COLLECTIONS) {
                    expected.addAll(identity);
                }
                if (subject == SubjectKind.MERCHANT && kind == CaseKind.MERCHANT_ABUSE) {
                    expected.add(ActionKind.SUSPEND_MERCHANT);
                }
                if (kind != CaseKind.COLLECTIONS) {
                    expected.addAll(held);
                }
                assertThat(admitted).as(kind + " of a " + subject).isEqualTo(expected);
            }
        }
        assertThat(Arrays.stream(ActionKind.values()).filter(kind -> !kind.available()))
                .as("no owning command: accounts' suspension, crossborder's block").containsExactlyInAnyOrder(
                        ActionKind.SUSPEND_ACCOUNT, ActionKind.REINSTATE_ACCOUNT, ActionKind.BLOCK_BENEFICIARY);

        OverrideWorld.Taken fraud = world.partyCase(CaseKind.FRAUD);
        for (ActionKind refused : List.of(ActionKind.SUSPEND_ACCOUNT, ActionKind.REINSTATE_ACCOUNT,
                ActionKind.BLOCK_BENEFICIARY, ActionKind.RESCREEN_PARTY, ActionKind.SUSPEND_MERCHANT)) {
            assertThatThrownBy(() -> world.propose(fraud, refused, Optional.of(UUID.randomUUID())))
                    .as(refused.name()).isInstanceOf(RiskActions.ActionNotProposable.class);
        }
        OverrideWorld.Taken merchant = world.merchantCase();
        assertThatThrownBy(() -> world.propose(merchant, ActionKind.SUSPEND_IDENTITY, Optional.empty()))
                .isInstanceOf(RiskActions.ActionNotProposable.class);
        assertThatThrownBy(() -> world.propose(fraud, ActionKind.SUSPEND_IDENTITY, Optional.of(UUID.randomUUID())))
                .as("a party's action names no other party").isInstanceOf(RiskActions.ActionNotProposable.class);
        assertThatThrownBy(() -> world.propose(fraud, ActionKind.RELEASE_HELD_WITHDRAWAL,
                Optional.of(UUID.randomUUID()))).as("a movement the case does not hold")
                .isInstanceOf(RiskActions.ActionNotProposable.class);
        assertThatThrownBy(() -> world.propose(fraud, ActionKind.RELEASE_HELD_WITHDRAWAL, Optional.empty()))
                .as("a held movement's action names it").isInstanceOf(Cases.CaseRequestInvalid.class);
        RiskCase current = world.caseOf(fraud.riskCase().id());
        assertThatThrownBy(() -> world.actions.propose(EvaluationWorld.UOW, current.id(), current.revision(),
                "RESET_PASSWORD", Optional.empty(), "x", fraud.investigator(), OverrideWorld.correlation()))
                .isInstanceOf(Cases.CaseRequestInvalid.class);
        assertThat(world.actionStore.all()).as("nothing was written").isEmpty();
        assertThat(world.caseOf(fraud.riskCase().id()).revision()).isEqualTo(fraud.riskCase().revision());
    }

    @Test
    @DisplayName("a case holding a held withdrawal proposes its release or denial on that withdrawal, a held card"
            + " payment its capture or void - the movement its opening assessment sealed HOLD")
    void aHeldMovementsCaseResolvesIt() {
        OverrideWorld.Held withdrawal = world.heldCase(RiskOperation.WITHDRAWAL);
        RiskAction release = world.propose(withdrawal.taken(), ActionKind.RELEASE_HELD_WITHDRAWAL,
                Optional.of(withdrawal.movement())).action();
        assertThat(release.targetKind()).isEqualTo(ActionTarget.WITHDRAWAL);
        assertThat(release.target()).isEqualTo(withdrawal.movement());
        assertThatThrownBy(() -> world.propose(withdrawal.taken(), ActionKind.CAPTURE_HELD_PAYMENT,
                Optional.of(withdrawal.movement()))).as("the hold is a withdrawal's, not a card payment's")
                .isInstanceOf(RiskActions.ActionNotProposable.class);

        OverrideWorld.Held payment = world.heldCase(RiskOperation.CARD_PAYMENT);
        RiskAction voided = world.propose(payment.taken(), ActionKind.VOID_HELD_PAYMENT,
                Optional.of(payment.movement())).action();
        assertThat(voided.targetKind()).isEqualTo(ActionTarget.PAYMENT_INTENT);
        assertThat(voided.reasonCode()).isEqualTo("RISK_CASE_FRAUD");
    }

    @Test
    @DisplayName("INV-RSK-07 / INV-AUD-04: the proposer cannot approve - SelfApprovalRefused, nothing written; a second"
            + " person approves - APPROVED under a permit due at once, risk.ActionApproved, risk.RiskActionRequested,"
            + " nothing dispatched; the same approver's retry converges; another's is not admitted")
    void theProposerCannotApprove() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.ATO);
        RiskAction proposed = world.propose(taken, ActionKind.SUSPEND_IDENTITY, Optional.empty()).action();
        int revision = world.caseOf(taken.riskCase().id()).revision();

        RiskActions.SelfApprovalRefused self = catchThrowableOfType(RiskActions.SelfApprovalRefused.class,
                () -> world.approve(proposed, taken.investigator()));
        assertThat(self.target()).isEqualTo(proposed.id().value().toString());
        assertThat(self.summary()).contains("act=action:approve").doesNotContain(taken.riskCase().subject().ref());
        assertThat(world.actionStore.find(EvaluationWorld.UOW, proposed.id()).orElseThrow().status())
                .isEqualTo(ActionStatus.PROPOSED);
        assertThat(world.caseOf(taken.riskCase().id()).revision()).as("nothing written").isEqualTo(revision);
        world.actions.recordRefusal(EvaluationWorld.UOW, self, taken.investigator(), OverrideWorld.correlation());
        assertThat(world.audited("risk.SelfApprovalRefused")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetType()).isEqualTo("risk_action");
        });

        Actor approver = world.analyst();
        RiskActions.Decided approved = world.approve(proposed, approver);
        assertThat(approved.replayed()).isFalse();
        RiskAction action = approved.action();
        assertThat(action.status()).isEqualTo(ActionStatus.APPROVED);
        assertThat(action.decidedBy()).contains(RiskCase.Person.of(approver));
        assertThat(action.nextAttemptAt()).as("due at once - the door claims it after the commit").contains(world.now());
        assertThat(action.attempts()).isZero();
        assertThat(action.decisionAction()).isPresent();
        assertThat(approved.riskCase().revision()).isEqualTo(revision + 1);
        assertThat(world.payments.calls).as("nothing is dispatched inside the approval").isEmpty();
        assertThat(world.audited("risk.ActionApproved")).singleElement()
                .satisfies(record -> assertThat(record.actor()).isEqualTo(approver));
        assertThat(world.announced("risk.RiskActionRequested")).singleElement().satisfies(payload ->
                assertThat(payload).containsEntry("actionId", proposed.id().value().toString())
                        .containsEntry("source", "CASE").containsEntry("kind", "SUSPEND_IDENTITY")
                        .containsEntry("targetKind", "PARTY").doesNotContainKey("outcome"));

        RiskActions.Decided again = world.approve(proposed, approver);
        assertThat(again.replayed()).as("the same approver's retry converges").isTrue();
        assertThatThrownBy(() -> world.approve(proposed, world.analyst())).as("another approver")
                .isInstanceOf(Cases.CaseActNotAdmitted.class);
        assertThat(world.audited("risk.ActionApproved")).hasSize(1);
    }

    @Test
    @DisplayName("INV-RSK-06: one live action per (case, kind, target) - a live twin is ActionNotProposable; a rejected"
            + " or FAILED action leaves it, so it is re-proposable as a new action (a new id, a new key, four eyes again)")
    void oneLiveActionAndAFailedOneIsReProposable() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.FRAUD);
        RiskAction first = world.propose(taken, ActionKind.SUSPEND_IDENTITY, Optional.empty()).action();
        assertThatThrownBy(() -> world.propose(taken, ActionKind.SUSPEND_IDENTITY, Optional.empty()))
                .isInstanceOf(RiskActions.ActionNotProposable.class);

        RiskCase current = world.caseOf(taken.riskCase().id());
        RiskActions.Decided withdrawn = world.actions.reject(EvaluationWorld.UOW, current.id(), first.id(),
                current.revision(), "proposed in error", taken.investigator(), OverrideWorld.correlation());
        assertThat(withdrawn.action().status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(world.audited("risk.ActionRejected")).singleElement().satisfies(record ->
                assertThat(record.changeSummary().orElseThrow()).contains("byProposer=true"));

        RiskAction second = world.propose(taken, ActionKind.SUSPEND_IDENTITY, Optional.empty()).action();
        world.approve(second, world.analyst());
        world.payments.lapsed.add(second.target());
        RiskAction failed = world.drive(second.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(ActionStatus.FAILED);

        RiskAction third = world.propose(taken, ActionKind.SUSPEND_IDENTITY, Optional.empty()).action();
        assertThat(third.id()).isNotEqualTo(second.id());
        assertThat(third.key()).isNotEqualTo(second.key());
        assertThat(third.status()).isEqualTo(ActionStatus.PROPOSED);
        assertThat(world.actionStore.find(EvaluationWorld.UOW, second.id()).orElseThrow().status())
                .as("the FAILED one is never re-dispatched by itself").isEqualTo(ActionStatus.FAILED);
    }

    @Test
    @DisplayName("the case must be taken, open and read at its revision; a disposition waits for a standing proposal,"
            + " and an approved action dispatches after the case is disposed")
    void theCaseGatesTheAct() {
        UUID party = EvaluationWorld.IDS.next();
        Actor investigator = world.analyst();
        RiskCase open = world.cases.open(EvaluationWorld.UOW, Cases.OpenRequest.of(CaseKind.FRAUD, SignalSubject.of(
                SubjectKind.PARTY, party)), "a pattern", investigator, OverrideWorld.correlation()).riskCase();
        assertThatThrownBy(() -> world.actions.propose(EvaluationWorld.UOW, open.id(), open.revision(),
                ActionKind.SUSPEND_IDENTITY.name(), Optional.empty(), "x", investigator, OverrideWorld.correlation()))
                .isInstanceOf(Cases.CaseNotTaken.class);

        OverrideWorld.Taken taken = world.partyCase(CaseKind.FRAUD);
        RiskCase current = world.caseOf(taken.riskCase().id());
        assertThatThrownBy(() -> world.actions.propose(EvaluationWorld.UOW, current.id(), current.revision() - 1,
                ActionKind.SUSPEND_IDENTITY.name(), Optional.empty(), "x", taken.investigator(),
                OverrideWorld.correlation())).isInstanceOf(Cases.CaseRevisionStale.class);

        RiskAction proposed = world.propose(taken, ActionKind.SUSPEND_IDENTITY, Optional.empty()).action();
        RiskCase standing = world.caseOf(taken.riskCase().id());
        assertThatThrownBy(() -> world.cases.proposeDisposition(EvaluationWorld.UOW, standing.id(),
                standing.revision(), "CLOSED_NO_ACTION", false, "nothing to do", taken.investigator(),
                OverrideWorld.correlation())).as("a disposition waits for the standing proposal")
                .isInstanceOf(Cases.CaseActNotAdmitted.class);

        world.approve(proposed, world.analyst());
        RiskCase decided = world.caseOf(taken.riskCase().id());
        world.cases.proposeDisposition(EvaluationWorld.UOW, decided.id(), decided.revision(), "CLOSED_NO_ACTION",
                false, "suspended; nothing more", taken.investigator(), OverrideWorld.correlation());
        assertThat(world.caseOf(taken.riskCase().id()).status()).isEqualTo(CaseStatus.DISPOSED);
        RiskCase disposed = world.caseOf(taken.riskCase().id());
        assertThatThrownBy(() -> world.actions.propose(EvaluationWorld.UOW, disposed.id(), disposed.revision(),
                ActionKind.REVOKE_SESSIONS.name(), Optional.empty(), "x", taken.investigator(),
                OverrideWorld.correlation())).isInstanceOf(Cases.CaseNotOpen.class);
        assertThat(world.drive(proposed.id()).orElseThrow().status())
                .as("an approved action concludes after its case is disposed").isEqualTo(ActionStatus.DONE);
    }

    @Test
    @DisplayName("self-dealing fails closed: an analyst never proposes or approves on their own party's case")
    void selfDealingFailsClosed() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.FRAUD);
        RiskAction proposed = world.propose(taken, ActionKind.REVOKE_SESSIONS, Optional.empty()).action();
        Actor insider = world.officerOf(UUID.fromString(taken.riskCase().subject().ref()));
        assertThat(catchThrowableOfType(RiskAssessments.SelfDealingRefused.class,
                () -> world.approve(proposed, insider)).act()).isEqualTo("action-approve");
        assertThat(world.actionStore.find(EvaluationWorld.UOW, proposed.id()).orElseThrow().status())
                .isEqualTo(ActionStatus.PROPOSED);
    }
}
