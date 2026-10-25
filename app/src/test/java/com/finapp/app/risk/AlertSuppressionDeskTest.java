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
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.AlertEvidence;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertObserver;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.AlertWindow;
import com.finapp.risk.Alerts;
import com.finapp.risk.NewAlert;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAlertId;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RuleKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.WindowKind;
import com.finapp.sharedkernel.correlation.CausationId;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The suppression door, hermetic (`P13-TSK-018`; `-015`'s machine; {@code PHASE_13_PLAN.md} sections 9, 11;
 * {@code INV-RSK-07}, {@code INV-AUD-04}): the proposal {@code RISK_INVESTIGATE} ({@code 201}), the approval
 * {@code RISK_CASE_APPROVE} from a {@code MULTI_FACTOR} session, the rejection {@code RISK_CASE_APPROVE}; every act keyed
 * ({@code risk.alert:<type>:<id>}) - a replayed key answering the alert as it stands; <strong>the routed defect's
 * proof</strong>: two {@code CUSTOMER}-typed analysts - as the session establishes every identity - suppress through the
 * door; the proposer's approval {@code 403 risk.SelfApprovalRefused} and the analyst's own party {@code 403
 * risk.SelfDealingRefused}, each recorded {@code FAILED}; an attached alert {@code 409 risk.AlertNotOpen}; a card-shaped
 * reason {@code 422 risk.ReasonRequired}; an unknown or malformed id the uniform {@code 404}; the answer names no person.
 * Over HTTP the door is {@code AlertSuppressionApiTest}'s (written, owed - C5).
 */
@DisplayName("the suppression door: two persons through RISK_INVESTIGATE / RISK_CASE_APPROVE, keyed, never the system"
        + " (P13-TSK-018)")
class AlertSuppressionDeskTest {

    private final RiskActionWorld world = new RiskActionWorld();
    private final Alerts alerts = new Alerts(world.alertStore, world.cases::fold,
            (unitOfWork, actor) -> Optional.ofNullable(world.partyOf.get(actor.id())),
            (unitOfWork, subject) -> Optional.ofNullable(world.partyOfSubject.get(subject)),
            (unitOfWork, record) -> world.audit.add(record), world.outbox, RiskEvaluationWorld.IDS,
            RiskEvaluationWorld.CLOCK, Spans.NONE, AlertObserver.NONE);
    private final RiskAlertDesk desk = new RiskAlertDesk(alerts, world.executor, world.transactions,
            RiskFlowScope.NONE);

    @Test
    @DisplayName("the proposal RISK_INVESTIGATE (201), the approval RISK_CASE_APPROVE and MULTI_FACTOR, the rejection"
            + " RISK_CASE_APPROVE - every act keyed; the queue a read, keyless")
    void everyRouteCarriesItsPermission() throws NoSuchMethodException {
        Map<Method, PermissionName> acts = Map.of(
                RiskAlertController.class.getMethod("proposeRiskAlertSuppression", String.class, String.class,
                        RiskAlertController.RiskSuppressionProposalRequest.class), PermissionName.RISK_INVESTIGATE,
                RiskAlertController.class.getMethod("approveRiskAlertSuppression", String.class, String.class,
                        RiskAlertController.RiskSuppressionDecisionRequest.class), PermissionName.RISK_CASE_APPROVE,
                RiskAlertController.class.getMethod("rejectRiskAlertSuppression", String.class, String.class,
                        RiskAlertController.RiskSuppressionDecisionRequest.class), PermissionName.RISK_CASE_APPROVE);
        acts.forEach((method, permission) -> {
            assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName())
                    .isEqualTo(permission);
            assertThat(method.getAnnotation(RequiresIdempotencyKey.class)).as(method.getName()).isNotNull();
            boolean approval = method.getName().equals("approveRiskAlertSuppression");
            assertThat(method.getAnnotation(RequiresAssurance.class) != null).as(method.getName()).isEqualTo(approval);
        });
        assertThat(RiskAlertController.class.getMethod("approveRiskAlertSuppression", String.class, String.class,
                RiskAlertController.RiskSuppressionDecisionRequest.class).getAnnotation(RequiresAssurance.class).value())
                .isEqualTo(AssuranceLevel.MULTI_FACTOR);
        Method queue = RiskAlertController.class.getMethod("readRiskAlertQueue", String.class, String.class,
                String.class, Integer.class);
        assertThat(queue.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(queue.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.RISK_INVESTIGATE);
        assertThat(queue.getAnnotation(RequiresIdempotencyKey.class)).isNull();
    }

    @Test
    @DisplayName("the routed defect's proof: a CUSTOMER-typed analyst proposes (PROPOSED), a second CUSTOMER-typed"
            + " analyst approves (SUPPRESSED); the key replayed answers the alert as it stands; no person in the answer;"
            + " audited risk.AlertSuppressionProposed and risk.AlertSuppressed")
    void twoPersonsSuppressThroughTheDoor() {
        RiskAlert alert = openAlert();
        Actor alice = world.analyst();
        Actor bob = world.analyst();
        RiskQueueDesk.RiskAlertView proposed = RiskActionWorld.as(alice, () -> desk.propose("k-propose", id(alert),
                proposal("FALSE_POSITIVE", "the pattern is the customer's payroll")));
        assertThat(proposed.status()).isEqualTo("OPEN");
        assertThat(proposed.suppression().status()).isEqualTo("PROPOSED");
        assertThat(proposed.suppression().reasonCode()).isEqualTo("FALSE_POSITIVE");

        RiskQueueDesk.RiskAlertView approved = RiskActionWorld.as(bob, () -> desk.approve("k-approve", id(alert),
                decision("agreed: payroll")));
        assertThat(approved.status()).isEqualTo("SUPPRESSED");
        assertThat(approved.suppression().status()).isEqualTo("DECIDED");
        assertThat(approved.suppression().decisionReason()).isEqualTo("agreed: payroll");
        assertThat(approved.toString()).doesNotContain(alice.id()).doesNotContain(bob.id());

        RiskQueueDesk.RiskAlertView replayed = RiskActionWorld.as(alice, () -> desk.propose("k-propose", id(alert),
                proposal("FALSE_POSITIVE", "the pattern is the customer's payroll")));
        assertThat(replayed.alertId()).isEqualTo(proposed.alertId());
        assertThat(replayed.status()).as("a replayed key answers the alert as it stands now").isEqualTo("SUPPRESSED");
        assertThat(world.audited("risk.AlertSuppressionProposed")).hasSize(1);
        assertThat(world.audited("risk.AlertSuppressed")).singleElement()
                .satisfies(record -> assertThat(record.actor()).isEqualTo(bob));
    }

    @Test
    @DisplayName("INV-RSK-07 at the door: the proposer's approval is 403 risk.SelfApprovalRefused, recorded FAILED in a"
            + " transaction of its own; the alert stays OPEN with its proposal")
    void theProposerCannotApprove() {
        RiskAlert alert = openAlert();
        Actor alice = world.analyst();
        RiskActionWorld.as(alice, () -> desk.propose("k-1", id(alert), proposal("RULE_DEFECT", "the rule misfires")));
        ApiException refused = refusal(alice, () -> desk.approve("k-2", id(alert), decision("mine")));
        assertThat(refused.errorCode()).isEqualTo(RiskErrorCode.SELF_APPROVAL_REFUSED);
        assertThat(world.audited("risk.SelfApprovalRefused")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(id(alert));
        });
        assertThat(world.audited("risk.AlertSuppressed")).isEmpty();
        assertThat(alerts.current(RiskEvaluationWorld.UNTOUCHABLE, alert.id()).orElseThrow().suppressionAwaitsDecision())
                .isTrue();
    }

    @Test
    @DisplayName("the refusals: the analyst's own party 403 SelfDealingRefused recorded FAILED; an attached alert 409"
            + " AlertNotOpen; a second proposal 409 SuppressionStale; a card-shaped reason and an unknown code 422"
            + " ReasonRequired; an unknown and a malformed id the uniform 404")
    void theRefusals() {
        UUID party = RiskEvaluationWorld.IDS.next();
        RiskAlert own = born(SignalSubject.of(SubjectKind.PARTY, party));
        Actor ofTheParty = world.analyst();
        world.partyOf.put(ofTheParty.id(), party);
        assertThat(refusal(ofTheParty, () -> desk.propose("u-1", id(own), proposal("FALSE_POSITIVE", "mine")))
                .errorCode()).isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        assertThat(world.audited("risk.SelfDealingRefused")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary().orElseThrow()).contains("act=alert-suppression-propose")
                    .doesNotContain(party.toString());
        });

        RiskAlert attached = openAlert();
        world.alertStore.attach(RiskEvaluationWorld.UNTOUCHABLE, attached.id(),
                RiskCaseId.of(RiskEvaluationWorld.IDS.next()));
        Actor alice = world.analyst();
        assertThat(refusal(alice, () -> desk.propose("u-2", id(attached), proposal("FALSE_POSITIVE", "seen")))
                .errorCode()).isEqualTo(RiskErrorCode.ALERT_NOT_OPEN);

        RiskAlert open = openAlert();
        RiskActionWorld.as(alice, () -> desk.propose("u-3", id(open), proposal("DATA_DEFECT", "a feed defect")));
        assertThat(refusal(world.analyst(), () -> desk.propose("u-4", id(open), proposal("DATA_DEFECT", "again")))
                .errorCode()).isEqualTo(RiskErrorCode.SUPPRESSION_STALE);
        assertThat(refusal(world.analyst(), () -> desk.approve("u-5", id(open), decision("card 4111 1111 1111 1111")))
                .errorCode()).isEqualTo(RiskErrorCode.REASON_REQUIRED);
        assertThat(refusal(alice, () -> desk.propose("u-6", id(openAlert()), proposal("DUPLICATE", "a duplicate")))
                .errorCode()).isEqualTo(RiskErrorCode.REASON_REQUIRED);
        assertThat(refusal(alice, () -> desk.propose("u-7", RiskEvaluationWorld.IDS.next().toString(),
                proposal("FALSE_POSITIVE", "nothing"))).errorCode()).isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(refusal(alice, () -> desk.reject("u-8", "not-an-id", decision("nothing"))).errorCode())
                .isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(refusal(alice, () -> desk.reject("u-9", UUID.randomUUID().toString(), decision("v4")))
                .errorCode()).as("a v4 value is no alert's").isEqualTo(RiskErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("the rejection - the proposer's withdrawal included - clears the proposal: the alert OPEN, audited"
            + " risk.AlertSuppressionRejected")
    void aRejectionClears() {
        RiskAlert alert = openAlert();
        Actor alice = world.analyst();
        RiskActionWorld.as(alice, () -> desk.propose("r-1", id(alert), proposal("ACTIVITY_EXPLAINED", "documents")));
        RiskQueueDesk.RiskAlertView rejected = RiskActionWorld.as(alice, () -> desk.reject("r-2", id(alert),
                decision("withdrawn - more to look at")));
        assertThat(rejected.status()).isEqualTo("OPEN");
        assertThat(rejected.suppression()).isNull();
        assertThat(world.audited("risk.AlertSuppressionRejected")).singleElement().satisfies(record ->
                assertThat(record.changeSummary().orElseThrow()).contains("byProposer=true"));
    }

    // -----------------------------------------------------------------

    /** An open ATO alert of a fresh party - an alert born OPEN before the folding (a planted backlog row). */
    private RiskAlert openAlert() {
        return born(SignalSubject.of(SubjectKind.PARTY, RiskEvaluationWorld.IDS.next()));
    }

    private RiskAlert born(SignalSubject subject) {
        return world.alertStore.insert(RiskEvaluationWorld.UNTOUCHABLE, RiskAlertId.next(RiskEvaluationWorld.IDS),
                NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER, AlertSeverity.HIGH, subject,
                        RuleKind.RECENT_CREDENTIAL_CHANGE,
                        AlertWindow.of(new com.finapp.risk.VelocityWindowKey(com.finapp.risk.VelocityDimension.PARTY, RiskEvaluationWorld.IDS.next().toString(), WindowKind.D1, Instant.parse("2026-10-24T00:00:00Z"))),
                        RiskPolicyVersionId.of(RiskEvaluationWorld.IDS.next()),
                        AlertEvidence.assessment(RiskAssessmentId.of(RiskEvaluationWorld.IDS.next())),
                        RiskActionWorld.correlation(), CausationId.of("desk-suite"))).orElseThrow();
    }

    private static String id(RiskAlert alert) {
        return alert.id().value().toString();
    }

    private static RiskAlertController.RiskSuppressionProposalRequest proposal(String code, String reason) {
        return new RiskAlertController.RiskSuppressionProposalRequest(code, reason);
    }

    private static RiskAlertController.RiskSuppressionDecisionRequest decision(String reason) {
        return new RiskAlertController.RiskSuppressionDecisionRequest(reason);
    }

    private static ApiException refusal(Actor actor, Supplier<?> work) {
        ApiException refused = catchThrowableOfType(ApiException.class, () -> RiskActionWorld.as(actor, work));
        assertThat(refused).as("refused on the error contract").isNotNull();
        return refused;
    }
}
