package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.sharedkernel.correlation.CausationId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `-015`'s {@link AlertFolding}, implemented by {@link Cases#fold} (`P13-TSK-016`; ADR-0110 decision 3;
 * {@code RISK_LIFECYCLES.md} sections 4.5, 4.6; {@code INV-RSK-06}): a born alert attaches to its subject's open case of
 * its kind's case kind, or opens one; ten alerts are one case; a duplicate is never folded twice and never suppressed; a
 * late alert after the disposal opens a new case naming its predecessor; an operational alert never folds; and the drain
 * attaches the backlog born {@code OPEN} before the folding was wired - once.
 */
@DisplayName("the folding of alerts into cases, and the drain of the backlog (P13-TSK-016)")
class CaseFoldingTest {

    private final CaseWorld world = new CaseWorld();

    @Test
    @DisplayName("each alert kind folds into its case kind - FRAUD and ATO and AML on the party, MERCHANT_ABUSE on the"
            + " merchant, SCREENING_HIT into AML - opened from the alert at revision 0")
    void alertsFoldIntoTheirKindsCase() {
        UUID party = EvaluationWorld.IDS.next();
        SignalSubject merchant = SignalSubject.of(SubjectKind.MERCHANT, EvaluationWorld.IDS.next());
        List<Alerts.Raised> raised = CaseWorld.asSystem(() -> world.alerts.raiseAll(CaseWorld.UOW, List.of(
                rule(AlertKind.FRAUD, CaseWorld.party(party), RuleKind.REFUND_RATIO),
                rule(AlertKind.ACCOUNT_TAKEOVER, CaseWorld.party(party), RuleKind.RECENT_CREDENTIAL_CHANGE),
                rule(AlertKind.AML, CaseWorld.party(party), RuleKind.STRUCTURING),
                rule(AlertKind.MERCHANT_ABUSE, merchant, RuleKind.CHARGEBACK_RATIO))));

        assertThat(raised).allSatisfy(answer -> {
            assertThat(answer.outcome()).isEqualTo(Alerts.Outcome.BORN);
            assertThat(answer.folded().outcome()).isEqualTo(AlertFolding.Folding.ATTACHED);
            assertThat(answer.alert().status()).isEqualTo(AlertStatus.ATTACHED);
        });
        assertThat(world.caseStore.all()).extracting(RiskCase::kind).containsExactlyInAnyOrder(CaseKind.FRAUD,
                CaseKind.ATO, CaseKind.AML, CaseKind.MERCHANT_ABUSE);
        assertThat(world.caseStore.all()).allSatisfy(riskCase -> {
            assertThat(riskCase.revision()).isZero();
            assertThat(riskCase.openingAlert()).isPresent();
        });
        RiskCase merchantCase = world.caseStore.all().stream().filter(c -> c.kind() == CaseKind.MERCHANT_ABUSE)
                .findFirst().orElseThrow();
        assertThat(merchantCase.subject()).isEqualTo(merchant);

        Alerts.Raised screening = CaseWorld.asSystem(() -> world.alerts.raise(CaseWorld.UOW,
                // A fact since P13-TSK-022's review (risk V018): its own code, no policy, its one signal.
                NewAlert.ofFact(AlertKind.SCREENING_HIT, AlertSeverity.CRITICAL, CaseWorld.party(party), AlertWorld.day(),
                        RiskSignalId.next(EvaluationWorld.IDS), CaseWorld.correlation(),
                        CausationId.of("folding-suite"))));
        RiskCase aml = world.caseStore.all().stream().filter(c -> c.kind() == CaseKind.AML).findFirst().orElseThrow();
        assertThat(screening.folded().caseId()).contains(aml.id());
        assertThat(world.cases.current(CaseWorld.UOW, aml.id()).orElseThrow().revision()).isEqualTo(1);
        assertThat(world.audited("risk.CaseOpened")).hasSize(4).allSatisfy(record -> {
            assertThat(record.actor().type()).isEqualTo(com.finapp.platform.security.ActorType.SYSTEM);
            assertThat(record.changeSummary().orElseThrow()).contains("from=alert");
        });
    }

    @Test
    @DisplayName("INV-RSK-06, scenario 12: ten alerts of one subject and kind are one open case - the first opens it,"
            + " the nine attach, each a revision of its own")
    void tenAlertsOneOpenCase() {
        SignalSubject subject = CaseWorld.party(EvaluationWorld.IDS.next());
        List<NewAlert> ten = new ArrayList<>();
        for (int day = 0; day < 10; day++) {
            ten.add(NewAlert.ofRule(AlertKind.AML, AlertSeverity.MEDIUM, subject, RuleKind.STRUCTURING,
                    AlertWindow.containing(WindowKind.D1, AlertWorld.EVIDENCE_STAMP.plusSeconds(86_400L * day)),
                    AlertWorld.policy(), AlertEvidence.journalEntries(List.of(EvaluationWorld.IDS.next())),
                    CaseWorld.correlation(), CausationId.of("monitoring-suite")));
        }
        List<Alerts.Raised> raised = CaseWorld.asSystem(() -> world.alerts.raiseAll(CaseWorld.UOW, ten));

        assertThat(world.caseStore.all()).singleElement().satisfies(riskCase -> {
            assertThat(riskCase.kind()).isEqualTo(CaseKind.AML);
            assertThat(riskCase.revision()).isEqualTo(9);
        });
        RiskCaseId only = world.caseStore.all().get(0).id();
        assertThat(raised).allSatisfy(answer -> assertThat(answer.folded().caseId()).contains(only));
        assertThat(world.caseStore.events(CaseWorld.UOW, only)).extracting(CaseEvent::kind)
                .containsOnly(CaseEventKind.OPENED, CaseEventKind.ALERT_ATTACHED).hasSize(10);
        assertThat(world.announced(Cases.OPENED_EVENT)).hasSize(1);
    }

    @Test
    @DisplayName("a duplicate alert is ALREADY: never folded twice, never suppressed - the case's revision unmoved")
    void aDuplicateAlertAttachesAndIsNeverSuppressed() {
        SignalSubject subject = CaseWorld.party(EvaluationWorld.IDS.next());
        Alerts.Raised first = world.raiseAto(subject, RuleKind.RECENT_CREDENTIAL_CHANGE);
        Alerts.Raised second = world.raiseAto(subject, RuleKind.RECENT_CREDENTIAL_CHANGE);
        assertThat(second.outcome()).isEqualTo(Alerts.Outcome.ALREADY);
        assertThat(second.folded().outcome()).isEqualTo(AlertFolding.Folding.QUEUED);
        assertThat(second.alert().status()).isEqualTo(AlertStatus.ATTACHED);
        assertThat(world.caseStore.all()).singleElement().satisfies(riskCase ->
                assertThat(riskCase.revision()).isZero());
        assertThat(first.folded().caseId()).isEqualTo(second.alert().attachedCase());
    }

    @Test
    @DisplayName("OQ-6, scenario 6: a late alert after the disposal leaves the disposed case untouched and opens a new"
            + " case naming it as predecessor")
    void aLateEventAfterDispositionOpensANewCase() {
        SignalSubject subject = CaseWorld.party(EvaluationWorld.IDS.next());
        Alerts.Raised first = world.raiseAto(subject, RuleKind.RECENT_CREDENTIAL_CHANGE);
        RiskCaseId closedId = first.folded().caseId().orElseThrow();
        RiskCase closing = world.cases.current(CaseWorld.UOW, closedId).orElseThrow();
        RiskCase disposed = world.cases.proposeDisposition(CaseWorld.UOW, closedId, closing.revision(),
                "CLOSED_FALSE_POSITIVE", false, "the customer changed their own password", world.analyst(),
                CaseWorld.correlation()).riskCase();

        Alerts.Raised late = world.raiseAto(subject, RuleKind.RECENT_RECOVERY);
        RiskCaseId newId = late.folded().caseId().orElseThrow();
        assertThat(newId).isNotEqualTo(closedId);
        RiskCase reopened = world.cases.current(CaseWorld.UOW, newId).orElseThrow();
        assertThat(reopened.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(reopened.predecessor()).contains(closedId);
        assertThat(world.cases.current(CaseWorld.UOW, closedId).orElseThrow()).isEqualTo(disposed);
    }

    @Test
    @DisplayName("an operational alert never folds: it stays OPEN with no case, and the folding refuses it")
    void anOperationalAlertNeverFolds() {
        RiskAlert unclassified = CaseWorld.asSystem(() -> world.alerts.raise(CaseWorld.UOW,
                AlertWorld.unclassified(CaseWorld.party(EvaluationWorld.IDS.next()), EvaluationWorld.IDS.next()))).alert();
        assertThat(unclassified.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(world.caseStore.all()).isEmpty();
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> CaseWorld.asSystem(() ->
                world.cases.fold(CaseWorld.UOW, unclassified)));
        assertThat(world.cases.queued(CaseWorld.UOW, 10)).isEmpty();
    }

    @Test
    @DisplayName("the drain: the alerts born OPEN before the folding was wired are folded once, oldest first - two of"
            + " one subject into one case, a second drain writing nothing - and an alert with a standing suppression"
            + " proposal is left to -018's door")
    void theDrainAttachesTheBacklogOnce() {
        SignalSubject subject = CaseWorld.party(EvaluationWorld.IDS.next());
        SignalSubject other = CaseWorld.party(EvaluationWorld.IDS.next());
        world.alertStore.now = Instant.parse("2026-10-24T08:00:00Z");
        RiskAlert oldest = world.plantedOpen(rule(AlertKind.FRAUD, subject, RuleKind.REFUND_RATIO));
        world.alertStore.now = Instant.parse("2026-10-24T09:00:00Z");
        RiskAlert later = world.plantedOpen(rule(AlertKind.FRAUD, subject, RuleKind.CHARGEBACK_RATIO));
        world.alertStore.now = Instant.parse("2026-10-24T10:00:00Z");
        RiskAlert elsewhere = world.plantedOpen(rule(AlertKind.FRAUD, other, RuleKind.REFUND_RATIO));
        world.alertStore.now = Instant.parse("2026-10-24T11:00:00Z");
        RiskAlert proposed = world.plantedOpen(rule(AlertKind.FRAUD, CaseWorld.party(EvaluationWorld.IDS.next()),
                RuleKind.BNPL_MULTI_APPLICATION));
        world.alertStore.propose(CaseWorld.UOW, proposed.id(), SuppressionReason.RULE_DEFECT, "the rule misfired",
                world.analyst());

        List<RiskAlert> queued = world.cases.queued(CaseWorld.UOW, 10);
        assertThat(queued).extracting(RiskAlert::id).containsExactly(oldest.id(), later.id(), elsewhere.id());
        for (RiskAlert alert : queued) {
            assertThat(CaseWorld.asSystem(() -> world.cases.drain(CaseWorld.UOW, alert.id())).outcome())
                    .isEqualTo(AlertFolding.Folding.ATTACHED);
        }
        assertThat(world.caseStore.all()).hasSize(2);
        RiskCase subjects = world.caseStore.all().stream().filter(c -> c.subject().equals(subject)).findFirst()
                .orElseThrow();
        assertThat(subjects.openingAlert()).contains(oldest.id());
        assertThat(subjects.revision()).isEqualTo(1);

        for (RiskAlert alert : queued) {
            assertThat(CaseWorld.asSystem(() -> world.cases.drain(CaseWorld.UOW, alert.id())))
                    .isEqualTo(AlertFolding.Folded.QUEUED);
        }
        assertThat(world.cases.current(CaseWorld.UOW, subjects.id()).orElseThrow().revision()).isEqualTo(1);
        assertThat(world.cases.queued(CaseWorld.UOW, 10)).isEmpty();
        assertThat(world.alertStore.find(CaseWorld.UOW, proposed.id()).orElseThrow().status())
                .isEqualTo(AlertStatus.OPEN);
    }

    @Test
    @DisplayName("a fold that finds its alert already attached to the case it holds converges; attached elsewhere, it"
            + " refuses AlertNotOpen (the drain's race - its transaction rolls back)")
    void theDrainsRaceConvergesOrRefuses() {
        SignalSubject subject = CaseWorld.party(EvaluationWorld.IDS.next());
        RiskAlert queued = world.plantedOpen(rule(AlertKind.FRAUD, subject, RuleKind.REFUND_RATIO));
        RiskCaseId caseId = CaseWorld.asSystem(() -> world.cases.drain(CaseWorld.UOW, queued.id())).caseId()
                .orElseThrow();
        RiskAlert attached = world.alertStore.find(CaseWorld.UOW, queued.id()).orElseThrow();
        AlertFolding.Folded again = CaseWorld.asSystem(() -> world.cases.fold(CaseWorld.UOW, queued));
        assertThat(again.caseId()).contains(caseId);
        assertThat(world.cases.current(CaseWorld.UOW, caseId).orElseThrow().revision()).isZero();

        RiskCase opened = world.cases.current(CaseWorld.UOW, caseId).orElseThrow();
        world.cases.proposeDisposition(CaseWorld.UOW, caseId, opened.revision(), "CLOSED_NO_ACTION", false,
                "nothing in it", world.analyst(), CaseWorld.correlation());
        assertThatExceptionOfType(Alerts.AlertNotOpen.class).isThrownBy(() -> CaseWorld.asSystem(() ->
                world.cases.fold(CaseWorld.UOW, attached)));
    }

    // -----------------------------------------------------------------

    private static NewAlert rule(AlertKind kind, SignalSubject subject, RuleKind rule) {
        return NewAlert.ofRule(kind, AlertSeverity.HIGH, subject, rule, AlertWorld.day(), AlertWorld.policy(),
                AlertEvidence.assessment(RiskAssessmentId.of(EvaluationWorld.IDS.next())), CaseWorld.correlation(),
                CausationId.of("folding-suite"));
    }
}
