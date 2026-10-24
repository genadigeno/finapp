package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The folding contract (`P13-TSK-015` declares it, `P13-TSK-016` implements it over its case tables;
 * {@code RISK_LIFECYCLES.md} sections 4.5, 4.6; ADR-0110 decision 3; {@code INV-RSK-06}): until a case exists the alert
 * stays {@code OPEN}, queued, once ({@link AlertFolding#NONE}); only a born alert of a kind that opens a case is folded;
 * <strong>a duplicate is never suppressed</strong> - the system's dedupe is {@code ATTACHED}; a folding that attaches
 * moves the alert through the alert's own edge ({@link Alerts#attach}), which converges for the same case and refuses
 * another; a folding that claims an attach it did not make is refused.
 */
@DisplayName("the folding: queued OPEN until a case exists, attached - never suppressed - once it does (P13-TSK-015)")
class AlertFoldingTest {

    private final AlertWorld world = new AlertWorld();

    @Test
    @DisplayName("NONE: an alert born before any case exists stays OPEN, queued - and a second raise of it keeps one"
            + " OPEN alert, folded never")
    void anOpenAlertIsQueuedUntilACaseExists() {
        SignalSubject subject = AlertWorld.freshParty();
        Alerts.Raised raised = world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));
        assertThat(raised.folded()).isEqualTo(AlertFolding.Folded.QUEUED);
        assertThat(raised.alert().status()).isEqualTo(AlertStatus.OPEN);
        assertThat(AlertFolding.NONE.fold(AlertWorld.UOW, raised.alert())).isEqualTo(AlertFolding.Folded.QUEUED);

        Alerts.Raised again = world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));
        assertThat(again.outcome()).isEqualTo(Alerts.Outcome.ALREADY);
        assertThat(world.store.all()).singleElement().satisfies(alert -> {
            assertThat(alert.status()).isEqualTo(AlertStatus.OPEN);
            assertThat(alert.suppression()).isEmpty();
        });
        assertThat(world.store.log).doesNotContain("attach", "propose", "approve");
    }

    @Test
    @DisplayName("only a born alert is folded: a raise that finds its alert standing hands nothing to the folding")
    void onlyABornAlertIsFolded() {
        List<RiskAlert> folded = new ArrayList<>();
        world.folding = (unitOfWork, born) -> {
            folded.add(born);
            return AlertFolding.Folded.QUEUED;
        };
        SignalSubject subject = AlertWorld.freshParty();
        for (int i = 0; i < 3; i++) {
            world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));
        }
        assertThat(folded).hasSize(1);
        assertThat(folded.get(0).status()).isEqualTo(AlertStatus.OPEN);
    }

    @Test
    @DisplayName("a duplicate is never suppressed - the system's dedupe is ATTACHED: the folding attaches the first"
            + " alert to its case, the duplicate raise changes nothing, and no suppression column is ever written")
    void aDuplicateIsNeverSuppressed() {
        RiskCaseId openCase = RiskCaseId.of(EvaluationWorld.IDS.next());
        world.folding = (unitOfWork, born) -> {
            world.alerts.attach(unitOfWork, born.id(), openCase);
            return AlertFolding.Folded.attached(openCase);
        };
        SignalSubject subject = AlertWorld.freshParty();
        Alerts.Raised first = world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));
        Alerts.Raised duplicate = world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));

        assertThat(first.folded()).isEqualTo(AlertFolding.Folded.attached(openCase));
        assertThat(first.alert().status()).isEqualTo(AlertStatus.ATTACHED);
        assertThat(first.alert().attachedCase()).contains(openCase);
        assertThat(duplicate.outcome()).isEqualTo(Alerts.Outcome.ALREADY);
        assertThat(duplicate.alert().status()).isEqualTo(AlertStatus.ATTACHED);
        assertThat(world.store.all()).singleElement().satisfies(alert -> {
            assertThat(alert.status()).as("never SUPPRESSED by the system").isNotEqualTo(AlertStatus.SUPPRESSED);
            assertThat(alert.suppression()).isEmpty();
            assertThat(alert.decision()).isEmpty();
        });
        assertThat(world.audited("risk.AlertSuppressed")).isEmpty();
        assertThat(world.store.log).doesNotContain("propose", "approve");
    }

    @Test
    @DisplayName("a later window of the same rule and subject is a new alert, folded on its own birth - into the same"
            + " open case")
    void aLaterWindowFoldsIntoTheOpenCase() {
        RiskCaseId openCase = RiskCaseId.of(EvaluationWorld.IDS.next());
        world.folding = (unitOfWork, born) -> {
            world.alerts.attach(unitOfWork, born.id(), openCase);
            return AlertFolding.Folded.attached(openCase);
        };
        SignalSubject subject = AlertWorld.freshParty();
        world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject, AlertWorld.day()));
        world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(subject,
                AlertWindow.containing(WindowKind.D1, AlertWorld.EVIDENCE_STAMP.plusSeconds(86_400))));
        assertThat(world.store.all()).hasSize(2)
                .allSatisfy(alert -> assertThat(alert.attachedCase()).contains(openCase));
    }

    @Test
    @DisplayName("the attach edge: the same case's retry converges; another case, or a suppressed alert, is"
            + " AlertNotOpen; an operational alert attaches to none")
    void theAttachEdge() {
        RiskAlert alert = world.openAlert();
        RiskCaseId first = RiskCaseId.of(EvaluationWorld.IDS.next());
        assertThat(world.alerts.attach(AlertWorld.UOW, alert.id(), first).status()).isEqualTo(AlertStatus.ATTACHED);
        assertThat(world.alerts.attach(AlertWorld.UOW, alert.id(), first).attachedCase()).contains(first);
        assertThatThrownBy(() -> world.alerts.attach(AlertWorld.UOW, alert.id(),
                RiskCaseId.of(EvaluationWorld.IDS.next()))).isInstanceOf(Alerts.AlertNotOpen.class);

        RiskAlert suppressed = world.openAlert();
        world.proposed(suppressed, world.analyst());
        world.alerts.approveSuppression(AlertWorld.UOW, suppressed.id(), "agreed", world.analyst(),
                AlertWorld.correlation());
        assertThatThrownBy(() -> world.alerts.attach(AlertWorld.UOW, suppressed.id(), first))
                .isInstanceOf(Alerts.AlertNotOpen.class);

        RiskAlert operational = world.born(AlertWorld.unclassified(AlertWorld.freshParty(), EvaluationWorld.IDS.next()));
        assertThatThrownBy(() -> world.alerts.attach(AlertWorld.UOW, operational.id(), first))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> world.alerts.attach(AlertWorld.UOW, RiskAlertId.of(EvaluationWorld.IDS.next()), first))
                .isInstanceOf(Alerts.AlertNotFound.class);
    }

    @Test
    @DisplayName("a folding that answers ATTACHED without attaching is refused - the raise does not report a fold the"
            + " row does not show")
    void aFoldingMustAttachWhatItSays() {
        world.folding = (unitOfWork, born) -> AlertFolding.Folded.attached(RiskCaseId.of(EvaluationWorld.IDS.next()));
        assertThatThrownBy(() -> world.alerts.raise(AlertWorld.UOW, AlertWorld.ato(AlertWorld.freshParty(),
                AlertWorld.day()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AlertFolding.Folded(AlertFolding.Folding.QUEUED,
                java.util.Optional.of(RiskCaseId.of(EvaluationWorld.IDS.next()))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
