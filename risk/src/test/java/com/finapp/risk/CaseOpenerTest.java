package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `-004`'s {@link CaseOpener}, implemented by {@link Cases#openFor} (`P13-TSK-016`; ADR-0108 decision 3; ADR-0110
 * decision 2; {@code DESIGN-016} D5; {@code INV-RSK-06}): T-eval's {@code REVIEW} or {@code HOLD} opens the party's case
 * of the kind its sealed hits call for with the assessment as its opening, or joins the open one with the assessment as
 * a step of its own - and an anchor rule's {@code HOLD} and its {@code ACCOUNT_TAKEOVER} alert are ONE {@code ATO} case,
 * whichever reaches it first. The system's opening is recorded under the judged command's principal.
 */
@DisplayName("T-eval's case: REVIEW and HOLD open or join their case, one ATO case (P13-TSK-016)")
class CaseOpenerTest {

    private final CaseWorld world = new CaseWorld();
    private final Actor customer = new Actor("customer-" + EvaluationWorld.IDS.next(), ActorType.CUSTOMER);

    @Test
    @DisplayName("a REVIEW opens a FRAUD case with the assessment as its opening, audited under the judged command's"
            + " principal and announced")
    void aReviewOpensItsCaseWithTheAssessment() {
        UUID party = EvaluationWorld.IDS.next();
        RiskAssessmentId assessment = RiskAssessmentId.of(EvaluationWorld.IDS.next());
        Cases.Opened opened = asCustomer(() -> world.cases.openFor(CaseWorld.UOW, decided(assessment, party,
                RiskDecision.REVIEW, CaseKind.FRAUD)));

        assertThat(opened.opened()).isTrue();
        RiskCase riskCase = opened.riskCase();
        assertThat(riskCase.kind()).isEqualTo(CaseKind.FRAUD);
        assertThat(riskCase.subject()).isEqualTo(CaseWorld.party(party));
        assertThat(riskCase.openingAssessment()).contains(assessment);
        assertThat(world.caseStore.events(CaseWorld.UOW, riskCase.id())).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(CaseEventKind.OPENED);
            assertThat(event.assessment()).contains(assessment);
            assertThat(event.actor().type()).isEqualTo(ActorType.CUSTOMER);
        });
        assertThat(world.audited("risk.CaseOpened")).singleElement().satisfies(record -> {
            assertThat(record.actor()).isEqualTo(customer);
            assertThat(record.changeSummary().orElseThrow()).contains("from=assessment");
        });
        assertThat(world.announced(Cases.OPENED_EVENT)).hasSize(1);
    }

    @Test
    @DisplayName("a second decision joins the party's open case: the assessment attached as a step; the same"
            + " assessment again writes nothing")
    void aSecondDecisionJoinsTheOpenCase() {
        UUID party = EvaluationWorld.IDS.next();
        RiskAssessmentId first = RiskAssessmentId.of(EvaluationWorld.IDS.next());
        RiskAssessmentId second = RiskAssessmentId.of(EvaluationWorld.IDS.next());
        RiskCase opened = asCustomer(() -> world.cases.openFor(CaseWorld.UOW, decided(first, party,
                RiskDecision.HOLD, CaseKind.FRAUD))).riskCase();
        Cases.Opened joined = asCustomer(() -> world.cases.openFor(CaseWorld.UOW, decided(second, party,
                RiskDecision.REVIEW, CaseKind.FRAUD)));
        assertThat(joined.opened()).isFalse();
        assertThat(joined.riskCase().id()).isEqualTo(opened.id());
        assertThat(joined.riskCase().revision()).isEqualTo(1);
        assertThat(world.caseStore.events(CaseWorld.UOW, opened.id())).extracting(CaseEvent::kind)
                .containsExactly(CaseEventKind.OPENED, CaseEventKind.ASSESSMENT_ATTACHED);

        Cases.Opened replayed = asCustomer(() -> world.cases.openFor(CaseWorld.UOW, decided(first, party,
                RiskDecision.HOLD, CaseKind.FRAUD)));
        assertThat(replayed.riskCase().revision()).isEqualTo(1);
        assertThat(world.caseStore.all()).hasSize(1);
    }

    @Test
    @DisplayName("-013's one ATO case: the anchor's alert folds first and the HOLD joins its ATO case; with the raise"
            + " lost behind its savepoint the HOLD opens the ATO case and the alert raised later joins it - one case"
            + " either way")
    void oneAtoCaseForAHoldAndItsAlert() {
        UUID party = EvaluationWorld.IDS.next();
        Alerts.Raised alert = world.raiseAto(CaseWorld.party(party), RuleKind.RECENT_CREDENTIAL_CHANGE);
        RiskAssessmentId held = RiskAssessmentId.of(EvaluationWorld.IDS.next());
        Cases.Opened joined = asCustomer(() -> world.cases.openFor(CaseWorld.UOW, decided(held, party,
                RiskDecision.HOLD, CaseKind.ATO)));
        assertThat(joined.opened()).isFalse();
        assertThat(alert.folded().caseId()).contains(joined.riskCase().id());
        assertThat(world.caseStore.all()).singleElement().satisfies(riskCase ->
                assertThat(riskCase.kind()).isEqualTo(CaseKind.ATO));

        UUID other = EvaluationWorld.IDS.next();
        Cases.Opened first = asCustomer(() -> world.cases.openFor(CaseWorld.UOW, decided(
                RiskAssessmentId.of(EvaluationWorld.IDS.next()), other, RiskDecision.HOLD, CaseKind.ATO)));
        assertThat(first.opened()).isTrue();
        Alerts.Raised late = world.raiseAto(CaseWorld.party(other), RuleKind.RECENT_RECOVERY);
        assertThat(late.folded().caseId()).contains(first.riskCase().id());
        assertThat(world.caseStore.all().stream().filter(c -> c.subject().equals(CaseWorld.party(other))))
                .hasSize(1);
    }

    @Test
    @DisplayName("the kind a decision's sealed hits call for: ATO when the account-takeover anchors show (a refuting"
            + " continuation breaks it), FRAUD otherwise; a decision opens no other kind")
    void theKindIsTheHits() {
        RuleHit change = new RuleHit(1, RuleKind.RECENT_CREDENTIAL_CHANGE, RuleOutcome.FIRED, 0,
                Optional.of(RiskDecision.HOLD), "fired");
        RuleHit knownPayee = new RuleHit(2, RuleKind.NEW_PAYEE, RuleOutcome.NOT_FIRED, 0, Optional.empty(),
                "notFired");
        RuleHit amount = new RuleHit(1, RuleKind.AMOUNT_BAND, RuleOutcome.FIRED, 300, Optional.empty(), "fired");
        assertThat(CaseOpener.Decided.kindFor(RiskOperation.WITHDRAWAL, List.of(change))).isEqualTo(CaseKind.ATO);
        assertThat(CaseOpener.Decided.kindFor(RiskOperation.TRANSFER, List.of(change, knownPayee)))
                .isEqualTo(CaseKind.FRAUD);
        assertThat(CaseOpener.Decided.kindFor(RiskOperation.TRANSFER, List.of(amount))).isEqualTo(CaseKind.FRAUD);
        assertThat(CaseOpener.Decided.kindFor(RiskOperation.CARD_PAYMENT, List.of(change))).isEqualTo(CaseKind.FRAUD);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> decided(
                RiskAssessmentId.of(EvaluationWorld.IDS.next()), EvaluationWorld.IDS.next(), RiskDecision.REVIEW,
                CaseKind.AML));
    }

    // -----------------------------------------------------------------

    private static CaseOpener.Decided decided(RiskAssessmentId assessment, UUID party, RiskDecision decision,
            CaseKind kind) {
        return new CaseOpener.Decided(assessment, RiskOperation.TRANSFER, CommandRef.of(EvaluationWorld.IDS.next()),
                party, decision, kind, CaseWorld.correlation());
    }

    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private <T> T asCustomer(java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope ignored = SecurityContext.enter(customer)) {
            return work.get();
        }
    }
}
