package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evidence read door (`P13-TSK-023`; the item `-018` (D9), `-020` and `-021` routed; {@code PHASE_13_PLAN.md}
 * section 11; {@code INV-RSK-08}; {@code DESIGN-023} D9): a reference the case's file holds is resolved - a signal's
 * codes, a journal entry's monitored transaction with its amount, currency and scale - after the file's own self-dealing
 * judgement; a reference the case does not hold, an assessment reference (its own door's) and a row not found are each
 * {@code EvidenceNotFound}; the reader's own party's case is refused; the serving is recorded {@code risk.CaseRead}
 * ({@code act=evidence}). Over HTTP the door is {@code RiskReportsApiTest}'s (written and owed, C5).
 */
@DisplayName("the evidence door: only what the case holds, after the file's judgement, recorded risk.CaseRead"
        + " (P13-TSK-023)")
class CaseEvidenceTest {

    private final OverrideWorld world = new OverrideWorld();
    private final CaseWorklist worklist = new CaseWorklist(world.caseStore, world.alertStore, world.actions,
            (unitOfWork, actor) -> Optional.ofNullable(world.partyOfActor.get(actor.id())),
            (unitOfWork, subject) -> Optional.ofNullable(world.partyOfSubject.get(subject)),
            (unitOfWork, record) -> world.audit.add(record), EvaluationWorld.IDS, EvaluationWorld.CLOCK);
    private final FakeRiskProofReads reads = new FakeRiskProofReads();
    private final CaseEvidence evidence = new CaseEvidence(worklist, reads, (unitOfWork, record) -> world.audit.add(record),
            EvaluationWorld.IDS, EvaluationWorld.CLOCK);

    @Test
    @DisplayName("a signal and a journal entry the case holds are resolved - the signal's codes, the transaction's amount"
            + " with its currency and scale - and each serving recorded risk.CaseRead naming the act and the reference")
    void whatTheCaseHoldsIsResolved() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.AML);
        RiskSignalId signalId = RiskSignalId.of(EvaluationWorld.IDS.next());
        UUID journalEntry = EvaluationWorld.IDS.next();
        attach(taken, signalId, journalEntry);
        RiskSignal signal = signal(signalId);
        reads.signals.put(signalId, signal);
        reads.transactions.put(journalEntry, transaction(journalEntry));
        Actor reader = world.analyst();

        CaseEvidence.Served served = evidence.read(EvaluationWorld.UOW, taken.riskCase().id(),
                CaseWorklist.EvidenceKind.SIGNAL, signalId.value(), reader);
        assertThat(served).isInstanceOfSatisfying(CaseEvidence.SignalServed.class,
                one -> assertThat(one.signal()).isEqualTo(signal));
        CaseEvidence.Served transaction = evidence.read(EvaluationWorld.UOW, taken.riskCase().id(),
                CaseWorklist.EvidenceKind.JOURNAL_ENTRY, journalEntry, reader);
        assertThat(transaction).isInstanceOfSatisfying(CaseEvidence.TransactionServed.class, one -> {
            assertThat(one.transaction().amountMinor()).isEqualTo(250_00L);
            assertThat(one.transaction().currency().code()).isEqualTo("EUR");
            assertThat(one.transaction().scale()).isEqualTo(2);
        });

        evidence.record(EvaluationWorld.UOW, taken.riskCase().id(), transaction, reader, CorrelationId.of("flow-1"));
        AuditRecord recorded = world.audited("risk.CaseRead").get(0);
        assertThat(recorded.targetType()).isEqualTo(Cases.TARGET_TYPE);
        assertThat(recorded.targetId()).isEqualTo(taken.riskCase().id().value().toString());
        assertThat(recorded.changeSummary().orElseThrow())
                .isEqualTo("act=evidence, kind=JOURNAL_ENTRY, evidence=" + journalEntry)
                .doesNotContain(taken.riskCase().subject().ref());
    }

    @Test
    @DisplayName("nothing is readable by id alone: a reference the case does not hold, an assessment reference (its own"
            + " door's) and a held reference whose row is not found are each EvidenceNotFound")
    void onlyWhatTheCaseHolds() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.AML);
        RiskSignalId held = RiskSignalId.of(EvaluationWorld.IDS.next());
        UUID journalEntry = EvaluationWorld.IDS.next();
        attach(taken, held, journalEntry);
        RiskSignalId other = RiskSignalId.of(EvaluationWorld.IDS.next());
        reads.signals.put(other, signal(other));
        Actor reader = world.analyst();

        assertThatThrownBy(() -> evidence.read(EvaluationWorld.UOW, taken.riskCase().id(),
                CaseWorklist.EvidenceKind.SIGNAL, other.value(), reader)).isInstanceOf(CaseEvidence.EvidenceNotFound.class);
        assertThatThrownBy(() -> evidence.read(EvaluationWorld.UOW, taken.riskCase().id(),
                CaseWorklist.EvidenceKind.ASSESSMENT, journalEntry, reader))
                .isInstanceOf(CaseEvidence.EvidenceNotFound.class);
        assertThatThrownBy(() -> evidence.read(EvaluationWorld.UOW, taken.riskCase().id(),
                CaseWorklist.EvidenceKind.JOURNAL_ENTRY, journalEntry, reader))
                .as("held, but not monitored").isInstanceOf(CaseEvidence.EvidenceNotFound.class);
        assertThat(reads.calls).as("the unheld signal was never read").doesNotContain("signal");
    }

    @Test
    @DisplayName("the file's own judgement holds: the reader's own party's case is refused before any evidence is read")
    void selfDealingIsRefused() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.AML);
        RiskSignalId signalId = RiskSignalId.of(EvaluationWorld.IDS.next());
        attach(taken, signalId, EvaluationWorld.IDS.next());
        reads.signals.put(signalId, signal(signalId));
        Actor reader = world.analyst();
        world.partyOfActor.put(reader.id(), UUID.fromString(taken.riskCase().subject().ref()));

        assertThatThrownBy(() -> evidence.read(EvaluationWorld.UOW, taken.riskCase().id(),
                CaseWorklist.EvidenceKind.SIGNAL, signalId.value(), reader))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class);
        assertThat(reads.calls).isEmpty();
        assertThat(world.audited("risk.CaseRead")).isEmpty();
    }

    // ------------------------------------------------------------------

    private void attach(OverrideWorld.Taken taken, RiskSignalId signal, UUID journalEntry) {
        RiskCase now = world.caseOf(taken.riskCase().id());
        world.cases.attachEvidence(EvaluationWorld.UOW, now.id(), now.revision(), new AlertEvidence(List.of(signal),
                List.of(), List.of(journalEntry)), "the device and the ledger line", taken.investigator(),
                OverrideWorld.correlation());
    }

    private static RiskSignal signal(RiskSignalId id) {
        return new RiskSignal(id, SignalKind.CREDENTIAL_CHANGED, new SignalSubject(SubjectKind.IDENTITY,
                UUID.randomUUID().toString()), new RiskSignal.SourceEvent(EventId.next(EvaluationWorld.IDS),
                "identity.CredentialChanged", 1, "identity", CorrelationId.of("flow"), CausationId.of("cause")),
                Instant.parse("2026-10-23T09:00:00Z"), SignalAttributes.none());
    }

    private static MonitoredTransaction transaction(UUID journalEntry) {
        return new MonitoredTransaction(MonitoredTransactionId.next(EvaluationWorld.IDS), journalEntry,
                MonitoringWatermark.sequenceOf(journalEntry), MonitoredEntry.EntryType.POSTING, "transfer",
                MonitoredKind.TRANSFER, Optional.of(MonitoredSubjectKind.ACCOUNT), Optional.of(UUID.randomUUID()),
                Optional.of(MonitoredDirection.OUT), MonitoredCounterpartyKind.CUSTOMER, Optional.of(UUID.randomUUID()),
                Optional.empty(), 250_00, 2, CurrencyCode.of("EUR"), LocalDate.of(2026, 10, 23),
                Instant.parse("2026-10-23T09:00:00Z"), Optional.empty());
    }
}
