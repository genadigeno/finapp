package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A defaulted BNPL obligation written off under four eyes (`P14-TSK-021`; owner decision C6 (A); ADR-0103 F11; {@code INV-BNPL-01},
 * {@code INV-AUD-04}, {@code INV-REV-04}, {@code INV-BAL-03}), hermetic: the real {@link BnplWriteOffs}, T-pur, T-svc, T-rep, T-ref,
 * T-rev and T-wvr and the real proofs over {@link BnplProofWorld}'s fakes - {@code bnpl V020} in memory. A write-off is an act on the
 * books, never forgiveness: every case that asserts the entry asserts the customer's read beside it. The races and the migration's
 * clauses are {@code app}'s {@code BnplWriteOffDatabaseTest}.
 */
@DisplayName("a BNPL obligation written off under four eyes (P14-TSK-021)")
class BnplWriteOffsTest {

    private static final LocalDate DEFAULTED_ON = BnplConditionWorld.DUE_2.plusDays(90);

    private final BnplProofWorld proofs = new BnplProofWorld();
    private final BnplCorrectionWorld corrections = proofs.corrections;
    private final BnplConditionWorld world = proofs.base;
    private final BnplApplicationFakes fakes = proofs.fakes;
    private final BnplWriteOffs writeOffs = new BnplWriteOffs(fakes.corrections, fakes.servicing, fakes.purchases, fakes.writeOffs,
            fakes.conditions, fakes.terms.store(), fakes.disputes, fakes.book, corrections.actingParties, fakes.outbox, fakes.audit,
            BnplConditionWorld.IDS, BnplConditionWorld.CLOCK);
    private final Actor proposer = corrections.alice;
    private final Actor approver = corrections.bob;

    // ------------------------------------------------------------------ the happy path, whole

    @Test
    @DisplayName("the whole of each receivable to expense, each to zero, balanced in one entry dated today; WRITTEN_OFF and never closed;"
            + " the proofs hold; the customer still reads what is owed")
    void aDefaultedObligationIsWrittenOffWhole() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        BnplWriteOffPosition read = proposed.writeOff().position();
        assertThat(read.total().isPositive()).isTrue();
        assertThat(fakes.book.lines).as("a proposal posts nothing").noneMatch(line -> line.toString().contains("write-off"));

        BnplWriteOffs.Decided decided = approve(agreement, proposed.writeOff().id(), read);

        assertThat(decided.replayed()).isFalse();
        assertThat(decided.writeOff().status()).isEqualTo(BnplCorrectionStatus.APPROVED);
        assertThat(decided.writeOff().valueDate()).contains(DEFAULTED_ON);
        BnplLedgerAccounts accounts = world.accounts(agreement);
        List<LedgerAccountId> assets = List.of(accounts.principal().id(), accounts.principalDue().id(), accounts.interestAccrued().id(),
                accounts.interestDue().id(), accounts.feesDue().id());
        List<JournalLine> entry = fakes.book.entryLines(BnplApplicationFakes.UOW, decided.writeOff().journalEntry().orElseThrow());
        assertThat(entry.get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(entry.get(0).account())
                .isEqualTo(fakes.book.operational(BnplApplicationFakes.UOW, AccountPurpose.BNPL_WRITE_OFF_EXPENSE, read.total().currency()));
        assertThat(entry.get(0).amount()).isEqualTo(read.total());
        assertThat(entry.subList(1, entry.size())).allSatisfy(line -> {
            assertThat(line.direction()).isEqualTo(Direction.CREDIT);
            assertThat(assets).contains(line.account());
        });
        accounts.all().forEach(account -> assertThat(fakes.book.balanceOf(BnplApplicationFakes.UOW, account).isZero())
                .as("%s to zero", account.purpose()).isTrue());

        BnplObligation after = world.obligation(agreement);
        assertThat(after.status()).isEqualTo(BnplObligationStatus.WRITTEN_OFF);
        assertThat(after.closureReason()).as("never closed: nothing was paid or forgiven").isEmpty();
        assertThat(fakes.events).extracting(EventEnvelope::eventType).contains(BnplWriteOffs.WRITTEN_OFF_EVENT);
        assertThat(fakes.payloads.get(fakes.payloads.size() - 1)).doesNotContain("amount", "Minor", "reason", "party");
        assertThat(fakes.audited).extracting(record -> record.operation().code())
                .contains("bnpl.WriteOffProposed", "bnpl.WriteOffApproved");

        assertThat(proofs.subledger(agreement).holds()).as("%s", proofs.subledger(agreement)).isTrue();
        assertThat(proofs.replay(agreement).verdict()).as("%s", proofs.replay(agreement)).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        BnplProofs.Reading reading = proofs.reading();
        assertThat(reading.currencies()).allSatisfy(verdict -> assertThat(verdict.identities()).allSatisfy(identity ->
                assertThat(identity.principalHolds()).as("G = repaid + cancelled + outstanding + written off").isTrue()));

        BnplObligations.Read customer = new BnplObligations(fakes.purchases, fakes.book, fakes.writeOffs)
                .read(BnplApplicationFakes.UOW, agreement.agreement(), world.party).orElseThrow();
        assertThat(customer.balances().principalDue()).as("what stands owed, never a read of zero").isEqualTo(read.principalDue());
        assertThat(customer.balances().principalOutstanding()).isEqualTo(read.principalTotal());
    }

    // ------------------------------------------------------------------ four eyes

    @Test
    @DisplayName("the proposer cannot approve: refused, nothing posted, the proposal standing - and the fake's CHECK holds beneath the domain")
    void theProposerCannotApprove() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        assertThatThrownBy(() -> corrections.on(DEFAULTED_ON, proposer, () -> writeOffs.approve(BnplApplicationFakes.UOW,
                agreement.agreement(), proposed.writeOff().id(), proposed.writeOff().position(), "mine", proposer,
                BnplConditionWorld.CORRELATION))).isInstanceOf(BnplWriteOffs.SelfApprovalRefused.class);
        assertThat(world.obligation(agreement).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        assertThat(fakes.writeOffs.rows.get(proposed.writeOff().id()).status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
    }

    @Test
    @DisplayName("an operator of the customer's own party is refused every act - before anything is written")
    void selfDealingIsRefused() {
        BnplConditionWorld.Agreement agreement = defaulted();
        assertThatThrownBy(() -> corrections.on(DEFAULTED_ON, corrections.insider, () -> writeOffs.propose(BnplApplicationFakes.UOW,
                agreement.agreement(), "mine", corrections.insider, BnplConditionWorld.CORRELATION)))
                .isInstanceOf(BnplRepaymentReversals.SelfDealingRefused.class);
        assertThat(fakes.writeOffs.rows).isEmpty();
    }

    @Test
    @DisplayName("a reason is required at every act, and a generated toString prints no reason and no figure")
    void aReasonIsRequiredAndNeverPrinted() {
        BnplConditionWorld.Agreement agreement = defaulted();
        assertThatThrownBy(() -> corrections.on(DEFAULTED_ON, proposer, () -> writeOffs.propose(BnplApplicationFakes.UOW,
                agreement.agreement(), " ", proposer, BnplConditionWorld.CORRELATION))).isInstanceOf(BnplReasons.ReasonRequired.class);
        BnplWriteOff row = propose(agreement).writeOff();
        assertThat(row.toString()).doesNotContain(row.proposalReason(), row.proposedBy());
        assertThat(row.position().toString()).doesNotContain(Long.toString(row.position().principalDue().minorUnits()));
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    @DisplayName("a non-defaulted obligation is refused at the proposal")
    void aNonDefaultedObligationIsRefused() {
        BnplConditionWorld.Agreement agreement = world.payIn4();
        world.serviceOn(BnplConditionWorld.DUE_2.plusDays(89), agreement);
        assertThatThrownBy(() -> corrections.on(BnplConditionWorld.DUE_2.plusDays(89), proposer, () -> writeOffs.propose(
                BnplApplicationFakes.UOW, agreement.agreement(), "uncollectable", proposer, BnplConditionWorld.CORRELATION)))
                .isInstanceOf(BnplWriteOffs.NotDefaulted.class);
    }

    @Test
    @DisplayName("a cure between the proposal and the approval is refused under the lock, before the entry, the proposal left for its door out")
    void aCureIsRefusedUnderTheLock() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        LocalDate next = DEFAULTED_ON.plusDays(1);
        // Every billed instalment paid: the condition derived now is no longer defaulted, whatever was last recorded.
        long due = fakes.purchases.billedItems(BnplApplicationFakes.UOW, agreement.obligation()).stream()
                .mapToLong(item -> item.dueTotal().minorUnits()).sum();
        world.repayOn(next, agreement, "cure", due);
        assertThatThrownBy(() -> approve(next, agreement, proposed.writeOff().id(), proposed.writeOff().position()))
                .isInstanceOfAny(BnplWriteOffs.NotDefaulted.class, BnplWriteOffs.NotInServicing.class);
        assertThat(fakes.book.lines).noneMatch(line -> line.toString().contains("write-off"));
    }

    @Test
    @DisplayName("anything moved since the proposal is stale, never silently re-read: nothing posted, the proposal standing, and rejecting it"
            + " and proposing again is the way on")
    void anyMoveIsStaleAndRejectionIsTheDoorOut() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        LocalDate next = DEFAULTED_ON.plusDays(1);
        world.repayOn(next, agreement, "part", 1_00);
        assertThatThrownBy(() -> approve(next, agreement, proposed.writeOff().id(), proposed.writeOff().position()))
                .isInstanceOf(BnplWriteOffs.Stale.class);
        assertThat(world.obligation(agreement).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        assertThat(fakes.writeOffs.rows.get(proposed.writeOff().id()).status()).isEqualTo(BnplCorrectionStatus.PROPOSED);

        assertThatThrownBy(() -> corrections.on(next, proposer, () -> writeOffs.propose(BnplApplicationFakes.UOW, agreement.agreement(),
                "again", proposer, BnplConditionWorld.CORRELATION))).as("one live proposal").isInstanceOf(BnplWriteOffs.ProposalPending.class);
        BnplWriteOffs.Decided rejected = corrections.on(next, approver, () -> writeOffs.reject(BnplApplicationFakes.UOW,
                agreement.agreement(), proposed.writeOff().id(), "it moved", approver, BnplConditionWorld.CORRELATION));
        assertThat(rejected.writeOff().status()).isEqualTo(BnplCorrectionStatus.REJECTED);

        BnplWriteOffs.Proposed again = corrections.on(next, proposer, () -> writeOffs.propose(BnplApplicationFakes.UOW,
                agreement.agreement(), "uncollectable", proposer, BnplConditionWorld.CORRELATION));
        assertThat(approve(next, agreement, again.writeOff().id(), again.writeOff().position()).writeOff().status())
                .isEqualTo(BnplCorrectionStatus.APPROVED);
        assertThat(proofs.subledger(agreement).holds()).isTrue();
    }

    @Test
    @DisplayName("a figure the approver names that is not the proposal's is stale: what they read is what they attest")
    void anotherFigureNamedIsStale() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        BnplWriteOffPosition read = proposed.writeOff().position();
        BnplWriteOffPosition lower = new BnplWriteOffPosition(read.principal(), read.principalDue().minus(eur(1)), read.interestAccrued(),
                read.interestDue(), read.feesDue(), read.revision());
        BnplWriteOffPosition otherRevision = new BnplWriteOffPosition(read.principal(), read.principalDue(), read.interestAccrued(),
                read.interestDue(), read.feesDue(), read.revision() + 1);
        assertThatThrownBy(() -> approve(agreement, proposed.writeOff().id(), lower)).isInstanceOf(BnplWriteOffs.Stale.class);
        assertThatThrownBy(() -> approve(agreement, proposed.writeOff().id(), otherRevision)).isInstanceOf(BnplWriteOffs.Stale.class);
    }

    @Test
    @DisplayName("cured and defaulted again between the two acts: the status reads the same and the figures and revision too, and it is stale"
            + " because the DEFAULT change is another - compared by what cannot round-trip")
    void aDefaultCuredAndSetAgainIsStale() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        BnplConditionEvent stood = fakes.conditions.latest(BnplApplicationFakes.UOW, agreement.obligation(), BnplConditionKind.DEFAULT)
                .orElseThrow();
        // The recorded history moves and no line does: a later DEFAULT change, set again.
        fakes.conditions.events.add(new BnplConditionEvent(UUID.randomUUID(), stood.obligation(), stood.businessDate().plusDays(1),
                BnplConditionKind.DEFAULT, stood.state(), Optional.empty(), stood.agreement(), stood.agreementVersion(), stood.correlation()));
        LocalDate later = stood.businessDate().plusDays(1);
        assertThatThrownBy(() -> approve(later, agreement, proposed.writeOff().id(), proposed.writeOff().position()))
                .isInstanceOf(BnplWriteOffs.Stale.class);
    }

    @Test
    @DisplayName("a credit balance is the customer's money and is never written off: refused until its servicing step applies it")
    void aCreditBalanceIsRefused() {
        BnplConditionWorld.Agreement agreement = defaulted();
        LedgerAccountId income = fakes.book.operational(BnplApplicationFakes.UOW, AccountPurpose.BNPL_FEE_INCOME,
                world.obligation(agreement).currency());
        fakes.book.post(BnplApplicationFakes.UOW, new PostingCommand("test:credit-held", DEFAULTED_ON, DEFAULTED_ON, "test", List.of(
                new JournalLine(income, Direction.DEBIT, eur(1_00)),
                new JournalLine(world.accounts(agreement).creditBalance().id(), Direction.CREDIT, eur(1_00)))));
        assertThatThrownBy(() -> corrections.on(DEFAULTED_ON, proposer, () -> writeOffs.propose(BnplApplicationFakes.UOW,
                agreement.agreement(), "uncollectable", proposer, BnplConditionWorld.CORRELATION)))
                .isInstanceOf(BnplWriteOffs.CreditHeld.class);
    }

    @Test
    @DisplayName("an open dispute comes first: its outcome may be a refund, which needs the receivable")
    void anOpenDisputeIsRefused() {
        BnplConditionWorld.Agreement agreement = defaulted();
        fakes.now = DEFAULTED_ON.atTime(11, 0).toInstant(java.time.ZoneOffset.UTC);
        fakes.inTransaction(() -> proofs.disputes.open(BnplApplicationFakes.UOW, agreement.agreement(), world.party,
                new BnplDisputes.OpenRequest("dispute-1", eur(10_00), BnplDisputeReason.values()[0], "it never came"), proposer,
                BnplConditionWorld.CORRELATION));
        assertThatThrownBy(() -> corrections.on(DEFAULTED_ON, proposer, () -> writeOffs.propose(BnplApplicationFakes.UOW,
                agreement.agreement(), "uncollectable", proposer, BnplConditionWorld.CORRELATION)))
                .isInstanceOf(BnplWriteOffs.DisputeOpen.class);
    }

    @Test
    @DisplayName("an elapsed date not accrued refuses a pay-in-N proposal: no interest earned is forgone, and ACCRUALS_COMPLETE sees no gap")
    void behindAccrualIsRefused() {
        BnplConditionWorld.Agreement agreement = world.payIn12();
        LocalDate due = world.instalment(agreement, 1).dueDate();
        proofs.stepOn(due.plusDays(90), agreement);
        assertThat(world.obligation(agreement).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        LocalDate later = due.plusDays(92);
        assertThatThrownBy(() -> corrections.on(later, proposer, () -> writeOffs.propose(BnplApplicationFakes.UOW, agreement.agreement(),
                "uncollectable", proposer, BnplConditionWorld.CORRELATION))).isInstanceOf(BnplWriteOffs.BehindAccrual.class);
    }

    // ------------------------------------------------------------------ one decision

    @Test
    @DisplayName("decided once: the same approver's retry converges, another approver and a rejection find it decided, and one entry stands")
    void decidedOnce() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        UUID id = proposed.writeOff().id();
        BnplWriteOffPosition read = proposed.writeOff().position();
        BnplWriteOffs.Decided first = approve(agreement, id, read);
        BnplWriteOffs.Decided again = approve(agreement, id, read);
        assertThat(again.replayed()).isTrue();
        assertThat(again.writeOff().journalEntry()).isEqualTo(first.writeOff().journalEntry());

        Actor third = new Actor(UUID.randomUUID().toString(), approver.type());
        corrections.operatorParties.put(third.id(), UUID.randomUUID());
        assertThatThrownBy(() -> corrections.on(DEFAULTED_ON, third, () -> writeOffs.approve(BnplApplicationFakes.UOW, agreement.agreement(),
                id, read, "me too", third, BnplConditionWorld.CORRELATION))).isInstanceOf(BnplWriteOffs.ProposalNotPending.class);
        assertThatThrownBy(() -> corrections.on(DEFAULTED_ON, third, () -> writeOffs.reject(BnplApplicationFakes.UOW, agreement.agreement(),
                id, "no", third, BnplConditionWorld.CORRELATION))).isInstanceOf(BnplWriteOffs.ProposalNotPending.class);
    }

    @Test
    @DisplayName("a locked month refuses the approval and moves it nowhere: the ledger's refusal passes through, the fake's rollback leaves the"
            + " proposal standing and the obligation ACTIVE")
    void aRefusedPostingLeavesNothing() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        // The key already taken stands for the ledger refusing the entry: the act rolls back whole.
        LedgerAccountId income = fakes.book.operational(BnplApplicationFakes.UOW, AccountPurpose.BNPL_FEE_INCOME,
                world.obligation(agreement).currency());
        String key = BnplWriteOffs.WRITE_OFF_KEY_PREFIX + agreement.obligation().value();
        fakes.book.post(BnplApplicationFakes.UOW, new PostingCommand(key, DEFAULTED_ON, DEFAULTED_ON, "test", List.of(
                new JournalLine(income, Direction.DEBIT, eur(1)), new JournalLine(income, Direction.CREDIT, eur(1)))));
        assertThatThrownBy(() -> approve(agreement, proposed.writeOff().id(), proposed.writeOff().position()))
                .isInstanceOf(RuntimeException.class);
        assertThat(fakes.writeOffs.rows.get(proposed.writeOff().id()).status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
        assertThat(world.obligation(agreement).status()).isEqualTo(BnplObligationStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ after it

    @Test
    @DisplayName("after it, every door that would move money or a correction refuses and nothing moves: repayment, payoff, merchant refund,"
            + " dispute, waiver and reversal - and servicing and accrual skip it")
    void everyDoorRefusesAWrittenOffObligation() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        UUID paid = fakes.purchases.repayments.values().stream().filter(row -> row.obligation().equals(agreement.obligation()))
                .findFirst().map(BnplPurchaseStore.Repayment::id).orElseThrow();
        approve(agreement, proposed.writeOff().id(), proposed.writeOff().position());
        LocalDate next = DEFAULTED_ON.plusDays(1);
        int lines = fakes.book.lines.size();

        assertThatThrownBy(() -> world.repayOn(next, agreement, "late money", 1_00)).isInstanceOf(BnplWrittenOffRefused.class);
        assertThatThrownBy(() -> proofs.quoteOn(next, agreement, "quote")).isInstanceOf(BnplWrittenOffRefused.class);
        assertThatThrownBy(() -> world.refundOn(next, agreement, "refund", 10_00)).isInstanceOf(BnplWrittenOffRefused.class);
        assertThatThrownBy(() -> fakes.inTransaction(() -> proofs.disputes.open(BnplApplicationFakes.UOW, agreement.agreement(), world.party,
                new BnplDisputes.OpenRequest("dispute-2", eur(10_00), BnplDisputeReason.values()[0], "it never came"), proposer,
                BnplConditionWorld.CORRELATION))).isInstanceOf(BnplWrittenOffRefused.class);
        assertThatThrownBy(() -> corrections.propose(next, agreement, paid, proposer)).isInstanceOf(BnplWrittenOffRefused.class);
        assertThat(world.serviceOn(next, agreement).kind().name()).isEqualTo("NOT_SERVICED");
        assertThat(fakes.book.lines).as("nothing moved").hasSize(lines);
        assertThat(world.obligation(agreement).status()).isEqualTo(BnplObligationStatus.WRITTEN_OFF);
        assertThat(proofs.subledger(agreement).holds()).isTrue();
    }

    // ------------------------------------------------------------------ the proofs can fail

    @Test
    @DisplayName("a write-off no row explains fails the subledger proof, the replay and the three-way identity: the month could not close on it")
    void anUnexplainedWriteOffFailsTheProofs() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        approve(agreement, proposed.writeOff().id(), proposed.writeOff().position());
        BnplWriteOff row = fakes.writeOffs.rows.remove(proposed.writeOff().id());

        BnplSubledgerProof.Verdict verdict = proofs.subledger(agreement);
        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.failures()).contains("UNEXPLAINED_ENTRIES");
        assertThat(proofs.replay(agreement).checks()).contains(BnplReplayProof.Check.WRITE_OFF);
        assertThat(proofs.reading().currencies()).anySatisfy(found -> assertThat(found.identities())
                .anySatisfy(identity -> assertThat(identity.principalHolds()).isFalse()));
        fakes.writeOffs.rows.put(row.id(), row);
    }

    @Test
    @DisplayName("a partial write-off diverges: the row took less than the receivable held, or more than the entry credited")
    void aPartialWriteOffDiverges() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        approve(agreement, proposed.writeOff().id(), proposed.writeOff().position());
        BnplWriteOff row = fakes.writeOffs.rows.get(proposed.writeOff().id());
        BnplWriteOffPosition read = row.position();
        BnplWriteOffPosition less = new BnplWriteOffPosition(read.principal(), read.principalDue().minus(eur(1)), read.interestAccrued(),
                read.interestDue(), read.feesDue(), read.revision());
        fakes.writeOffs.rows.put(row.id(), new BnplWriteOff(row.id(), row.obligation(), row.agreement(), less, row.defaultEvent(),
                row.agreementVersion(), row.status(), row.proposedBy(), row.proposedAt(), row.proposalReason(), row.decidedBy(),
                row.decidedAt(), row.decisionReason(), row.journalEntry(), row.valueDate()));
        assertThat(proofs.replay(agreement).checks()).contains(BnplReplayProof.Check.WRITE_OFF);
        assertThat(proofs.subledger(agreement).holds()).isFalse();
    }

    // ------------------------------------------------------------------ plumbing

    @Test
    @DisplayName("the customer's read after it names what stands owed, never zero: the ledger's accounts are empty, the figures are the row's")
    void theCustomersReadNamesWhatStandsOwed() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        BnplWriteOffPosition position = proposed.writeOff().position();
        approve(agreement, proposed.writeOff().id(), position);
        BnplObligations.Read read = new BnplObligations(fakes.purchases, fakes.book, fakes.writeOffs)
                .read(BnplApplicationFakes.UOW, agreement.agreement(), world.party).orElseThrow();
        assertThat(read.balances().principalOutstanding()).isEqualTo(position.principalTotal());
        assertThat(read.balances().principalOutstanding().isPositive()).isTrue();
        assertThat(read.balances().feesDue()).isEqualTo(position.feesDue());
        assertThat(read.owned().obligation().status()).isEqualTo(BnplObligationStatus.WRITTEN_OFF);
    }

    @Test
    @DisplayName("what is proposed under one agreement version is not approved under another, whatever else stands - the proposal is stale")
    void anotherAgreementVersionIsStale() {
        BnplConditionWorld.Agreement agreement = defaulted();
        BnplWriteOffs.Proposed proposed = propose(agreement);
        BnplAgreement current = fakes.purchases.agreements.get(agreement.agreement().value());
        fakes.purchases.agreements.put(agreement.agreement().value(), new BnplAgreement(current.id(), current.version() + 1,
                current.obligation(), current.offer(), current.decision(), current.termsVersionId(), current.product(), current.plan(),
                current.scheduleEngine(), current.accrualEngine(), current.allocationEngine(), current.refundAllocationEngine(),
                current.feeScheduleVersion(), current.template(), current.canonicalFormat(), current.canonicalTerms(),
                current.termsSha256(), current.documentSha256(), current.createdAt()));
        BnplWriteOffs.Attestation moved = corrections.on(DEFAULTED_ON, approver, () -> writeOffs.read(BnplApplicationFakes.UOW,
                agreement.agreement(), proposed.writeOff().id(), approver, BnplConditionWorld.CORRELATION));
        assertThat(moved.blockers()).containsExactly(BnplWriteOffs.Blocker.MOVED);
        assertThat(moved.agreementVersionNow()).isEqualTo(current.version() + 1);
        assertThatThrownBy(() -> approve(agreement, proposed.writeOff().id(), proposed.writeOff().position()))
                .isInstanceOf(BnplWriteOffs.Stale.class);
    }

    @Test
    @DisplayName("on the day an upheld dispute lifts, the clock restarts and nothing is past due: the condition the recorder would derive is not"
            + " defaulted, so a proposal that day is refused, whatever the earlier DEFAULT change says")
    void anUpheldDisputesLiftIsNotADefault() {
        BnplConditionWorld.Agreement agreement = defaulted();
        fakes.now = DEFAULTED_ON.atTime(11, 0).toInstant(java.time.ZoneOffset.UTC);
        BnplDispute dispute = fakes.inTransaction(() -> proofs.disputes.open(BnplApplicationFakes.UOW, agreement.agreement(), world.party,
                new BnplDisputes.OpenRequest("dispute-1", eur(BnplConditionWorld.GROSS * 3 / 4), BnplDisputeReason.NOT_RECEIVED, "it never came"),
                world.customer, BnplConditionWorld.CORRELATION)).dispute();
        fakes.now = DEFAULTED_ON.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC);
        fakes.inTransaction(() -> proofs.disputes.respond(BnplApplicationFakes.UOW, dispute.id(), world.merchant,
                new BnplDisputes.Response(BnplDisputes.Decision.CONTEST, "we shipped it", "merchant-key-1", BnplKeyIssuer.KEY_ISSUER),
                world.merchantActor, BnplConditionWorld.CORRELATION));
        LocalDate lifted = DEFAULTED_ON.plusDays(1);
        fakes.now = lifted.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC);
        fakes.inTransaction(() -> proofs.disputes.resolve(BnplApplicationFakes.UOW, dispute.id(), BnplDisputeStatus.RESOLVED_UPHELD,
                "on the evidence", approver, BnplConditionWorld.CORRELATION));
        assertThatThrownBy(() -> corrections.on(lifted, proposer, () -> writeOffs.propose(BnplApplicationFakes.UOW, agreement.agreement(),
                "uncollectable", proposer, BnplConditionWorld.CORRELATION))).isInstanceOf(BnplWriteOffs.NotDefaulted.class);
    }

    @Test
    @DisplayName("the pending list never serves the officer's own party's agreement, and an officer the platform cannot place reads none and is"
            + " recorded FAILED")
    void thePendingListJudgesTheReader() {
        BnplConditionWorld.Agreement agreement = defaulted();
        propose(agreement);
        assertThat(writeOffs.pending(BnplApplicationFakes.UOW, approver, BnplConditionWorld.CORRELATION)).hasSize(1);
        assertThat(writeOffs.pending(BnplApplicationFakes.UOW, corrections.insider, BnplConditionWorld.CORRELATION))
                .as("the officer's own party's").isEmpty();
        Actor nobody = new Actor("operator-unplaced", com.finapp.platform.security.ActorType.EMPLOYEE);
        BnplWriteOffs.UnplacedReaderRefused refused = org.junit.jupiter.api.Assertions.assertThrows(
                BnplWriteOffs.UnplacedReaderRefused.class, () -> writeOffs.pending(BnplApplicationFakes.UOW, nobody, BnplConditionWorld.CORRELATION));
        writeOffs.recordRefusal(BnplApplicationFakes.UOW, refused, nobody, BnplConditionWorld.CORRELATION);
        assertThat(fakes.audited.get(fakes.audited.size() - 1).outcome()).isEqualTo(com.finapp.platform.audit.AuditOutcome.FAILED);
    }

    @Test
    @DisplayName("what a customer reads beside the status says it stands owed, in words that cannot be read as paid or forgiven")
    void theNoticeSaysItStandsOwed() {
        assertThat(BnplObligations.WRITTEN_OFF_NOTICE).contains("written off in our accounts").contains("bookkeeping entry")
                .contains("has not been paid or forgiven").contains("still owed");
    }

    @Test
    @DisplayName("every correction left standing from before it, and the payoff quoted before it, is refused after it: a reversal's and a"
            + " waiver's approval, a new waiver, the payoff's execution - nothing posted")
    void whatStoodBeforeItIsRefusedAfterIt() {
        BnplConditionWorld.Agreement agreement = defaulted();
        UUID paid = fakes.purchases.repayments.values().stream().filter(row -> row.obligation().equals(agreement.obligation()))
                .findFirst().map(BnplPurchaseStore.Repayment::id).orElseThrow();
        BnplRepaymentReversal reversal = corrections.propose(DEFAULTED_ON, agreement, paid, proposer);
        BnplFeeAssessment fee = corrections.fees(agreement).get(0);
        BnplWaiver waiver = corrections.proposeWaiver(DEFAULTED_ON, agreement, fee.id(), 1_00, proposer);
        BnplPayoffs.Quoted quoted = proofs.quoteOn(DEFAULTED_ON, agreement, "quote");
        BnplWriteOffs.Proposed proposed = propose(agreement);
        approve(agreement, proposed.writeOff().id(), proposed.writeOff().position());
        int lines = fakes.book.lines.size();
        LocalDate same = DEFAULTED_ON;

        assertThatThrownBy(() -> corrections.approve(same, agreement, paid, reversal.id(), approver)).isInstanceOf(BnplWrittenOffRefused.class);
        assertThatThrownBy(() -> corrections.approveWaiver(same, agreement, waiver.id(), approver)).isInstanceOf(BnplWrittenOffRefused.class);
        assertThatThrownBy(() -> corrections.proposeWaiver(same, agreement, fee.id(), 1_00, proposer)).isInstanceOf(BnplWrittenOffRefused.class);
        fakes.now = same.atTime(10, 30).toInstant(java.time.ZoneOffset.UTC);
        assertThatThrownBy(() -> fakes.inTransaction(() -> proofs.payoffs.execute(BnplApplicationFakes.UOW, agreement.agreement(), world.party,
                "execute", quoted.quote().id(), world.customer, BnplConditionWorld.CORRELATION))).isInstanceOf(BnplWrittenOffRefused.class);
        assertThat(fakes.book.lines).as("nothing moved").hasSize(lines);
    }

    private BnplConditionWorld.Agreement defaulted() {
        BnplConditionWorld.Agreement agreement = world.payIn4();
        world.serviceOn(DEFAULTED_ON, agreement);
        assertThat(fakes.conditions.latest(BnplApplicationFakes.UOW, agreement.obligation(), BnplConditionKind.DEFAULT))
                .as("defaulted at 90 days past due").isPresent();
        return agreement;
    }

    private BnplWriteOffs.Proposed propose(BnplConditionWorld.Agreement agreement) {
        return corrections.on(DEFAULTED_ON, proposer, () -> writeOffs.propose(BnplApplicationFakes.UOW, agreement.agreement(),
                "uncollectable after collections", proposer, BnplConditionWorld.CORRELATION));
    }

    private BnplWriteOffs.Decided approve(BnplConditionWorld.Agreement agreement, UUID id, BnplWriteOffPosition attested) {
        return approve(DEFAULTED_ON, agreement, id, attested);
    }

    private BnplWriteOffs.Decided approve(LocalDate on, BnplConditionWorld.Agreement agreement, UUID id, BnplWriteOffPosition attested) {
        return corrections.on(on, approver, () -> writeOffs.approve(BnplApplicationFakes.UOW, agreement.agreement(), id, attested,
                "read and agreed", approver, BnplConditionWorld.CORRELATION));
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, com.finapp.sharedkernel.money.CurrencyCode.of("EUR"));
    }
}
