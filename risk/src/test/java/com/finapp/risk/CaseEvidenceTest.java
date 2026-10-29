package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<UUID, UUID> partyOfAccount = new HashMap<>();
    private final MonitoredParties accounts = new MonitoredParties() {
        @Override
        public Optional<UUID> partyOfAccount(Connection unitOfWork, UUID customerAccountId) {
            return Optional.ofNullable(partyOfAccount.get(customerAccountId));
        }

        @Override
        public Accounts accountsOf(Connection unitOfWork, UUID partyId) {
            return Accounts.NONE;
        }
    };
    private final CaseEvidence evidence = new CaseEvidence(worklist, reads,
            (unitOfWork, subject) -> Optional.ofNullable(world.partyOfSubject.get(subject)), accounts,
            (unitOfWork, record) -> world.audit.add(record), EvaluationWorld.IDS, EvaluationWorld.CLOCK);

    /** A third party's identity, account and counterparty account - placed, neither the case's nor the reader's. */
    private final UUID third = EvaluationWorld.IDS.next();
    private final SignalSubject thirdIdentity = SignalSubject.of(SubjectKind.IDENTITY, EvaluationWorld.IDS.next());
    private final UUID thirdAccount = EvaluationWorld.IDS.next();
    private final UUID thirdCounterparty = EvaluationWorld.IDS.next();

    {
        world.partyOfSubject.put(thirdIdentity, third);
        partyOfAccount.put(thirdAccount, third);
        partyOfAccount.put(thirdCounterparty, third);
    }

    @Test
    @DisplayName("a signal and a journal entry the case holds are resolved - the signal's codes, the transaction's amount"
            + " with its currency and scale - and each serving recorded risk.CaseRead naming the act and the reference")
    void whatTheCaseHoldsIsResolved() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.AML);
        RiskSignalId signalId = RiskSignalId.of(EvaluationWorld.IDS.next());
        UUID journalEntry = EvaluationWorld.IDS.next();
        attach(taken, signalId, journalEntry);
        RiskSignal signal = signal(signalId, thirdIdentity);
        reads.signals.put(signalId, signal);
        reads.transactions.put(journalEntry, transaction(journalEntry, thirdAccount, thirdCounterparty));
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
        reads.signals.put(other, signal(other, thirdIdentity));
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
        reads.signals.put(signalId, signal(signalId, thirdIdentity));
        Actor reader = world.analyst();
        world.partyOfActor.put(reader.id(), UUID.fromString(taken.riskCase().subject().ref()));

        assertThatThrownBy(() -> evidence.read(EvaluationWorld.UOW, taken.riskCase().id(),
                CaseWorklist.EvidenceKind.SIGNAL, signalId.value(), reader))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class);
        assertThat(reads.calls).isEmpty();
        assertThat(world.audited("risk.CaseRead")).isEmpty();
    }

    @Test
    @DisplayName("item 79 (the Phase 13 gate): a reference of the reader's own party attached to another party's case is"
            + " refused OWN_PARTY - a signal by its subject, a journal entry by its subject account or its customer"
            + " counterparty - nothing served, nothing recorded served")
    void aReferenceOfTheReadersOwnPartyIsRefused() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.AML);
        Actor reader = world.analyst();
        UUID own = EvaluationWorld.IDS.next();
        world.partyOfActor.put(reader.id(), own);
        SignalSubject ownIdentity = SignalSubject.of(SubjectKind.IDENTITY, EvaluationWorld.IDS.next());
        world.partyOfSubject.put(ownIdentity, own);
        UUID ownAccount = EvaluationWorld.IDS.next();
        partyOfAccount.put(ownAccount, own);

        RiskSignalId ownSignal = RiskSignalId.of(EvaluationWorld.IDS.next());
        UUID asSubject = EvaluationWorld.IDS.next();
        UUID asCounterparty = EvaluationWorld.IDS.next();
        attach(taken, ownSignal, asSubject);
        attachEntry(taken, asCounterparty);
        reads.signals.put(ownSignal, signal(ownSignal, ownIdentity));
        reads.transactions.put(asSubject, transaction(asSubject, ownAccount, thirdCounterparty));
        reads.transactions.put(asCounterparty, transaction(asCounterparty, thirdAccount, ownAccount));

        for (var reference : List.of(new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.SIGNAL, ownSignal.value()),
                new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.JOURNAL_ENTRY, asSubject),
                new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.JOURNAL_ENTRY, asCounterparty))) {
            assertThatThrownBy(() -> evidence.read(EvaluationWorld.UOW, taken.riskCase().id(), reference.kind(),
                    reference.id(), reader))
                    .as(reference.toString())
                    .isInstanceOfSatisfying(RiskAssessments.SelfDealingRefused.class, refused -> assertThat(
                            refused.refusal()).isEqualTo(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY));
        }
        assertThat(world.audited("risk.CaseRead")).isEmpty();
    }

    @Test
    @DisplayName("item 79: fail closed - a reference naming a party the platform cannot place (a signal's unplaced"
            + " subject, a journal entry's unplaced counterparty account, a loan the agreement join cannot place) is"
            + " refused SUBJECT_UNPLACEABLE; an operational counterparty names no party and does not refuse")
    void aReferenceThatCannotBePlacedIsRefused() {
        OverrideWorld.Taken taken = world.partyCase(CaseKind.AML);
        Actor reader = world.analyst();
        RiskSignalId unplaced = RiskSignalId.of(EvaluationWorld.IDS.next());
        UUID strangerCounterparty = EvaluationWorld.IDS.next();
        UUID loanEntry = EvaluationWorld.IDS.next();
        UUID operationalEntry = EvaluationWorld.IDS.next();
        attach(taken, unplaced, strangerCounterparty);
        attachEntry(taken, loanEntry);
        attachEntry(taken, operationalEntry);
        reads.signals.put(unplaced, signal(unplaced, SignalSubject.of(SubjectKind.IDENTITY,
                EvaluationWorld.IDS.next())));
        reads.transactions.put(strangerCounterparty, transaction(strangerCounterparty, thirdAccount,
                EvaluationWorld.IDS.next()));
        reads.transactions.put(loanEntry, transaction(loanEntry, thirdAccount, MonitoredCounterpartyKind.LOAN,
                EvaluationWorld.IDS.next()));
        reads.transactions.put(operationalEntry, transaction(operationalEntry, thirdAccount,
                MonitoredCounterpartyKind.OPERATIONAL, EvaluationWorld.IDS.next()));

        for (var reference : List.of(new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.SIGNAL, unplaced.value()),
                new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.JOURNAL_ENTRY, strangerCounterparty),
                new CaseWorklist.EvidenceRef(CaseWorklist.EvidenceKind.JOURNAL_ENTRY, loanEntry))) {
            assertThatThrownBy(() -> evidence.read(EvaluationWorld.UOW, taken.riskCase().id(), reference.kind(),
                    reference.id(), reader))
                    .as(reference.toString())
                    .isInstanceOfSatisfying(RiskAssessments.SelfDealingRefused.class, refused -> assertThat(
                            refused.refusal()).isEqualTo(
                                    RiskAssessments.SelfDealingRefused.Cause.SUBJECT_UNPLACEABLE));
        }
        assertThat(evidence.read(EvaluationWorld.UOW, taken.riskCase().id(), CaseWorklist.EvidenceKind.JOURNAL_ENTRY,
                operationalEntry, reader)).isInstanceOf(CaseEvidence.TransactionServed.class);
    }

    // ------------------------------------------------------------------

    private void attachEntry(OverrideWorld.Taken taken, UUID journalEntry) {
        RiskCase now = world.caseOf(taken.riskCase().id());
        world.cases.attachEvidence(EvaluationWorld.UOW, now.id(), now.revision(), new AlertEvidence(List.of(),
                List.of(), List.of(journalEntry)), "a further ledger line", taken.investigator(),
                OverrideWorld.correlation());
    }

    private void attach(OverrideWorld.Taken taken, RiskSignalId signal, UUID journalEntry) {
        RiskCase now = world.caseOf(taken.riskCase().id());
        world.cases.attachEvidence(EvaluationWorld.UOW, now.id(), now.revision(), new AlertEvidence(List.of(signal),
                List.of(), List.of(journalEntry)), "the device and the ledger line", taken.investigator(),
                OverrideWorld.correlation());
    }

    private static RiskSignal signal(RiskSignalId id, SignalSubject subject) {
        return new RiskSignal(id, SignalKind.CREDENTIAL_CHANGED, subject, new RiskSignal.SourceEvent(
                EventId.next(EvaluationWorld.IDS),
                "identity.CredentialChanged", 1, "identity", CorrelationId.of("flow"), CausationId.of("cause")),
                Instant.parse("2026-10-23T09:00:00Z"), SignalAttributes.none());
    }

    private static MonitoredTransaction transaction(UUID journalEntry, UUID subjectAccount, UUID counterpartyAccount) {
        return transaction(journalEntry, subjectAccount, MonitoredCounterpartyKind.CUSTOMER, counterpartyAccount);
    }

    private static MonitoredTransaction transaction(UUID journalEntry, UUID subjectAccount,
            MonitoredCounterpartyKind counterpartyKind, UUID counterpartyRef) {
        return new MonitoredTransaction(MonitoredTransactionId.next(EvaluationWorld.IDS), journalEntry,
                MonitoringWatermark.sequenceOf(journalEntry), MonitoredEntry.EntryType.POSTING, "transfer",
                MonitoredKind.TRANSFER, Optional.of(MonitoredSubjectKind.ACCOUNT), Optional.of(subjectAccount),
                Optional.of(MonitoredDirection.OUT), counterpartyKind, Optional.of(counterpartyRef),
                Optional.empty(), 250_00, 2, CurrencyCode.of("EUR"), LocalDate.of(2026, 10, 23),
                Instant.parse("2026-10-23T09:00:00Z"), Optional.empty());
    }
}
