package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-case over in-memory stores (`P13-TSK-016`; {@code RISK_LIFECYCLES.md} section 4.6; ADR-0110 decisions 2, 5-7;
 * {@code INV-RSK-06}, {@code INV-RSK-07}, {@code INV-RSK-10}, {@code INV-AUD-04}): one open case per subject and kind;
 * the revision compare-and-set keeping every act and refusing a stale view; four eyes; self-dealing failing closed;
 * the one-person close from {@code OPEN}; and {@code PROHIBITED} applied by the profile after the case, once, never
 * by {@code Cases} itself and never cleared by a recompute. The database's arbiters are {@code CaseDatabaseTest}'s
 * (written and owed, C5).
 */
@DisplayName("T-case: one open per subject and kind, the revision, four eyes, self-dealing (P13-TSK-016)")
class CasesTest {

    private final CaseWorld world = new CaseWorld();

    @Test
    @DisplayName("INV-RSK-06: one open case per subject and kind - a second opening answers the standing case, another"
            + " kind is its own case; the opening is audited and announced")
    void oneOpenCasePerSubjectAndKind() {
        SignalSubject subject = CaseWorld.party(EvaluationWorld.IDS.next());
        Actor analyst = world.analyst();
        Cases.Opened first = world.cases.open(CaseWorld.UOW, Cases.OpenRequest.of(CaseKind.FRAUD, subject),
                "the refund pattern", analyst, CaseWorld.correlation());
        Cases.Opened second = world.cases.open(CaseWorld.UOW, Cases.OpenRequest.of(CaseKind.FRAUD, subject),
                "the same pattern again", world.analyst(), CaseWorld.correlation());
        Cases.Opened aml = world.cases.open(CaseWorld.UOW, Cases.OpenRequest.of(CaseKind.AML, subject),
                "a laundering suspicion", analyst, CaseWorld.correlation());

        assertThat(first.opened()).isTrue();
        assertThat(second.opened()).isFalse();
        assertThat(second.riskCase().id()).isEqualTo(first.riskCase().id());
        assertThat(aml.riskCase().id()).isNotEqualTo(first.riskCase().id());
        assertThat(world.caseStore.all()).hasSize(2);
        RiskCase born = first.riskCase();
        assertThat(born.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(born.revision()).isZero();
        assertThat(born.level()).isEqualTo(CaseLevel.L1);
        assertThat(born.queue()).isEqualTo(CaseQueue.FIRST_LINE);
        assertThat(born.slaHours()).isEqualTo(SlaDeadlines.hours(CaseKind.FRAUD));
        assertThat(world.audited("risk.CaseOpened")).hasSize(2).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.changeSummary().orElseThrow()).contains("from=hand").doesNotContain(subject.ref());
        });
        assertThat(world.announced(Cases.OPENED_EVENT)).hasSize(2).allSatisfy(payload ->
                assertThat(payload).containsOnlyKeys("caseId", "kind", "status"));
        assertThat(world.movedInto(CaseKind.FRAUD, CaseStatus.OPEN)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-0110 decision 5, scenario 5: two investigators on one revision - the first act lands, the second"
            + " is refused CaseRevisionStale with nothing written, and after a re-read both acts are kept")
    void theRevisionCompareAndSetKeepsEveryAct() {
        RiskCase opened = world.openFraudCase();
        Actor alice = world.analyst();
        Actor bob = world.analyst();
        int seen = opened.revision();

        RiskCase noted = world.cases.note(CaseWorld.UOW, opened.id(), seen, "the payee is new", alice,
                CaseWorld.correlation()).riskCase();
        assertThat(noted.revision()).isEqualTo(seen + 1);
        assertThatExceptionOfType(Cases.CaseRevisionStale.class).isThrownBy(() -> world.cases.note(CaseWorld.UOW,
                opened.id(), seen, "the device is old", bob, CaseWorld.correlation()));
        assertThat(world.caseStore.actions(CaseWorld.UOW, opened.id())).hasSize(1);

        RiskCase reread = world.cases.current(CaseWorld.UOW, opened.id()).orElseThrow();
        RiskCase both = world.cases.note(CaseWorld.UOW, opened.id(), reread.revision(), "the device is old", bob,
                CaseWorld.correlation()).riskCase();
        assertThat(both.revision()).isEqualTo(2);
        assertThat(world.caseStore.actions(CaseWorld.UOW, opened.id())).extracting(CaseAction::text)
                .containsExactly(java.util.Optional.of("the payee is new"), java.util.Optional.of("the device is old"));
        assertThat(world.caseStore.events(CaseWorld.UOW, opened.id())).extracting(CaseEvent::revision)
                .containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("a take moves OPEN to IN_INVESTIGATION; the assignee's own take converges with nothing written;"
            + " another investigator takes it over, the status kept")
    void takeAndTakeOver() {
        RiskCase opened = world.openFraudCase();
        Actor alice = world.analyst();
        Actor bob = world.analyst();
        RiskCase taken = world.taken(opened, alice);
        assertThat(taken.status()).isEqualTo(CaseStatus.IN_INVESTIGATION);
        assertThat(taken.takenBy().orElseThrow().is(alice)).isTrue();

        Cases.Acted again = world.cases.take(CaseWorld.UOW, taken.id(), taken.revision(), alice,
                CaseWorld.correlation());
        assertThat(again.converged()).isTrue();
        assertThat(again.riskCase().revision()).isEqualTo(taken.revision());

        RiskCase over = world.taken(taken, bob);
        assertThat(over.status()).isEqualTo(CaseStatus.IN_INVESTIGATION);
        assertThat(over.takenBy().orElseThrow().is(bob)).isTrue();
        assertThat(world.audited("risk.CaseTaken")).hasSize(2);
        assertThat(world.movedInto(CaseKind.FRAUD, CaseStatus.IN_INVESTIGATION)).isEqualTo(1);
    }

    @Test
    @DisplayName("an escalation needs the case taken, raises one level to a named queue above the first line, is"
            + " announced, and stops at L3")
    void escalation() {
        RiskCase opened = world.openFraudCase();
        Actor alice = world.analyst();
        assertThatExceptionOfType(Cases.CaseNotTaken.class).isThrownBy(() -> world.cases.escalate(CaseWorld.UOW,
                opened.id(), opened.revision(), "SECOND_LINE", "beyond me", alice, CaseWorld.correlation()));
        RiskCase taken = world.taken(opened, alice);
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> world.cases.escalate(CaseWorld.UOW,
                taken.id(), taken.revision(), "FIRST_LINE", "back down", alice, CaseWorld.correlation()));
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> world.cases.escalate(CaseWorld.UOW,
                taken.id(), taken.revision(), "NOWHERE", "somewhere", alice, CaseWorld.correlation()));

        RiskCase second = world.cases.escalate(CaseWorld.UOW, taken.id(), taken.revision(), "SECOND_LINE",
                "beyond me", alice, CaseWorld.correlation()).riskCase();
        assertThat(second.status()).isEqualTo(CaseStatus.ESCALATED);
        assertThat(second.level()).isEqualTo(CaseLevel.L2);
        assertThat(second.queue()).isEqualTo(CaseQueue.SECOND_LINE);
        RiskCase senior = world.cases.escalate(CaseWorld.UOW, second.id(), second.revision(), "SENIOR_REVIEW",
                "a senior's call", alice, CaseWorld.correlation()).riskCase();
        assertThat(senior.level()).isEqualTo(CaseLevel.L3);
        assertThatExceptionOfType(Cases.CaseActNotAdmitted.class).isThrownBy(() -> world.cases.escalate(
                CaseWorld.UOW, senior.id(), senior.revision(), "SPECIALIST", "higher still", alice,
                CaseWorld.correlation()));
        assertThat(world.announced(Cases.ESCALATED_EVENT)).hasSize(2);
        assertThat(world.audited("risk.CaseEscalated")).hasSize(2)
                .allSatisfy(record -> assertThat(record.reason()).isPresent());
        assertThat(world.movedInto(CaseKind.FRAUD, CaseStatus.ESCALATED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a false positive is closed straight from OPEN by one person's reasoned act - decided by its proposal,"
            + " audited, announced and timed")
    void aCloseFromOpenNeedsNoTake() {
        RiskCase opened = world.openFraudCase();
        Actor alice = world.analyst();
        Cases.Decided closed = world.cases.proposeDisposition(CaseWorld.UOW, opened.id(), opened.revision(),
                "CLOSED_FALSE_POSITIVE", false, "the customer's own payroll", alice, CaseWorld.correlation());

        RiskCase disposed = closed.riskCase();
        assertThat(disposed.status()).isEqualTo(CaseStatus.DISPOSED);
        RiskCase.DispositionSlot slot = disposed.disposition().orElseThrow();
        assertThat(slot.disposition()).isEqualTo(Disposition.CLOSED_FALSE_POSITIVE);
        assertThat(slot.id()).isEqualTo(closed.dispositionId());
        assertThat(slot.proposedBy().is(alice)).isTrue();
        assertThat(slot.approvedBy().orElseThrow().is(alice)).isTrue();
        assertThat(slot.disposedAt()).isPresent();
        assertThat(world.audited("risk.DispositionProposed")).singleElement().satisfies(record ->
                assertThat(record.changeSummary().orElseThrow()).contains("secondPerson=NOT_REQUIRED"));
        assertThat(world.audited("risk.CaseDisposed")).hasSize(1);
        assertThat(world.announced(Cases.DISPOSED_EVENT)).singleElement().satisfies(payload ->
                assertThat(payload).containsEntry("disposition", "CLOSED_FALSE_POSITIVE").containsEntry("status",
                        "DISPOSED"));
        assertThat(world.resolutions).hasSize(1);
        assertThat(world.caseStore.events(CaseWorld.UOW, opened.id())).extracting(CaseEvent::kind)
                .containsExactly(CaseEventKind.OPENED, CaseEventKind.DISPOSED);
    }

    @Test
    @DisplayName("INV-RSK-07, scenario 15: a confirming disposition needs the case taken and a second person - the"
            + " proposer refused with nothing written, the second approves, their retry converges, a third finds it"
            + " disposed")
    void aConfirmedDispositionNeedsTheCaseTakenAndASecondPerson() {
        RiskCase opened = world.openFraudCase();
        Actor alice = world.analyst();
        Actor bob = world.analyst();
        assertThatExceptionOfType(Cases.CaseNotTaken.class).isThrownBy(() -> world.cases.proposeDisposition(
                CaseWorld.UOW, opened.id(), opened.revision(), "CONFIRMED_FRAUD", false, "it is fraud", alice,
                CaseWorld.correlation()));
        RiskCase taken = world.taken(opened, alice);
        Cases.Decided proposed = world.cases.proposeDisposition(CaseWorld.UOW, taken.id(), taken.revision(),
                "CONFIRMED_FRAUD", false, "it is fraud", alice, CaseWorld.correlation());
        RiskCase standing = proposed.riskCase();
        assertThat(standing.status()).isEqualTo(CaseStatus.IN_INVESTIGATION);
        assertThat(standing.standingProposal()).isPresent();

        Cases.SelfApprovalRefused refused = catchThrowableOfType(Cases.SelfApprovalRefused.class,
                () -> world.cases.approveDisposition(CaseWorld.UOW, standing.id(), proposed.dispositionId(),
                        standing.revision(), "I agree with myself", alice, CaseWorld.correlation()));
        assertThat(refused).isNotNull();
        assertThat(world.cases.current(CaseWorld.UOW, standing.id()).orElseThrow().revision())
                .isEqualTo(standing.revision());
        world.cases.recordRefusal(CaseWorld.UOW, refused, alice, CaseWorld.correlation());
        assertThat(world.audited("risk.SelfApprovalRefused")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetType()).isEqualTo(Cases.TARGET_TYPE);
        });

        Cases.Decided approved = world.cases.approveDisposition(CaseWorld.UOW, standing.id(), proposed.dispositionId(),
                standing.revision(), "the evidence holds", bob, CaseWorld.correlation());
        assertThat(approved.riskCase().status()).isEqualTo(CaseStatus.DISPOSED);
        assertThat(approved.riskCase().disposition().orElseThrow().approvedBy().orElseThrow().is(bob)).isTrue();
        Cases.Decided retried = world.cases.approveDisposition(CaseWorld.UOW, standing.id(), proposed.dispositionId(),
                standing.revision(), "the evidence holds", bob, CaseWorld.correlation());
        assertThat(retried.replayed()).isTrue();
        assertThatExceptionOfType(Cases.CaseNotOpen.class).isThrownBy(() -> world.cases.approveDisposition(
                CaseWorld.UOW, standing.id(), proposed.dispositionId(), standing.revision(), "me too", world.analyst(),
                CaseWorld.correlation()));
        assertThat(world.audited("risk.DispositionApproved")).hasSize(1);
        assertThat(world.audited("risk.CaseDisposed")).hasSize(1);
    }

    @Test
    @DisplayName("one proposal stands at a time; a decision names the standing one; a rejection - the proposer's"
            + " withdrawal too - clears the slot and keeps the case open for a new proposal")
    void aRejectionClearsTheSlot() {
        Actor alice = world.analyst();
        RiskCase taken = world.taken(world.openFraudCase(), alice);
        Cases.Decided proposed = world.cases.proposeDisposition(CaseWorld.UOW, taken.id(), taken.revision(),
                "REFERRED", false, "for the payments desk", alice, CaseWorld.correlation());
        RiskCase standing = proposed.riskCase();
        assertThatExceptionOfType(Cases.CaseActNotAdmitted.class).isThrownBy(() -> world.cases.proposeDisposition(
                CaseWorld.UOW, standing.id(), standing.revision(), "CONFIRMED_FRAUD", false, "or this", alice,
                CaseWorld.correlation()));
        assertThatExceptionOfType(Cases.CaseActNotAdmitted.class).isThrownBy(() -> world.cases.approveDisposition(
                CaseWorld.UOW, standing.id(), EvaluationWorld.IDS.next(), standing.revision(), "another one", world.analyst(),
                CaseWorld.correlation()));

        RiskCase rejected = world.cases.rejectDisposition(CaseWorld.UOW, standing.id(), proposed.dispositionId(),
                standing.revision(), "withdrawn - more to read", alice, CaseWorld.correlation()).riskCase();
        assertThat(rejected.status()).isEqualTo(CaseStatus.IN_INVESTIGATION);
        assertThat(rejected.disposition()).isEmpty();
        assertThat(world.audited("risk.DispositionRejected")).singleElement().satisfies(record ->
                assertThat(record.changeSummary().orElseThrow()).contains("byProposer=true"));
        Cases.Decided again = world.cases.proposeDisposition(CaseWorld.UOW, rejected.id(), rejected.revision(),
                "CLOSED_NO_ACTION", false, "nothing to act on", alice, CaseWorld.correlation());
        assertThat(again.riskCase().status()).isEqualTo(CaseStatus.DISPOSED);
        assertThat(again.dispositionId()).isNotEqualTo(proposed.dispositionId());
    }

    @Test
    @DisplayName("a disposed case is final: every act on it is CaseNotOpen")
    void aDisposedCaseIsFinal() {
        RiskCase opened = world.openFraudCase();
        Actor alice = world.analyst();
        RiskCase disposed = world.cases.proposeDisposition(CaseWorld.UOW, opened.id(), opened.revision(),
                "CLOSED_NO_ACTION", false, "nothing here", alice, CaseWorld.correlation()).riskCase();
        int revision = disposed.revision();
        assertThatExceptionOfType(Cases.CaseNotOpen.class).isThrownBy(() -> world.cases.note(CaseWorld.UOW,
                disposed.id(), revision, "one more thing", alice, CaseWorld.correlation()));
        assertThatExceptionOfType(Cases.CaseNotOpen.class).isThrownBy(() -> world.cases.take(CaseWorld.UOW,
                disposed.id(), revision, alice, CaseWorld.correlation()));
        assertThatExceptionOfType(Cases.CaseNotOpen.class).isThrownBy(() -> world.cases.proposeDisposition(
                CaseWorld.UOW, disposed.id(), revision, "CONFIRMED_FRAUD", false, "on second thoughts", alice,
                CaseWorld.correlation()));
        assertThat(world.cases.current(CaseWorld.UOW, disposed.id()).orElseThrow().revision()).isEqualTo(revision);
    }

    @Test
    @DisplayName("self-dealing fails closed: the analyst's own party's case, an analyst placed in no party and a subject"
            + " placed in no party are each refused before anything is written; the refusal is recorded FAILED")
    void selfDealingFailsClosed() {
        UUID customer = EvaluationWorld.IDS.next();
        RiskCase theirs = world.cases.open(CaseWorld.UOW, Cases.OpenRequest.of(CaseKind.FRAUD,
                CaseWorld.party(customer)), "the pattern", world.analyst(), CaseWorld.correlation()).riskCase();
        Actor own = world.analystOf(customer);
        RiskAssessments.SelfDealingRefused refused = catchThrowableOfType(RiskAssessments.SelfDealingRefused.class,
                () -> world.cases.note(CaseWorld.UOW, theirs.id(), theirs.revision(), "mine", own,
                        CaseWorld.correlation()));
        assertThat(refused.refusal()).isEqualTo(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY);
        assertThatExceptionOfType(RiskAssessments.SelfDealingRefused.class).isThrownBy(() -> world.cases.open(
                CaseWorld.UOW, Cases.OpenRequest.of(CaseKind.FRAUD, CaseWorld.party(customer)), "mine", own,
                CaseWorld.correlation()));
        Actor nobody = new Actor("analyst-unplaced", com.finapp.platform.security.ActorType.EMPLOYEE);
        assertThatExceptionOfType(IllegalArgumentException.class).as("never the platform's act").isThrownBy(() ->
                world.cases.take(CaseWorld.UOW, theirs.id(), theirs.revision(),
                        new Actor("system", com.finapp.platform.security.ActorType.SYSTEM), CaseWorld.correlation()));
        Actor session = new Actor("analyst-session", com.finapp.platform.security.ActorType.CUSTOMER);
        world.partyOfActor.put(session.id(), EvaluationWorld.IDS.next());
        assertThat(world.cases.note(CaseWorld.UOW, theirs.id(), theirs.revision(), "a session's analyst", session,
                CaseWorld.correlation()).riskCase().revision()).as("a CUSTOMER-typed session is a person")
                .isEqualTo(theirs.revision() + 1);
        RiskCase noted = world.cases.current(CaseWorld.UOW, theirs.id()).orElseThrow();
        assertThat(catchThrowableOfType(RiskAssessments.SelfDealingRefused.class, () -> world.cases.take(
                CaseWorld.UOW, theirs.id(), theirs.revision(), nobody, CaseWorld.correlation())).refusal())
                .isEqualTo(RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE);
        SignalSubject merchant = SignalSubject.of(SubjectKind.MERCHANT, EvaluationWorld.IDS.next());
        assertThat(catchThrowableOfType(RiskAssessments.SelfDealingRefused.class, () -> world.cases.open(
                CaseWorld.UOW, Cases.OpenRequest.of(CaseKind.MERCHANT_ABUSE, merchant), "a merchant", world.analyst(),
                CaseWorld.correlation())).refusal())
                .isEqualTo(RiskAssessments.SelfDealingRefused.Cause.SUBJECT_UNPLACEABLE);
        assertThat(world.caseStore.all()).hasSize(1);
        assertThat(world.caseStore.actions(CaseWorld.UOW, theirs.id())).as("the refusals wrote nothing").hasSize(1);
        assertThat(noted.revision()).isEqualTo(theirs.revision() + 1);

        world.cases.recordRefusal(CaseWorld.UOW, theirs.id().value().toString(), refused, own, CaseWorld.correlation());
        AuditRecord record = world.audited("risk.SelfDealingRefused").get(0);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(record.changeSummary().orElseThrow()).contains("act=case-note", "OWN_PARTY")
                .doesNotContain(customer.toString());

        // D6: placed through the accountable party, a merchant's case is workable.
        world.partyOfSubject.put(merchant, EvaluationWorld.IDS.next());
        assertThat(world.cases.open(CaseWorld.UOW, Cases.OpenRequest.of(CaseKind.MERCHANT_ABUSE, merchant),
                "a merchant's refunds", world.analyst(), CaseWorld.correlation()).opened()).isTrue();
    }

    @Test
    @DisplayName("by hand: a kind on its subject kind only, COLLECTIONS never (-025's); notes screened and bounded;"
            + " evidence by reference, at least one")
    void theRequestsTheCaseTakes() {
        Actor alice = world.analyst();
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> world.cases.open(CaseWorld.UOW,
                Cases.OpenRequest.of(CaseKind.COLLECTIONS, CaseWorld.party(EvaluationWorld.IDS.next())), "a default", alice,
                CaseWorld.correlation()));
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> world.cases.open(CaseWorld.UOW,
                Cases.OpenRequest.of(CaseKind.MERCHANT_ABUSE, CaseWorld.party(EvaluationWorld.IDS.next())), "a party?", alice,
                CaseWorld.correlation()));
        assertThatExceptionOfType(RiskReasons.ReasonRequired.class).isThrownBy(() -> world.cases.open(CaseWorld.UOW,
                Cases.OpenRequest.of(CaseKind.FRAUD, CaseWorld.party(EvaluationWorld.IDS.next())), " ", alice,
                CaseWorld.correlation()));

        RiskCase opened = world.openFraudCase();
        assertThatExceptionOfType(RiskReasons.ReasonRequired.class).isThrownBy(() -> world.cases.note(CaseWorld.UOW,
                opened.id(), opened.revision(), "card 4111 1111 1111 1111 was used", alice, CaseWorld.correlation()));
        assertThatExceptionOfType(RiskReasons.ReasonRequired.class).isThrownBy(() -> world.cases.note(CaseWorld.UOW,
                opened.id(), opened.revision(), "x".repeat(RiskReasons.BOUND + 1), alice, CaseWorld.correlation()));
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> world.cases.attachEvidence(
                CaseWorld.UOW, opened.id(), opened.revision(), AlertEvidence.NONE, "nothing", alice,
                CaseWorld.correlation()));
        RiskAssessmentId assessment = RiskAssessmentId.of(EvaluationWorld.IDS.next());
        RiskCase attached = world.cases.attachEvidence(CaseWorld.UOW, opened.id(), opened.revision(),
                AlertEvidence.assessment(assessment), "the earlier refusal", alice, CaseWorld.correlation())
                .riskCase();
        CaseAction evidence = world.caseStore.actions(CaseWorld.UOW, attached.id()).get(0);
        assertThat(evidence.kind()).isEqualTo(CaseActionKind.EVIDENCE_ATTACHED);
        assertThat(evidence.evidence().assessments()).containsExactly(assessment);
        assertThat(world.audited("risk.CaseNoted")).singleElement().satisfies(record ->
                assertThat(record.changeSummary().orElseThrow()).contains("act=evidence", "assessments=1")
                        .doesNotContain(assessment.value().toString()));
    }

    @Test
    @DisplayName("PROHIBITED is asked only of a confirming disposition of a party's case")
    void prohibitIsAskedOnlyOfAConfirmingPartyCase() {
        Actor alice = world.analyst();
        RiskCase taken = world.taken(world.openFraudCase(), alice);
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> world.cases.proposeDisposition(
                CaseWorld.UOW, taken.id(), taken.revision(), "CLOSED_NO_ACTION", true, "and prohibit", alice,
                CaseWorld.correlation()));
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> world.cases.proposeDisposition(
                CaseWorld.UOW, taken.id(), taken.revision(), "REFERRED", true, "and prohibit", alice,
                CaseWorld.correlation()));
        SignalSubject merchant = SignalSubject.of(SubjectKind.MERCHANT, EvaluationWorld.IDS.next());
        world.partyOfSubject.put(merchant, EvaluationWorld.IDS.next());
        RiskCase merchantCase = world.taken(world.cases.open(CaseWorld.UOW, Cases.OpenRequest.of(
                CaseKind.MERCHANT_ABUSE, merchant), "refunds", alice, CaseWorld.correlation()).riskCase(), alice);
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> world.cases.proposeDisposition(
                CaseWorld.UOW, merchantCase.id(), merchantCase.revision(), "CONFIRMED_FRAUD", true, "prohibit it",
                alice, CaseWorld.correlation()));
        assertThatExceptionOfType(Cases.CaseRequestInvalid.class).isThrownBy(() -> world.cases.proposeDisposition(
                CaseWorld.UOW, taken.id(), taken.revision(), "CLOSED_SOMEHOW", false, "?", alice,
                CaseWorld.correlation()));
    }

    @Test
    @DisplayName("INV-RSK-10, the lock order: an approved prohibiting disposition writes the case alone - no profile"
            + " row touched under it; the profile applies it after, in its own step, once (a re-drive writes nothing);"
            + " and a recompute never clears it")
    void theProhibitionIsAppliedAfterTheCaseOnceAndNeverClearedByARecompute() {
        UUID customer = EvaluationWorld.IDS.next();
        Actor alice = world.analyst();
        Actor bob = world.analyst();
        RiskCase taken = world.taken(world.cases.open(CaseWorld.UOW, Cases.OpenRequest.of(CaseKind.FRAUD,
                CaseWorld.party(customer)), "mule pattern", alice, CaseWorld.correlation()).riskCase(), alice);
        Cases.Decided proposed = world.cases.proposeDisposition(CaseWorld.UOW, taken.id(), taken.revision(),
                "CONFIRMED_FRAUD", true, "mule account confirmed", alice, CaseWorld.correlation());
        assertThat(proposed.riskCase().disposition().orElseThrow().needsSecondPerson()).isTrue();
        Cases.Decided approved = world.cases.approveDisposition(CaseWorld.UOW, taken.id(), proposed.dispositionId(),
                proposed.riskCase().revision(), "confirmed by the bank", bob, CaseWorld.correlation());
        assertThat(approved.prohibits()).isTrue();
        assertThat(world.profileStore.log).as("the case act touched no profile row (never R8 under R4)").isEmpty();
        assertThat(world.profileStore.head(customer)).isEmpty();

        List<RiskCaseStore.PendingProhibition> due = world.cases.prohibitionsDue(CaseWorld.UOW, 10);
        assertThat(due).singleElement().satisfies(pending -> {
            assertThat(pending.partyId()).isEqualTo(customer);
            assertThat(pending.dispositionId()).isEqualTo(proposed.dispositionId());
        });
        ProfileRecompute.Outcome applied = world.recompute.applyDisposition(CaseWorld.UOW, customer,
                proposed.dispositionId(), CaseWorld.correlation());
        assertThat(applied.kind()).isEqualTo(ProfileRecompute.Kind.PROHIBITED_SET);
        assertThat(applied.rating()).contains(RiskRating.PROHIBITED);
        CustomerRiskProfileVersion version = world.profileStore.versions(CaseWorld.UOW, customer).get(0);
        assertThat(version.cause()).isEqualTo(ProfileCause.DISPOSITION);
        assertThat(version.dispositionId()).contains(proposed.dispositionId());
        assertThat(world.announced(ProfileRecompute.EVENT_TYPE)).singleElement().satisfies(payload ->
                assertThat(payload).containsEntry("rating", "PROHIBITED"));

        ProfileRecompute.Outcome again = world.recompute.applyDisposition(CaseWorld.UOW, customer,
                proposed.dispositionId(), CaseWorld.correlation());
        assertThat(again.kind()).isEqualTo(ProfileRecompute.Kind.ALREADY_APPLIED);
        assertThat(world.profileStore.versions(CaseWorld.UOW, customer)).hasSize(1);
        assertThat(world.cases.prohibitionsDue(CaseWorld.UOW, 10)).isEmpty();

        world.monitoringActive();
        ProfileRecompute.Outcome recomputed = world.recompute.recompute(CaseWorld.UOW, customer,
                CaseWorld.correlation());
        assertThat(recomputed.kind()).isEqualTo(ProfileRecompute.Kind.PROHIBITED_STANDS);
        assertThat(world.profileStore.head(customer).orElseThrow().rating()).contains(RiskRating.PROHIBITED);
        assertThat(world.profileStore.versions(CaseWorld.UOW, customer)).hasSize(1);
    }

    @Test
    @DisplayName("the history has exactly one row per revision and every person's act its action at that ordinal")
    void theHistoryIsOneRowPerRevision() {
        Actor alice = world.analyst();
        RiskCase opened = world.openFraudCase();
        RiskCase taken = world.taken(opened, alice);
        RiskCase noted = world.cases.note(CaseWorld.UOW, taken.id(), taken.revision(), "first read", alice,
                CaseWorld.correlation()).riskCase();
        RiskCase escalated = world.cases.escalate(CaseWorld.UOW, noted.id(), noted.revision(), "SPECIALIST",
                "a specialist's pattern", alice, CaseWorld.correlation()).riskCase();
        assertThat(world.caseStore.events(CaseWorld.UOW, opened.id())).extracting(CaseEvent::revision)
                .containsExactly(0, 1, 2, 3);
        assertThat(world.caseStore.actions(CaseWorld.UOW, opened.id())).extracting(CaseAction::ordinal)
                .containsExactly(1, 2, 3);
        assertThat(world.caseStore.events(CaseWorld.UOW, opened.id())).extracting(CaseEvent::kind).containsExactly(
                CaseEventKind.OPENED, CaseEventKind.TAKEN, CaseEventKind.NOTED, CaseEventKind.ESCALATED);
        assertThat(escalated.revision()).isEqualTo(3);
    }
}
