package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CausationId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The case queue and the case file over the in-memory stores (`P13-TSK-018`; the entry's {@code CaseQueueTest} -
 * {@code DESIGN-018} D13; {@code PHASE_13_PLAN.md} sections 9, 11; {@code INV-RSK-08}): the open cases oldest first by
 * {@code (opened_at, id)} unless a status is named, keyset-paged, self-dealing withheld, each page recorded; one case's
 * file whole - its alerts, investigator actions, history and downstream actions ({@code RiskActions.ofCase}) and its
 * evidence <em>by reference only</em> - recorded {@code risk.CaseRead}, refused for the reader's own party or an
 * unplaceable subject. The database's half is {@code QueueDatabaseTest}'s (written and owed, C5).
 */
@DisplayName("the case queue and the case file: oldest first, withheld or refused for self-dealing, recorded"
        + " (P13-TSK-018)")
class CaseWorklistTest {

    private final OverrideWorld world = new OverrideWorld();
    private final CaseWorklist worklist = new CaseWorklist(world.caseStore, world.alertStore, world.actions,
            (unitOfWork, actor) -> Optional.ofNullable(world.partyOfActor.get(actor.id())),
            (unitOfWork, subject) -> Optional.ofNullable(world.partyOfSubject.get(subject)),
            (unitOfWork, record) -> world.audit.add(record), EvaluationWorld.IDS, EvaluationWorld.CLOCK);

    @Test
    @DisplayName("the queue: the open cases oldest first unless a status is named, a kind filter, paged after the last"
            + " case served; a disposed case only by name; each page recorded risk.CaseRead with the ids served")
    void theQueue() {
        List<RiskCase> opened = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            world.advance(Duration.ofSeconds(1));
            opened.add(world.partyCase(CaseKind.FRAUD).riskCase());
        }
        world.advance(Duration.ofSeconds(1));
        OverrideWorld.Taken ato = world.partyCase(CaseKind.ATO);
        world.advance(Duration.ofSeconds(1));
        OverrideWorld.Taken closed = world.partyCase(CaseKind.FRAUD);
        world.cases.proposeDisposition(EvaluationWorld.UOW, closed.riskCase().id(), world.caseOf(closed.riskCase().id())
                .revision(), "CLOSED_FALSE_POSITIVE", false, "the customer's payroll", closed.investigator(),
                OverrideWorld.correlation());
        Actor reader = world.analyst();

        CaseWorklist.Page first = page(Optional.empty(), Optional.empty(), Optional.empty(), 2, reader);
        assertThat(first.cases()).extracting(RiskCase::id).containsExactly(opened.get(0).id(), opened.get(1).id());
        assertThat(first.next()).contains(opened.get(1).id());
        CaseWorklist.Page second = page(Optional.empty(), Optional.empty(), first.next(), 2, reader);
        assertThat(second.cases()).extracting(RiskCase::id).containsExactly(opened.get(2).id(), ato.riskCase().id());
        assertThat(second.next()).isEmpty();

        assertThat(page(Optional.empty(), Optional.of("ATO"), Optional.empty(), 20, reader).cases())
                .extracting(RiskCase::id).containsExactly(ato.riskCase().id());
        assertThat(page(Optional.of("DISPOSED"), Optional.empty(), Optional.empty(), 20, reader).cases())
                .extracting(RiskCase::id).containsExactly(closed.riskCase().id());
        assertThat(page(Optional.of("IN_INVESTIGATION"), Optional.of("FRAUD"), Optional.empty(), 20, reader).cases())
                .hasSize(3);

        worklist.record(EvaluationWorld.UOW, first, reader, OverrideWorld.correlation());
        AuditRecord recorded = world.audited("risk.CaseRead").get(0);
        assertThat(recorded.targetType()).isEqualTo(CaseWorklist.QUEUE_TARGET_TYPE);
        assertThat(recorded.targetId()).isEqualTo(CaseWorklist.OPEN_ANY);
        assertThat(recorded.changeSummary().orElseThrow())
                .contains("page: 2 case(s), status=OPEN_ANY, kind=ANY, after=none")
                .contains(opened.get(0).id().value().toString()).contains("more=true")
                .doesNotContain(opened.get(0).subject().ref());

        assertThatThrownBy(() -> CaseWorklist.Filter.named(Optional.of("CLOSED"), Optional.empty()))
                .isInstanceOf(Queues.QueueRequestInvalid.class);
        assertThatThrownBy(() -> page(Optional.empty(), Optional.empty(),
                Optional.of(RiskCaseId.of(EvaluationWorld.IDS.next())), 20, reader))
                .isInstanceOf(Queues.QueueRequestInvalid.class).hasMessageContaining("the cursor names no case");
    }

    @Test
    @DisplayName("self-dealing withheld from a page: the reader's own party's case and the case of a merchant of the"
            + " reader's organisation are never served; an unplaced reader refused, recorded FAILED against the queue")
    void selfDealingIsWithheld() {
        UUID own = EvaluationWorld.IDS.next();
        Actor reader = world.analyst();
        world.partyOfActor.put(reader.id(), own);
        RiskCase ownCase = world.cases.open(EvaluationWorld.UOW, Cases.OpenRequest.of(CaseKind.FRAUD,
                CaseWorld.party(own)), "a pattern", world.analyst(), OverrideWorld.correlation()).riskCase();
        world.advance(Duration.ofSeconds(1));
        OverrideWorld.Taken merchant = world.merchantCase();
        world.partyOfSubject.put(merchant.riskCase().subject(), own);
        world.advance(Duration.ofSeconds(1));
        RiskCase other = world.partyCase(CaseKind.FRAUD).riskCase();

        CaseWorklist.Page page = page(Optional.empty(), Optional.empty(), Optional.empty(), 20, reader);
        assertThat(page.cases()).extracting(RiskCase::id).containsExactly(other.id())
                .doesNotContain(ownCase.id(), merchant.riskCase().id());
        assertThat(page.withheld()).as("the merchant's; the own party's left out by the statement").isEqualTo(1);

        Actor unplaced = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        assertThatThrownBy(() -> page(Optional.empty(), Optional.empty(), Optional.empty(), 20, unplaced))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .satisfies(refused -> worklist.recordRefusal(EvaluationWorld.UOW,
                        CaseWorklist.Filter.named(Optional.empty(), Optional.empty()),
                        (RiskAssessments.SelfDealingRefused) refused, unplaced, OverrideWorld.correlation()));
        AuditRecord refused = world.audited("risk.SelfDealingRefused").get(0);
        assertThat(refused.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(refused.targetType()).isEqualTo(CaseWorklist.QUEUE_TARGET_TYPE);
        assertThat(refused.changeSummary().orElseThrow()).contains("act=case-queue-read, cause=UNPLACEABLE");
    }

    @Test
    @DisplayName("the file: the case, its attached alert, its investigator actions, its history and its downstream"
            + " action, and every evidence reference it holds - by reference, each once; recorded risk.CaseRead with"
            + " the counts, never a note or a subject")
    void theFile() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.FRAUD);
        RiskCaseId id = taken.riskCase().id();
        RiskAssessmentId alertsAssessment = RiskAssessmentId.of(EvaluationWorld.IDS.next());
        RiskAlert alert = world.alertStore.insert(EvaluationWorld.UOW, RiskAlertId.next(EvaluationWorld.IDS),
                NewAlert.ofRule(AlertKind.FRAUD, AlertSeverity.MEDIUM, taken.riskCase().subject(),
                        RuleKind.VELOCITY_COUNT, AlertWorld.day(), AlertWorld.policy(),
                        AlertEvidence.assessment(alertsAssessment), OverrideWorld.correlation(),
                        CausationId.of("file-suite"))).orElseThrow();
        OverrideWorld.asSystem(() -> world.cases.fold(EvaluationWorld.UOW, alert));
        RiskSignalId signal = RiskSignalId.of(EvaluationWorld.IDS.next());
        UUID journalEntry = EvaluationWorld.IDS.next();
        RiskCase now = world.caseOf(id);
        world.cases.attachEvidence(EvaluationWorld.UOW, id, now.revision(), new AlertEvidence(List.of(signal),
                List.of(alertsAssessment), List.of(journalEntry)), "the device and the ledger line",
                taken.investigator(), OverrideWorld.correlation());
        now = world.caseOf(id);
        world.cases.note(EvaluationWorld.UOW, id, now.revision(), "a private note of the investigator",
                taken.investigator(), OverrideWorld.correlation());
        RiskAction proposed = world.propose(taken, ActionKind.REVOKE_SESSIONS, Optional.empty()).action();

        Actor reader = world.analyst();
        CaseWorklist.CaseFile file = worklist.read(EvaluationWorld.UOW, id, reader);
        assertThat(file.riskCase().id()).isEqualTo(id);
        assertThat(file.alerts()).extracting(RiskAlert::id).containsExactly(alert.id());
        assertThat(file.actions()).extracting(CaseAction::kind).contains(CaseActionKind.TAKEN, CaseActionKind.NOTE,
                CaseActionKind.EVIDENCE_ATTACHED, CaseActionKind.ACTION_PROPOSED);
        assertThat(file.history()).extracting(CaseEvent::revision).isSorted().first().isEqualTo(0);
        assertThat(file.riskActions()).extracting(RiskAction::id).containsExactly(proposed.id());
        assertThat(file.evidence()).containsExactly(
                new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.ASSESSMENT, alertsAssessment.value()),
                new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.SIGNAL, signal.value()),
                new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.JOURNAL_ENTRY, journalEntry));

        worklist.record(EvaluationWorld.UOW, file, reader, OverrideWorld.correlation());
        AuditRecord recorded = world.audited("risk.CaseRead").get(0);
        assertThat(recorded.targetType()).isEqualTo(Cases.TARGET_TYPE);
        assertThat(recorded.targetId()).isEqualTo(id.value().toString());
        assertThat(recorded.changeSummary().orElseThrow())
                .contains("kind=FRAUD, status=IN_INVESTIGATION")
                .contains("alerts=1").contains("riskActions=1").contains("evidence=3")
                .doesNotContain("a private note").doesNotContain(taken.riskCase().subject().ref());
    }

    @Test
    @DisplayName("the file refused fail closed: the reader's own party's case (OWN_PARTY), a merchant's case whose"
            + " organisation cannot be placed (SUBJECT_UNPLACEABLE), an unplaced reader (UNPLACEABLE) - each recorded"
            + " FAILED against the case; an unknown case is CaseNotFound; never the platform")
    void theFileRefusesSelfDealing() {
        UUID own = EvaluationWorld.IDS.next();
        Actor reader = world.analyst();
        world.partyOfActor.put(reader.id(), own);
        RiskCase ownCase = world.cases.open(EvaluationWorld.UOW, Cases.OpenRequest.of(CaseKind.FRAUD,
                CaseWorld.party(own)), "a pattern", world.analyst(), OverrideWorld.correlation()).riskCase();
        assertThatThrownBy(() -> worklist.read(EvaluationWorld.UOW, ownCase.id(), reader))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .satisfies(refused -> {
                    RiskAssessments.SelfDealingRefused self = (RiskAssessments.SelfDealingRefused) refused;
                    assertThat(self.refusal()).isEqualTo(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY);
                    worklist.recordRefusal(EvaluationWorld.UOW, ownCase.id(), self, reader,
                            OverrideWorld.correlation());
                });
        AuditRecord recorded = world.audited("risk.SelfDealingRefused").get(0);
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(recorded.targetId()).isEqualTo(ownCase.id().value().toString());
        assertThat(recorded.changeSummary().orElseThrow()).contains("act=case-read, cause=OWN_PARTY")
                .doesNotContain(own.toString());

        OverrideWorld.Taken merchant = world.merchantCase();
        world.partyOfSubject.remove(merchant.riskCase().subject());
        assertThatThrownBy(() -> worklist.read(EvaluationWorld.UOW, merchant.riskCase().id(), reader))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .extracting(refused -> ((RiskAssessments.SelfDealingRefused) refused).refusal())
                .isEqualTo(RiskAssessments.SelfDealingRefused.Cause.SUBJECT_UNPLACEABLE);

        Actor unplaced = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        assertThatThrownBy(() -> worklist.read(EvaluationWorld.UOW, ownCase.id(), unplaced))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .extracting(refused -> ((RiskAssessments.SelfDealingRefused) refused).refusal())
                .isEqualTo(RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE);
        assertThatThrownBy(() -> worklist.read(EvaluationWorld.UOW, RiskCaseId.of(EvaluationWorld.IDS.next()),
                reader)).isInstanceOf(Cases.CaseNotFound.class);
        assertThatThrownBy(() -> worklist.read(EvaluationWorld.UOW, ownCase.id(), Actor.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(world.audited("risk.CaseRead")).as("nothing refused was served").isEmpty();
    }

    @Test
    @DisplayName("the evidence is named, never resolved: the file's references are ids and kinds, and its opening"
            + " assessment comes first")
    void theEvidenceIsByReference() {
        RiskAssessmentId opening = RiskAssessmentId.of(EvaluationWorld.IDS.next());
        RiskAssessmentId attached = RiskAssessmentId.of(EvaluationWorld.IDS.next());
        RiskCase riskCase = new RiskCase(RiskCaseId.of(EvaluationWorld.IDS.next()), CaseKind.FRAUD,
                CaseWorld.party(EvaluationWorld.IDS.next()), CaseStatus.OPEN, 1, CaseLevel.L1, CaseQueue.FIRST_LINE,
                Optional.empty(), 24, world.now().plusSeconds(3600), Optional.empty(), Optional.of(opening),
                Optional.empty(), world.now(), Optional.empty(), "correlation");
        CaseEvent attachedEvent = new CaseEvent(riskCase.id(), 1, CaseEventKind.ASSESSMENT_ATTACHED, CaseStatus.OPEN,
                CaseLevel.L1, CaseQueue.FIRST_LINE, new RiskCase.Person("system", ActorType.SYSTEM), Optional.empty(),
                Optional.empty(), Optional.of(attached), "correlation", Optional.of(world.now()));
        assertThat(CaseWorklist.evidence(riskCase, List.of(), List.of(), List.of(attachedEvent))).containsExactly(
                new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.ASSESSMENT, opening.value()),
                new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.ASSESSMENT, attached.value()));
    }

    private CaseWorklist.Page page(Optional<String> status, Optional<String> kind, Optional<RiskCaseId> after, int limit,
            Actor reader) {
        return worklist.page(EvaluationWorld.UOW, CaseWorklist.Filter.named(status, kind), after,
                Queues.size(Optional.of(limit)), reader);
    }
}
