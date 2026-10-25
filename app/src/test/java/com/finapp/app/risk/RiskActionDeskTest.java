package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.risk.CaseKind;
import com.finapp.risk.RiskErrorCode;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The risk action doors, hermetic (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 9, 11; ADR-0112 decision 3): the
 * proposal {@code RISK_INVESTIGATE} ({@code 201}), the decisions {@code RISK_CASE_APPROVE}, the approval
 * {@code MULTI_FACTOR} and {@code 202}; every act keyed and a replayed key answering the action as it stands; the
 * proposer's approval {@code 403 risk.SelfApprovalRefused} and the analyst's own party {@code 403
 * risk.SelfDealingRefused}, each recorded {@code FAILED} in a transaction of its own; a kind the case does not admit
 * {@code 409 risk.ActionNotProposable}; a stale revision {@code 409 risk.CaseRevisionStale}; an untaken case
 * {@code 422 risk.CaseNotTaken}; a blank reason {@code 422 risk.ReasonRequired}; a malformed or unknown id the uniform
 * {@code 404 risk.NotFound}; a rejection dispatching nothing. Over HTTP the doors are {@code RiskActionApiTest}'s
 * (written, owed - C5).
 */
@DisplayName("the risk action doors: RISK_INVESTIGATE / RISK_CASE_APPROVE, MULTI_FACTOR, keyed acts, 202"
        + " (P13-TSK-017)")
class RiskActionDeskTest {

    private final RiskActionWorld world = new RiskActionWorld();

    @Test
    @DisplayName("the proposal carries RISK_INVESTIGATE and answers 201; the decisions RISK_CASE_APPROVE, the approval"
            + " MULTI_FACTOR and 202; every act keyed")
    void theRoutesPermissionsAndStatuses() throws NoSuchMethodException {
        Method propose = RiskActionController.class.getMethod("proposeRiskAction", String.class, String.class,
                RiskActionController.RiskActionProposalRequest.class);
        Method approve = RiskActionController.class.getMethod("approveRiskAction", String.class, String.class,
                String.class, RiskCaseController.RiskCaseDecisionRequest.class);
        Method reject = RiskActionController.class.getMethod("rejectRiskAction", String.class, String.class,
                String.class, RiskCaseController.RiskCaseDecisionRequest.class);
        assertThat(propose.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.RISK_INVESTIGATE);
        assertThat(approve.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.RISK_CASE_APPROVE);
        assertThat(reject.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.RISK_CASE_APPROVE);
        assertThat(approve.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        assertThat(propose.getAnnotation(RequiresAssurance.class)).isNull();
        assertThat(reject.getAnnotation(RequiresAssurance.class)).isNull();
        for (Method act : List.of(propose, approve, reject)) {
            assertThat(act.getAnnotation(RequiresIdempotencyKey.class)).as(act.getName()).isNotNull();
        }
        assertThat(propose.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.CREATED);
        assertThat(approve.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(reject.getAnnotation(ResponseStatus.class)).as("200").isNull();
        assertThat(RiskActionDesk.RiskActionView.class.getRecordComponents())
                .noneMatch(component -> component.getName().contains("By"));
    }

    @Test
    @DisplayName("a proposal answers the action PROPOSED with the case's next revision; a replay of its key answers the"
            + " same action and writes nothing; audited risk.ActionProposed with the reason")
    void aProposalIsKeyed() {
        RiskActionWorld.Taken ato = world.partyCase(CaseKind.ATO);
        int revision = world.caseOf(ato.id()).revision();
        RiskActionController.RiskActionProposalRequest body = new RiskActionController.RiskActionProposalRequest(
                revision, "SUSPEND_IDENTITY", null, "the account was taken over");
        RiskActionDesk.RiskActionView first = RiskActionWorld.as(ato.investigator(),
                () -> world.desk.propose("key-1", ato.id(), body));
        assertThat(first.status()).isEqualTo("PROPOSED");
        assertThat(first.kind()).isEqualTo("SUSPEND_IDENTITY");
        assertThat(first.targetKind()).isEqualTo("PARTY");
        assertThat(first.targetRef()).isEqualTo(ato.riskCase().subject().ref());
        assertThat(first.caseRevision()).isEqualTo(revision + 1);
        assertThat(first.attempts()).isZero();
        RiskActionDesk.RiskActionView replayed = RiskActionWorld.as(ato.investigator(),
                () -> world.desk.propose("key-1", ato.id(), body));
        assertThat(replayed.actionId()).isEqualTo(first.actionId());
        assertThat(world.actionStore.all()).hasSize(1);
        assertThat(world.audited("risk.ActionProposed")).singleElement()
                .satisfies(record -> assertThat(record.reason()).contains("the account was taken over"));
    }

    @Test
    @DisplayName("the refusals on the error contract, each recorded FAILED where it is a person's refused act")
    void theRefusalsOnTheErrorContract() {
        RiskActionWorld.Held held = world.heldWithdrawalCase();
        RiskActionDesk.RiskActionView proposed = world.propose(held.taken(), "RELEASE_HELD_WITHDRAWAL",
                held.movement().toString());

        ApiException self = refusal(held.taken().investigator(), () -> world.desk.approve("s", proposed.caseId(),
                proposed.actionId(), decision(proposed.caseId())));
        assertThat(self.errorCode()).isEqualTo(RiskErrorCode.SELF_APPROVAL_REFUSED);
        assertThat(world.audited("risk.SelfApprovalRefused")).singleElement()
                .satisfies(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED));
        assertThat(world.actionOf(proposed.actionId()).status().name()).isEqualTo("PROPOSED");

        Actor insider = world.analyst();
        world.partyOf.put(insider.id(), UUID.fromString(held.taken().riskCase().subject().ref()));
        ApiException dealing = refusal(insider, () -> world.desk.approve("d", proposed.caseId(), proposed.actionId(),
                decision(proposed.caseId())));
        assertThat(dealing.errorCode()).isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        assertThat(world.audited("risk.SelfDealingRefused")).singleElement()
                .satisfies(record -> assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED));

        assertThat(refusal(held.taken().investigator(), () -> world.desk.propose("n", proposed.caseId(),
                new RiskActionController.RiskActionProposalRequest(world.caseOf(proposed.caseId()).revision(),
                        "SUSPEND_MERCHANT", null, "wrong case"))).errorCode())
                .isEqualTo(RiskErrorCode.ACTION_NOT_PROPOSABLE);
        assertThat(refusal(held.taken().investigator(), () -> world.desk.propose("t", proposed.caseId(),
                new RiskActionController.RiskActionProposalRequest(world.caseOf(proposed.caseId()).revision(),
                        "RELEASE_HELD_WITHDRAWAL", held.movement().toString(), "again"))).errorCode())
                .as("a live twin").isEqualTo(RiskErrorCode.ACTION_NOT_PROPOSABLE);
        assertThat(refusal(held.taken().investigator(), () -> world.desk.propose("r", proposed.caseId(),
                new RiskActionController.RiskActionProposalRequest(0, "REVOKE_SESSIONS", null, "stale"))).errorCode())
                .isEqualTo(RiskErrorCode.CASE_REVISION_STALE);
        assertThat(refusal(held.taken().investigator(), () -> world.desk.propose("b", proposed.caseId(),
                new RiskActionController.RiskActionProposalRequest(world.caseOf(proposed.caseId()).revision(),
                        "REVOKE_SESSIONS", null, " "))).errorCode()).isEqualTo(RiskErrorCode.REASON_REQUIRED);
        assertThat(refusal(held.taken().investigator(), () -> world.desk.propose("m", proposed.caseId(),
                new RiskActionController.RiskActionProposalRequest(world.caseOf(proposed.caseId()).revision(),
                        "RELEASE_HELD_WITHDRAWAL", "not-an-id", "x"))).errorCode())
                .isEqualTo(RiskErrorCode.CASE_REQUEST_INVALID);
        for (Supplier<?> unknown : List.<Supplier<?>>of(
                () -> world.desk.approve("u", "not-a-case", proposed.actionId(), decision(proposed.caseId())),
                () -> world.desk.approve("v", proposed.caseId(), "not-an-action", decision(proposed.caseId())),
                () -> world.desk.approve("w", proposed.caseId(), RiskEvaluationWorld.IDS.next().toString(),
                        decision(proposed.caseId())))) {
            assertThat(refusal(world.analyst(), unknown).errorCode()).isEqualTo(RiskErrorCode.NOT_FOUND);
        }

        Actor investigator = world.analyst();
        UUID party = RiskEvaluationWorld.IDS.next();
        var open = RiskActionWorld.as(investigator, () -> world.transactions.inTransaction(uow -> world.cases.open(uow,
                com.finapp.risk.Cases.OpenRequest.of(CaseKind.FRAUD, com.finapp.risk.SignalSubject.of(
                        com.finapp.risk.SubjectKind.PARTY, party)), "a pattern", investigator,
                RiskActionWorld.correlation()).riskCase()));
        assertThat(refusal(investigator, () -> world.desk.propose("o", open.id().value().toString(),
                new RiskActionController.RiskActionProposalRequest(open.revision(), "REVOKE_SESSIONS", null, "x")))
                .errorCode()).as("an untaken case").isEqualTo(RiskErrorCode.CASE_NOT_TAKEN);
        assertThat(world.owners.calls).isEmpty();
    }

    @Test
    @DisplayName("a rejection answers 200 REJECTED and dispatches nothing; audited risk.ActionRejected")
    void aRejectionDispatchesNothing() {
        RiskActionWorld.Taken fraud = world.partyCase(CaseKind.FRAUD);
        RiskActionDesk.RiskActionView proposed = world.propose(fraud, "REVOKE_SESSIONS", null);
        RiskActionDesk.RiskActionView rejected = RiskActionWorld.as(world.analyst(), () -> world.desk.reject("j",
                proposed.caseId(), proposed.actionId(), decision(proposed.caseId())));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.decidedAt()).isNotNull();
        assertThat(world.owners.calls).isEmpty();
        assertThat(world.audited("risk.ActionRejected")).hasSize(1);
    }

    // ------------------------------------------------------------------

    private RiskCaseController.RiskCaseDecisionRequest decision(String caseId) {
        return new RiskCaseController.RiskCaseDecisionRequest(world.caseOf(caseId).revision(), "decided");
    }

    private static ApiException refusal(Actor actor, Supplier<?> work) {
        ApiException refused = catchThrowableOfType(ApiException.class, () -> RiskActionWorld.as(actor, work));
        assertThat(refused).as("refused on the error contract").isNotNull();
        return refused;
    }
}
