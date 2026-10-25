package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The suppression machine (`P13-TSK-015`; its door `-018`'s; {@code RISK_LIFECYCLES.md} section 4.5;
 * {@code INV-RSK-07}, {@code INV-AUD-04}, scenario 15's alert half): {@code SUPPRESSED} only by two persons with
 * reasons - an {@code OPEN} alert, a closed reason code, a second holder; the proposer refused and the refusal recorded
 * {@code FAILED}; a rejection clears the proposal and the alert stays open; never the system; never one's own party's
 * alert, nor an actor or a subject the platform cannot place. The database's four-eyes {@code CHECK} alone is
 * {@code AlertDatabaseTest#aSuppressionNeedsASecondPerson} (written and owed, C5).
 */
@DisplayName("an alert is suppressed only by two persons, with reasons - never by the system (P13-TSK-015)")
class AlertSuppressionTest {

    private final AlertWorld world = new AlertWorld();

    @Test
    @DisplayName("a proposal on an OPEN alert, then a second holder's approval: SUPPRESSED, audited"
            + " risk.AlertSuppressionProposed and risk.AlertSuppressed with the reasons, counted by its reason code")
    void aSecondHolderSuppresses() {
        RiskAlert alert = world.openAlert();
        Actor proposer = world.analyst();
        Actor approver = world.analyst();

        RiskAlert proposed = world.proposed(alert, proposer);
        assertThat(proposed.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(proposed.suppressionAwaitsDecision()).isTrue();
        assertThat(proposed.suppression().orElseThrow().reasonCode()).isEqualTo(SuppressionReason.FALSE_POSITIVE);

        Alerts.Decided decided = world.alerts.approveSuppression(AlertWorld.UOW, alert.id(), "agreed: payroll",
                approver, AlertWorld.correlation());
        assertThat(decided.replayed()).isFalse();
        assertThat(decided.alert().status()).isEqualTo(AlertStatus.SUPPRESSED);
        assertThat(decided.alert().decision().orElseThrow().decidedBy()).isEqualTo(approver.id());

        AuditRecord proposal = world.audited("risk.AlertSuppressionProposed").get(0);
        assertThat(proposal.actor()).isEqualTo(proposer);
        assertThat(proposal.reason()).contains("the pattern is the customer's payroll");
        assertThat(proposal.changeSummary()).contains("kind=ACCOUNT_TAKEOVER, reasonCode=FALSE_POSITIVE");
        AuditRecord suppression = world.audited("risk.AlertSuppressed").get(0);
        assertThat(suppression.actor()).isEqualTo(approver);
        assertThat(suppression.targetType()).isEqualTo("risk_alert");
        assertThat(suppression.targetId()).isEqualTo(alert.id().value().toString());
        assertThat(suppression.reason()).contains("agreed: payroll");
        assertThat(suppression.changeSummary().orElseThrow()).doesNotContain(alert.subject().ref());
        assertThat(world.suppressedCount).containsExactly(Map.entry(SuppressionReason.FALSE_POSITIVE, 1));
        assertThat(world.events).as("a suppression announces nothing").hasSize(1);
    }

    @Test
    @DisplayName("INV-RSK-07: the proposer is refused - SelfApprovalRefused, nothing written - and the refusal is"
            + " recorded FAILED")
    void theProposerCannotApprove() {
        RiskAlert alert = world.openAlert();
        Actor proposer = world.analyst();
        world.proposed(alert, proposer);

        assertThatThrownBy(() -> world.alerts.approveSuppression(AlertWorld.UOW, alert.id(), "mine", proposer,
                AlertWorld.correlation()))
                .as("[INV-RSK-07] the proposer never approves")
                .isInstanceOf(Alerts.SelfApprovalRefused.class)
                .satisfies(refused -> {
                    Alerts.SelfApprovalRefused self = (Alerts.SelfApprovalRefused) refused;
                    world.alerts.recordRefusal(AlertWorld.UOW, self, proposer, AlertWorld.correlation());
                });
        assertThat(world.store.all().get(0).status()).isEqualTo(AlertStatus.OPEN);
        assertThat(world.audited("risk.AlertSuppressed")).isEmpty();
        AuditRecord refusal = world.audited("risk.SelfApprovalRefused").get(0);
        assertThat(refusal.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(refusal.targetType()).isEqualTo("risk_alert");
        assertThat(refusal.changeSummary().orElseThrow()).contains("act=alert-suppression:approve")
                .doesNotContain(alert.subject().ref());
        assertThat(world.suppressedCount).isEmpty();
    }

    @Test
    @DisplayName("the acts need their states: a second proposal while one awaits is SuppressionStale; an approval or a"
            + " rejection with none awaiting is SuppressionStale; an attached alert is AlertNotOpen to every act")
    void theActsNeedTheirStates() {
        RiskAlert alert = world.openAlert();
        Actor proposer = world.analyst();
        assertThatThrownBy(() -> world.alerts.approveSuppression(AlertWorld.UOW, alert.id(), "nothing to approve",
                world.analyst(), AlertWorld.correlation())).isInstanceOf(Alerts.SuppressionStale.class);
        assertThatThrownBy(() -> world.alerts.rejectSuppression(AlertWorld.UOW, alert.id(), "nothing to reject",
                world.analyst(), AlertWorld.correlation())).isInstanceOf(Alerts.SuppressionStale.class);
        world.proposed(alert, proposer);
        assertThatThrownBy(() -> world.proposed(alert, world.analyst())).isInstanceOf(Alerts.SuppressionStale.class);

        RiskAlert attached = world.openAlert();
        world.alerts.attach(AlertWorld.UOW, attached.id(), RiskCaseId.of(EvaluationWorld.IDS.next()));
        assertThatThrownBy(() -> world.proposed(attached, world.analyst())).isInstanceOf(Alerts.AlertNotOpen.class);
        assertThatThrownBy(() -> world.alerts.approveSuppression(AlertWorld.UOW, attached.id(), "late",
                world.analyst(), AlertWorld.correlation())).isInstanceOf(Alerts.AlertNotOpen.class);
        assertThatThrownBy(() -> world.alerts.proposeSuppression(AlertWorld.UOW,
                RiskAlertId.of(EvaluationWorld.IDS.next()), "FALSE_POSITIVE", "no such alert", world.analyst(),
                AlertWorld.correlation())).isInstanceOf(Alerts.AlertNotFound.class);
    }

    @Test
    @DisplayName("reasons are required: a code outside SuppressionReason (DUPLICATE among them - a duplicate attaches),"
            + " a blank, unbounded or card-bearing text, and an approval or a rejection without a reason")
    void reasonsAreRequired() {
        RiskAlert alert = world.openAlert();
        Actor proposer = world.analyst();
        for (String code : new String[] {null, "", "DUPLICATE", "false_positive"}) {
            assertThatThrownBy(() -> world.alerts.proposeSuppression(AlertWorld.UOW, alert.id(), code, "a reason",
                    proposer, AlertWorld.correlation())).as(String.valueOf(code))
                    .isInstanceOf(RiskReasons.ReasonRequired.class);
        }
        for (String text : new String[] {null, " ", "x".repeat(RiskReasons.BOUND + 1), "card 4111111111111111"}) {
            assertThatThrownBy(() -> world.alerts.proposeSuppression(AlertWorld.UOW, alert.id(), "RULE_DEFECT", text,
                    proposer, AlertWorld.correlation())).isInstanceOf(RiskReasons.ReasonRequired.class);
        }
        world.proposed(alert, proposer);
        assertThatThrownBy(() -> world.alerts.approveSuppression(AlertWorld.UOW, alert.id(), " ", world.analyst(),
                AlertWorld.correlation())).isInstanceOf(RiskReasons.ReasonRequired.class);
        assertThatThrownBy(() -> world.alerts.rejectSuppression(AlertWorld.UOW, alert.id(), null, world.analyst(),
                AlertWorld.correlation())).isInstanceOf(RiskReasons.ReasonRequired.class);
        assertThat(world.store.all().get(0).status()).isEqualTo(AlertStatus.OPEN);
    }

    @Test
    @DisplayName("never the system: a SYSTEM, a SERVICE or a MERCHANT actor is refused before anything is read - at"
            + " every act (P13-TSK-018: a person is an authenticated human, CUSTOMER- or EMPLOYEE-typed)")
    void neverTheSystem() {
        RiskAlert alert = world.openAlert();
        RiskAlert proposed = world.proposed(world.openAlert(), world.analyst());
        world.store.log.clear();
        for (Actor actor : List.of(Actor.SYSTEM, new Actor("service-1", ActorType.SERVICE),
                new Actor("merchant-1", ActorType.MERCHANT))) {
            world.partyOfActor.put(actor.id(), UUID.randomUUID());
            assertThatThrownBy(() -> world.alerts.proposeSuppression(AlertWorld.UOW, alert.id(), "FALSE_POSITIVE",
                    "the system dedupes", actor, AlertWorld.correlation())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> world.alerts.approveSuppression(AlertWorld.UOW, proposed.id(), "the system agrees",
                    actor, AlertWorld.correlation())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> world.alerts.rejectSuppression(AlertWorld.UOW, proposed.id(), "the system rejects",
                    actor, AlertWorld.correlation())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(world.store.log).isEmpty();
    }

    @Test
    @DisplayName("P13-TSK-018's routed defect: a CUSTOMER-typed operator - as the platform's session establishes every"
            + " identity - proposes, and a second CUSTOMER-typed operator approves: SUPPRESSED, both persons recorded"
            + " CUSTOMER")
    void aCustomerTypedOperatorSuppresses() {
        RiskAlert alert = world.openAlert();
        Actor proposer = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        Actor approver = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        world.partyOfActor.put(proposer.id(), UUID.randomUUID());
        world.partyOfActor.put(approver.id(), UUID.randomUUID());

        RiskAlert proposed = world.proposed(alert, proposer);
        assertThat(proposed.suppression().orElseThrow().proposedByType()).isEqualTo(ActorType.CUSTOMER);
        Alerts.Decided decided = world.alerts.approveSuppression(AlertWorld.UOW, alert.id(), "agreed", approver,
                AlertWorld.correlation());
        assertThat(decided.alert().status()).isEqualTo(AlertStatus.SUPPRESSED);
        assertThat(decided.alert().decision().orElseThrow().decidedByType()).isEqualTo(ActorType.CUSTOMER);
        assertThat(world.audited("risk.AlertSuppressed")).singleElement()
                .satisfies(record -> assertThat(record.actor()).isEqualTo(approver));
    }

    @Test
    @DisplayName("D6: a merchant's alert is placed through its organisation party (the accountable join) - suppressible"
            + " by another analyst, refused to an analyst of the merchant's own party")
    void aMerchantsAlertIsJudgedByItsOrganisation() {
        SignalSubject merchant = SignalSubject.of(SubjectKind.MERCHANT, UUID.randomUUID());
        UUID organisation = UUID.randomUUID();
        world.partyOfSubject.put(merchant, organisation);
        RiskAlert alert = world.born(NewAlert.ofRule(AlertKind.MERCHANT_ABUSE, AlertSeverity.HIGH, merchant,
                RuleKind.REFUND_RATIO, AlertWorld.day(), AlertWorld.policy(),
                AlertEvidence.assessment(RiskAssessmentId.of(EvaluationWorld.IDS.next())), AlertWorld.correlation(),
                com.finapp.sharedkernel.correlation.CausationId.of("merchant-suite")));
        assertThatThrownBy(() -> world.proposed(alert, world.analystOf(organisation)))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .extracting(refused -> ((RiskAssessments.SelfDealingRefused) refused).refusal())
                .isEqualTo(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY);
        assertThat(world.proposed(alert, world.analyst()).suppressionAwaitsDecision()).isTrue();
    }

    @Test
    @DisplayName("a rejection - the proposer's withdrawal included - clears the proposal: the alert OPEN, audited"
            + " risk.AlertSuppressionRejected, and a new proposal admitted")
    void aRejectionClears() {
        RiskAlert alert = world.openAlert();
        Actor proposer = world.analyst();
        world.proposed(alert, proposer);
        Alerts.Decided withdrawn = world.alerts.rejectSuppression(AlertWorld.UOW, alert.id(), "withdrawn: premature",
                proposer, AlertWorld.correlation());
        assertThat(withdrawn.alert().status()).isEqualTo(AlertStatus.OPEN);
        assertThat(withdrawn.alert().suppression()).isEmpty();
        assertThat(world.audited("risk.AlertSuppressionRejected").get(0).changeSummary().orElseThrow())
                .contains("byProposer=true");

        Actor second = world.analyst();
        world.proposed(alert, second);
        Alerts.Decided rejected = world.alerts.rejectSuppression(AlertWorld.UOW, alert.id(), "needs a case",
                world.analyst(), AlertWorld.correlation());
        assertThat(rejected.alert().open()).isTrue();
        assertThat(world.audited("risk.AlertSuppressionRejected")).hasSize(2);
        assertThat(world.audited("risk.AlertSuppressionProposed")).as("the trail keeps every proposal").hasSize(2);
    }

    @Test
    @DisplayName("ten approvers in turn: one SUPPRESSED; the same approver's retry converges; the other nine find it"
            + " no longer open - one suppression, one record, one count")
    void tenApproversSuppressOnce() {
        RiskAlert alert = world.openAlert();
        world.proposed(alert, world.analyst());
        List<Object> answers = new ArrayList<>();
        Actor first = null;
        for (int i = 0; i < 10; i++) {
            Actor approver = world.analyst();
            try {
                answers.add(world.alerts.approveSuppression(AlertWorld.UOW, alert.id(), "agreed", approver,
                        AlertWorld.correlation()));
                first = first == null ? approver : first;
            } catch (Alerts.AlertNotOpen stale) {
                answers.add(stale);
            }
        }
        assertThat(answers).filteredOn(Alerts.Decided.class::isInstance).hasSize(1);
        assertThat(answers).filteredOn(Alerts.AlertNotOpen.class::isInstance).hasSize(9);
        Alerts.Decided retried = world.alerts.approveSuppression(AlertWorld.UOW, alert.id(), "agreed", first,
                AlertWorld.correlation());
        assertThat(retried.replayed()).isTrue();
        assertThat(world.audited("risk.AlertSuppressed")).hasSize(1);
        assertThat(world.suppressedCount).containsExactly(Map.entry(SuppressionReason.FALSE_POSITIVE, 1));
    }

    @Test
    @DisplayName("self-dealing fails closed: the actor's own party's alert, an actor placed in no party and an alert"
            + " whose subject's party cannot be placed are refused - recorded FAILED, nothing written")
    void selfDealingFailsClosed() {
        UUID party = UUID.randomUUID();
        RiskAlert own = world.born(AlertWorld.ato(AlertWorld.party(party), AlertWorld.day()));
        Actor ofTheParty = world.analystOf(party);
        assertThatThrownBy(() -> world.proposed(own, ofTheParty))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .satisfies(refused -> {
                    RiskAssessments.SelfDealingRefused self = (RiskAssessments.SelfDealingRefused) refused;
                    assertThat(self.refusal()).isEqualTo(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY);
                    world.alerts.recordRefusal(AlertWorld.UOW, own.id(), self, ofTheParty, AlertWorld.correlation());
                });
        AuditRecord recorded = world.audited("risk.SelfDealingRefused").get(0);
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(recorded.changeSummary().orElseThrow()).contains("act=alert-suppression-propose, cause=OWN_PARTY")
                .doesNotContain(party.toString());

        Actor unplaced = new Actor("analyst-unplaced", ActorType.EMPLOYEE);
        world.store.log.clear();
        assertThatThrownBy(() -> world.proposed(world.openAlert(), unplaced))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .extracting(refused -> ((RiskAssessments.SelfDealingRefused) refused).refusal())
                .isEqualTo(RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE);

        SignalSubject account = SignalSubject.of(SubjectKind.ACCOUNT, UUID.randomUUID());
        RiskAlert ofAnAccount = world.born(AlertWorld.structuring(account, AlertWorld.day(),
                List.of(EvaluationWorld.IDS.next())));
        assertThatThrownBy(() -> world.proposed(ofAnAccount, world.analyst()))
                .as("no join places the account's party: the judgement cannot be made, so it fails closed")
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .extracting(refused -> ((RiskAssessments.SelfDealingRefused) refused).refusal())
                .isEqualTo(RiskAssessments.SelfDealingRefused.Cause.SUBJECT_UNPLACEABLE);

        UUID accountParty = UUID.randomUUID();
        world.partyOfSubject.put(account, accountParty);
        assertThatThrownBy(() -> world.proposed(ofAnAccount, world.analystOf(accountParty)))
                .as("the join places it: the analyst's own")
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class);
        assertThat(world.proposed(ofAnAccount, world.analyst()).suppressionAwaitsDecision()).isTrue();
        assertThat(world.store.all()).filteredOn(alert -> alert.suppression().isPresent()).hasSize(1);
    }
}
