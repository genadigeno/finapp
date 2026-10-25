package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CausationId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The alert's machine and values (`P13-TSK-015`; {@code RISK_LIFECYCLES.md} section 4.5; {@code INV-RSK-06},
 * {@code INV-RSK-07}): {@code OPEN → ATTACHED | SUPPRESSED} only, nothing out of a terminal state; the stored value
 * refusing what the {@code CHECK}s refuse - a suppression by its proposer, an attach without a case, an operational alert
 * attached; the window a bucket of its kind; the evidence bounded references; the rule a closed vocabulary; the two
 * shapes of a raise.
 */
@DisplayName("the alert's machine: OPEN -> ATTACHED | SUPPRESSED, born once per (subject, rule, window start) (P13-TSK-015)")
class RiskAlertTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    @Test
    @DisplayName("the machine admits OPEN -> ATTACHED and OPEN -> SUPPRESSED, and nothing else - ATTACHED and"
            + " SUPPRESSED are terminal, OPEN -> OPEN is no edge")
    void theEdges() {
        for (AlertStatus from : AlertStatus.values()) {
            for (AlertStatus to : AlertStatus.values()) {
                boolean admitted = from == AlertStatus.OPEN && to != AlertStatus.OPEN;
                assertThat(from.admits(to)).as(from + " -> " + to).isEqualTo(admitted);
            }
        }
        assertThat(Arrays.stream(AlertStatus.values()).filter(AlertStatus::terminal))
                .containsExactly(AlertStatus.ATTACHED, AlertStatus.SUPPRESSED);
    }

    @Test
    @DisplayName("an alert is ATTACHED exactly when it names its case, and an operational alert never attaches")
    void theAttachmentIsWhole() {
        RiskAlert open = alert(AlertKind.AML, AlertStatus.OPEN, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(open.open()).isTrue();
        assertThat(open.foldsInto()).contains(CaseKind.AML);
        RiskCaseId caseId = RiskCaseId.of(EvaluationWorld.IDS.next());
        assertThatCode(() -> alert(AlertKind.AML, AlertStatus.ATTACHED, Optional.of(caseId), Optional.empty(),
                Optional.empty())).doesNotThrowAnyException();
        assertThatThrownBy(() -> alert(AlertKind.AML, AlertStatus.ATTACHED, Optional.empty(), Optional.empty(),
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> alert(AlertKind.AML, AlertStatus.OPEN, Optional.of(caseId), Optional.empty(),
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> alert(AlertKind.UNCLASSIFIED_TRANSACTION, AlertStatus.ATTACHED, Optional.of(caseId),
                Optional.empty(), Optional.empty()))
                .as("an operational alert opens no case")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(alert(AlertKind.VELOCITY_DIVERGENCE, AlertStatus.OPEN, Optional.empty(), Optional.empty(),
                Optional.empty()).foldsInto()).isEmpty();
    }

    @Test
    @DisplayName("INV-RSK-07: SUPPRESSED exactly when a second person approved a proposal - never by its proposer,"
            + " never without a proposal")
    void theSuppressionIsTwoPersons() {
        RiskAlert.Suppression proposal = new RiskAlert.Suppression(SuppressionReason.FALSE_POSITIVE, "payroll",
                "analyst-a", ActorType.EMPLOYEE, NOW);
        RiskAlert.Decision bySecond = new RiskAlert.Decision("analyst-b", ActorType.EMPLOYEE, NOW, "agreed");
        RiskAlert.Decision byProposer = new RiskAlert.Decision("analyst-a", ActorType.EMPLOYEE, NOW, "mine");
        assertThatCode(() -> alert(AlertKind.AML, AlertStatus.SUPPRESSED, Optional.empty(), Optional.of(proposal),
                Optional.of(bySecond))).doesNotThrowAnyException();
        assertThatThrownBy(() -> alert(AlertKind.AML, AlertStatus.SUPPRESSED, Optional.empty(), Optional.of(proposal),
                Optional.of(byProposer)))
                .as("[INV-RSK-07] the proposer never approves")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> alert(AlertKind.AML, AlertStatus.SUPPRESSED, Optional.empty(), Optional.empty(),
                Optional.of(bySecond))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> alert(AlertKind.AML, AlertStatus.SUPPRESSED, Optional.empty(), Optional.of(proposal),
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> alert(AlertKind.AML, AlertStatus.OPEN, Optional.empty(), Optional.of(proposal),
                Optional.of(bySecond))).isInstanceOf(IllegalArgumentException.class);
        RiskAlert awaiting = alert(AlertKind.AML, AlertStatus.OPEN, Optional.empty(), Optional.of(proposal),
                Optional.empty());
        assertThat(awaiting.suppressionAwaitsDecision()).isTrue();
        assertThat(awaiting.toString()).doesNotContain("payroll").doesNotContain("analyst-a");
    }

    @Test
    @DisplayName("the window is a bucket of its kind from a database instant - the cut's, a velocity window's own, a"
            + " stamp's - and a start off its boundary is refused")
    void theWindowIsABucket() {
        Instant stamp = Instant.parse("2026-10-25T09:41:17.123456Z");
        AlertWindow day = AlertWindow.containing(WindowKind.D1, stamp);
        assertThat(day.start()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
        assertThat(day.end()).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
        assertThat(AlertWindow.containing(WindowKind.H1, stamp).start()).isEqualTo(Instant.parse("2026-10-25T09:00:00Z"));
        assertThat(AlertWindow.containing(WindowKind.D7, stamp).start())
                .as("an ISO Monday").isEqualTo(Instant.parse("2026-10-19T00:00:00Z"));
        assertThat(AlertWindow.of(WindowKind.D1, new WindowCut(movement(), stamp))).isEqualTo(day);
        assertThat(AlertWindow.of(new VelocityWindowKey(VelocityDimension.PARTY, UUID.randomUUID().toString(),
                WindowKind.D1, day.start()))).isEqualTo(day);
        assertThatThrownBy(() -> AlertWindow.stored(WindowKind.D1, stamp))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AlertWindow.containing(WindowKind.D1, stamp.plusSeconds(3600)))
                .as("a later detection in the same day is the same window").isEqualTo(day);
    }

    @Test
    @DisplayName("evidence is references only: at most 100 of each kind, each named once")
    void theEvidenceIsBounded() {
        List<UUID> hundred = IntStream.range(0, AlertEvidence.BOUND).mapToObj(i -> EvaluationWorld.IDS.next()).toList();
        assertThat(AlertEvidence.journalEntries(hundred).size()).isEqualTo(100);
        List<UUID> tooMany = new ArrayList<>(hundred);
        tooMany.add(EvaluationWorld.IDS.next());
        assertThatThrownBy(() -> AlertEvidence.journalEntries(tooMany)).isInstanceOf(IllegalArgumentException.class);
        UUID once = EvaluationWorld.IDS.next();
        assertThatThrownBy(() -> AlertEvidence.journalEntries(List.of(once, once)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AlertEvidence.NONE.isEmpty()).isTrue();
        assertThat(AlertEvidence.journalEntries(hundred).toString()).doesNotContain(hundred.get(0).toString());
    }

    @Test
    @DisplayName("the rule is a closed vocabulary - the rule kinds and the two operational kinds - and a raise has two"
            + " shapes: a rule's (a rule kind, its policy, evidence) and an operational one (its own code, no policy)")
    void theRuleAndTheTwoShapes() {
        List<String> expected = new ArrayList<>(Arrays.stream(RuleKind.values()).map(Enum::name).toList());
        expected.add("UNCLASSIFIED_TRANSACTION");
        expected.add("VELOCITY_DIVERGENCE");
        // P13-TSK-025: the one fact kind's own code - a default's alert (CollectionsCaseRulesTest's third shape).
        expected.add("OBLIGATION_DEFAULTED");
        assertThat(AlertRule.codes()).containsExactlyElementsOf(expected);
        assertThat(AlertRule.named("NOT_A_RULE")).isEmpty();
        assertThat(AlertRule.of(RuleKind.STRUCTURING).ruleKind()).contains(RuleKind.STRUCTURING);
        assertThat(AlertRule.operational(AlertKind.VELOCITY_DIVERGENCE).operational()).isTrue();
        assertThatThrownBy(() -> AlertRule.operational(AlertKind.AML)).isInstanceOf(IllegalArgumentException.class);

        SignalSubject subject = AlertWorld.freshParty();
        AlertEvidence one = AlertEvidence.assessment(RiskAssessmentId.of(EvaluationWorld.IDS.next()));
        assertThatThrownBy(() -> new NewAlert(AlertKind.AML, AlertSeverity.LOW, subject, AlertRule.of(RuleKind.STRUCTURING),
                AlertWorld.day(), Optional.empty(), one, AlertWorld.correlation(), CausationId.of("c")))
                .as("a rule's alert names its policy").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewAlert.ofRule(AlertKind.AML, AlertSeverity.LOW, subject, RuleKind.STRUCTURING,
                AlertWorld.day(), AlertWorld.policy(), AlertEvidence.NONE, AlertWorld.correlation(), CausationId.of("c")))
                .as("a rule's alert names its evidence").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewAlert(AlertKind.UNCLASSIFIED_TRANSACTION, AlertSeverity.LOW, subject,
                AlertRule.of(RuleKind.STRUCTURING), AlertWorld.day(), Optional.empty(), one, AlertWorld.correlation(),
                CausationId.of("c")))
                .as("an operational alert's rule is its own code").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewAlert(AlertKind.AML, AlertSeverity.LOW, subject,
                AlertRule.operational(AlertKind.VELOCITY_DIVERGENCE), AlertWorld.day(), Optional.of(AlertWorld.policy()),
                one, AlertWorld.correlation(), CausationId.of("c")))
                .as("a rule's alert is never raised under an operational code").isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> NewAlert.ofRule(AlertKind.SCREENING_HIT, AlertSeverity.CRITICAL, subject,
                RuleKind.SCREENING_HIT, AlertWorld.day(), AlertWorld.policy(), one, AlertWorld.correlation(),
                CausationId.of("c")))
                .as("SCREENING_HIT names an alert kind and a rule kind: its rule's alert is admitted")
                .doesNotThrowAnyException();
        assertThatCode(() -> NewAlert.operational(AlertKind.VELOCITY_DIVERGENCE, AlertSeverity.LOW, subject,
                AlertWorld.day(), AlertEvidence.NONE, AlertWorld.correlation(), CausationId.of("c")))
                .as("an operational alert may carry no reference").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the identity is (subject ref, rule code, window start), ordered - the raise order")
    void theKey() {
        SignalSubject subject = AlertWorld.freshParty();
        NewAlert alert = AlertWorld.ato(subject, AlertWorld.day());
        assertThat(alert.key()).isEqualTo(new AlertKey(subject.ref(), "RECENT_CREDENTIAL_CHANGE",
                Instant.parse("2026-10-25T00:00:00Z")));
        AlertKey a = new AlertKey("a", "STRUCTURING", NOW);
        AlertKey b = new AlertKey("a", "STRUCTURING", NOW.plusSeconds(1));
        AlertKey c = new AlertKey("b", "AMOUNT_BAND", NOW);
        assertThat(List.of(c, b, a).stream().sorted().toList()).containsExactly(a, b, c);
        assertThat(alert.toString()).doesNotContain(subject.ref());
    }

    // -----------------------------------------------------------------

    private static RiskAlert alert(AlertKind kind, AlertStatus status, Optional<RiskCaseId> attached,
            Optional<RiskAlert.Suppression> suppression, Optional<RiskAlert.Decision> decision) {
        boolean operational = kind.operational();
        return new RiskAlert(RiskAlertId.of(EvaluationWorld.IDS.next()), kind, AlertSeverity.MEDIUM,
                AlertWorld.freshParty(),
                operational ? AlertRule.operational(kind) : AlertRule.of(RuleKind.STRUCTURING), AlertWorld.day(),
                operational ? Optional.empty() : Optional.of(AlertWorld.policy()),
                AlertEvidence.journalEntries(List.of(EvaluationWorld.IDS.next())), status, attached,
                attached.map(caseId -> NOW), suppression, decision, NOW, "flow", "cause");
    }

    private static VelocityMovement movement() {
        return VelocityMovement.of(EvaluationWorld.eur(100)).party(UUID.randomUUID()).build();
    }
}
