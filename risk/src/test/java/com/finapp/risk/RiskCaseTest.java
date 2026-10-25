package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The case value's machine (`P13-TSK-016`; {@code RISK_LIFECYCLES.md} section 4.6; ADR-0110 decisions 2, 5, 6;
 * {@code INV-RSK-06}, {@code INV-RSK-07}): every judgement it makes - the open case, the revision, the take, the
 * escalation's level, the proposal's and the decision's rules - and the shapes it refuses to hold (a taken case with no
 * investigator, a disposed case without a decided slot, a four-eyes slot decided by its proposer, PROHIBITED of a
 * non-confirming disposition). A disposed case admits nothing: it never re-opens.
 */
@DisplayName("the case's machine on the value: every edge and refusal, never re-opened (P13-TSK-016)")
class RiskCaseTest {

    private static final Instant AT = Instant.parse("2026-10-25T12:00:00Z");
    private static final RiskCase.Person ALICE = new RiskCase.Person("alice", ActorType.EMPLOYEE);
    private static final RiskCase.Person BOB = new RiskCase.Person("bob", ActorType.EMPLOYEE);

    @Test
    @DisplayName("born OPEN at revision 0 on the first line; the revision is compared exactly")
    void theRevision() {
        RiskCase born = riskCase(CaseStatus.OPEN, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.empty(),
                Optional.empty());
        assertThat(born.open()).isTrue();
        assertThat(born.taken()).isFalse();
        assertThatCode(() -> born.judgeRevision(0)).doesNotThrowAnyException();
        assertThatExceptionOfType(Cases.CaseRevisionStale.class).isThrownBy(() -> born.judgeRevision(1));
        assertThatExceptionOfType(Cases.CaseRevisionStale.class).isThrownBy(() -> born.judgeRevision(-1));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new RiskCase(born.id(), born.kind(),
                born.subject(), CaseStatus.OPEN, -1, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.empty(), 24, AT,
                Optional.empty(), Optional.empty(), Optional.empty(), AT, Optional.empty(), "flow"));
    }

    @Test
    @DisplayName("the take: OPEN to IN_INVESTIGATION, a taken case keeps its status on a take-over, the assignee's own"
            + " take is recognised")
    void theTake() {
        RiskCase open = riskCase(CaseStatus.OPEN, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.empty(), Optional.empty());
        assertThat(open.afterTake()).isEqualTo(CaseStatus.IN_INVESTIGATION);
        RiskCase taken = riskCase(CaseStatus.IN_INVESTIGATION, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.of(ALICE),
                Optional.empty());
        assertThat(taken.afterTake()).isEqualTo(CaseStatus.IN_INVESTIGATION);
        assertThat(taken.takenBy(new Actor("alice", ActorType.EMPLOYEE))).isTrue();
        assertThat(taken.takenBy(new Actor("bob", ActorType.EMPLOYEE))).isFalse();
        RiskCase escalated = riskCase(CaseStatus.ESCALATED, CaseLevel.L2, CaseQueue.SECOND_LINE, Optional.of(ALICE),
                Optional.empty());
        assertThat(escalated.afterTake()).isEqualTo(CaseStatus.ESCALATED);
    }

    @Test
    @DisplayName("the escalation: refused on an untaken case, refused to the first line, one level up, refused at L3")
    void theEscalation() {
        RiskCase open = riskCase(CaseStatus.OPEN, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.empty(), Optional.empty());
        assertThatExceptionOfType(Cases.CaseNotTaken.class).isThrownBy(() -> open.judgeEscalation(CaseQueue.SECOND_LINE));
        RiskCase taken = riskCase(CaseStatus.IN_INVESTIGATION, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.of(ALICE),
                Optional.empty());
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> taken.judgeEscalation(
                CaseQueue.FIRST_LINE));
        assertThat(taken.judgeEscalation(CaseQueue.SECOND_LINE)).isEqualTo(CaseLevel.L2);
        RiskCase second = riskCase(CaseStatus.ESCALATED, CaseLevel.L2, CaseQueue.SECOND_LINE, Optional.of(ALICE),
                Optional.empty());
        assertThat(second.judgeEscalation(CaseQueue.SPECIALIST)).isEqualTo(CaseLevel.L3);
        RiskCase top = riskCase(CaseStatus.ESCALATED, CaseLevel.L3, CaseQueue.SENIOR_REVIEW, Optional.of(ALICE),
                Optional.empty());
        assertThatExceptionOfType(Cases.CaseActNotAdmitted.class).isThrownBy(() -> top.judgeEscalation(
                CaseQueue.SPECIALIST));
        assertThat(CaseLevel.L3.next()).isEmpty();
    }

    @Test
    @DisplayName("the proposal: a one-person close from OPEN; a two persons' one only on a taken case; one standing at a"
            + " time; PROHIBITED only of a confirming disposition of a party's case")
    void theProposal() {
        RiskCase open = riskCase(CaseStatus.OPEN, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.empty(), Optional.empty());
        assertThatCode(() -> open.judgeProposal(Disposition.CLOSED_FALSE_POSITIVE, false)).doesNotThrowAnyException();
        assertThatCode(() -> open.judgeProposal(Disposition.CLOSED_NO_ACTION, false)).doesNotThrowAnyException();
        for (Disposition twoPersons : new Disposition[] {Disposition.CONFIRMED_FRAUD, Disposition.CONFIRMED_SUSPICIOUS,
                Disposition.REFERRED}) {
            assertThatExceptionOfType(Cases.CaseNotTaken.class).isThrownBy(() -> open.judgeProposal(twoPersons, false));
        }
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> open.judgeProposal(
                Disposition.CLOSED_NO_ACTION, true));
        RiskCase taken = riskCase(CaseStatus.IN_INVESTIGATION, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.of(ALICE),
                Optional.empty());
        assertThatCode(() -> taken.judgeProposal(Disposition.CONFIRMED_FRAUD, true)).doesNotThrowAnyException();
        RiskCase.DispositionSlot standing = slot(Disposition.CONFIRMED_FRAUD, false, Optional.empty());
        RiskCase proposed = riskCase(CaseStatus.IN_INVESTIGATION, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.of(ALICE),
                Optional.of(standing));
        assertThatExceptionOfType(Cases.CaseActNotAdmitted.class).isThrownBy(() -> proposed.judgeProposal(
                Disposition.CLOSED_NO_ACTION, false));
        assertThat(proposed.judgeDecision(standing.id())).isEqualTo(standing);
        assertThatExceptionOfType(Cases.CaseActNotAdmitted.class).isThrownBy(() -> proposed.judgeDecision(
                EvaluationWorld.IDS.next()));
        assertThatExceptionOfType(Cases.CaseActNotAdmitted.class).isThrownBy(() -> taken.judgeDecision(
                standing.id()));

        RiskCase merchant = new RiskCase(RiskCaseId.of(EvaluationWorld.IDS.next()), CaseKind.MERCHANT_ABUSE,
                SignalSubject.of(SubjectKind.MERCHANT, EvaluationWorld.IDS.next()), CaseStatus.IN_INVESTIGATION, 1, CaseLevel.L1,
                CaseQueue.FIRST_LINE, Optional.of(ALICE), 72, AT, Optional.empty(), Optional.empty(), Optional.empty(),
                AT, Optional.empty(), "flow");
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> merchant.judgeProposal(
                Disposition.CONFIRMED_FRAUD, true));
    }

    @Test
    @DisplayName("never re-opened: a disposed case refuses every act; the value refuses a DISPOSED case without a"
            + " decided slot, a decided slot on a live case, a four-eyes slot decided by its proposer, PROHIBITED of a"
            + " non-confirming disposition, a taken case with no investigator")
    void theShapesItRefuses() {
        RiskCase.DispositionSlot decided = slot(Disposition.CONFIRMED_FRAUD, false, Optional.of(BOB));
        RiskCase disposed = riskCase(CaseStatus.DISPOSED, CaseLevel.L2, CaseQueue.SECOND_LINE, Optional.of(ALICE),
                Optional.of(decided));
        assertThat(disposed.open()).isFalse();
        assertThatExceptionOfType(Cases.CaseNotOpen.class).isThrownBy(disposed::judgeOpen);

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> riskCase(CaseStatus.DISPOSED,
                CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.of(ALICE), Optional.empty()));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> riskCase(
                CaseStatus.IN_INVESTIGATION, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.of(ALICE),
                Optional.of(decided)));
        assertThatExceptionOfType(IllegalArgumentException.class).as("INV-RSK-07 on the value").isThrownBy(() ->
                slot(Disposition.CONFIRMED_SUSPICIOUS, false, Optional.of(ALICE)));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                slot(Disposition.CLOSED_NO_ACTION, true, Optional.empty()));
        assertThatCode(() -> slot(Disposition.CLOSED_FALSE_POSITIVE, false, Optional.of(ALICE)))
                .as("a one-person close is decided by its proposer").doesNotThrowAnyException();
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> riskCase(
                CaseStatus.IN_INVESTIGATION, CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.empty(), Optional.empty()));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> riskCase(CaseStatus.ESCALATED,
                CaseLevel.L1, CaseQueue.FIRST_LINE, Optional.of(ALICE), Optional.empty()));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> riskCase(CaseStatus.OPEN,
                CaseLevel.L2, CaseQueue.SECOND_LINE, Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("the history's shape: revision 0 is the opening alone; a person's step names its action; an attach"
            + " names what it attached")
    void theHistorysShape() {
        RiskCaseId id = RiskCaseId.of(EvaluationWorld.IDS.next());
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new CaseEvent(id, 1,
                CaseEventKind.OPENED, CaseStatus.OPEN, CaseLevel.L1, CaseQueue.FIRST_LINE, ALICE, Optional.empty(),
                Optional.empty(), Optional.empty(), "flow", Optional.empty()));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new CaseEvent(id, 1,
                CaseEventKind.NOTED, CaseStatus.OPEN, CaseLevel.L1, CaseQueue.FIRST_LINE, ALICE, Optional.empty(),
                Optional.empty(), Optional.empty(), "flow", Optional.empty()));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new CaseEvent(id, 1,
                CaseEventKind.ALERT_ATTACHED, CaseStatus.OPEN, CaseLevel.L1, CaseQueue.FIRST_LINE, ALICE,
                Optional.empty(), Optional.empty(), Optional.empty(), "flow", Optional.empty()));
        assertThatExceptionOfType(IllegalArgumentException.class).as("an action is a person's").isThrownBy(() ->
                new CaseAction(EvaluationWorld.IDS.next(), id, 1, CaseActionKind.NOTE,
                        new RiskCase.Person("system", ActorType.SYSTEM), Optional.of("a note"), AlertEvidence.NONE,
                        Optional.empty(), false, Optional.empty(), "flow", Optional.empty()));
        assertThatExceptionOfType(IllegalArgumentException.class).as("a take carries no text").isThrownBy(() ->
                new CaseAction(EvaluationWorld.IDS.next(), id, 1, CaseActionKind.TAKEN, ALICE, Optional.of("mine"),
                        AlertEvidence.NONE, Optional.empty(), false, Optional.empty(), "flow", Optional.empty()));
    }

    // -----------------------------------------------------------------

    private static RiskCase riskCase(CaseStatus status, CaseLevel level, CaseQueue queue,
            Optional<RiskCase.Person> takenBy, Optional<RiskCase.DispositionSlot> slot) {
        return new RiskCase(RiskCaseId.of(EvaluationWorld.IDS.next()), CaseKind.FRAUD, SignalSubject.of(SubjectKind.PARTY,
                EvaluationWorld.IDS.next()), status, 0, level, queue, takenBy, 24, AT.plusSeconds(86_400), Optional.empty(),
                Optional.empty(), Optional.empty(), AT, slot, "flow");
    }

    private static RiskCase.DispositionSlot slot(Disposition disposition, boolean prohibits,
            Optional<RiskCase.Person> approvedBy) {
        return new RiskCase.DispositionSlot(EvaluationWorld.IDS.next(), disposition, prohibits, ALICE, Optional.of(AT),
                approvedBy, approvedBy.map(person -> AT));
    }
}
