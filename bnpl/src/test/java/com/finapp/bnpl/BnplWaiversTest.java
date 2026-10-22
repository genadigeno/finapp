package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.DUE_3;
import static com.finapp.bnpl.BnplConditionWorld.DUE_4;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-wvr, hermetic (`P12-TSK-024`; {@code PHASE_12_PLAN.md} sections 7.1, 7.2 and 12.11 F7; lending's {@code Waivers}
 * mirrored for its fee kind; {@code INV-REV-02}, {@code INV-REV-04}, {@code INV-AUD-04}; the Phase 11 gate's R3): a late
 * fee is forgone only by a four-eyes, reasoned waiver that posts F7 - the ledger's reversal of the fee's F4 entry for the
 * amount - bounded by what may still be forgiven, so a fee paid, released by a refund or already waived is never forgiven
 * again; a waiver of the last receivable closes the obligation; the shared income row never before the wallet. Over
 * {@link BnplCorrectionWorld}; the races and the database's ranks are {@code BnplWaiverDatabaseTest}'s.
 */
@DisplayName("fee waivers under four eyes (P12-TSK-024)")
class BnplWaiversTest {

    private BnplCorrectionWorld world;
    private BnplConditionWorld base;
    private BnplApplicationFakes fakes;

    @BeforeEach
    void setUp() {
        world = new BnplCorrectionWorld();
        base = world.base;
        fakes = world.fakes;
    }

    /** Pay-in-4 with instalment 2 billed and its EUR 5.00 late fee assessed on its fifth day. */
    private BnplConditionWorld.Agreement aLateFee() {
        BnplConditionWorld.Agreement p4 = base.payIn4();
        base.serviceOn(DUE_2, p4);
        assertThat(base.serviceOn(DUE_2.plusDays(5), p4).fees()).isEqualTo(1);
        return p4;
    }

    private BnplFeeAssessment fee(BnplConditionWorld.Agreement agreement, int sequence) {
        UUID instalment = base.instalment(agreement, sequence).id();
        return world.fees(agreement).stream().filter(row -> row.instalment().equals(instalment)).findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------ F7 and its bound

    @Test
    @DisplayName("a fee waiver reverses only the unpaid remainder: 3.00 of the 5.00 paid, 5.00 is WaiverExceedsDue, 2.00 is"
            + " posted - F7 DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE, the reversal of the fee's F4 entry - the fee's FEES"
            + " billed 3.00 and settled, the event and the audit records")
    void aFeeWaiverReversesOnlyTheUnpaidRemainder() {
        BnplConditionWorld.Agreement p4 = aLateFee();
        BnplFeeAssessment late = fee(p4, 2);
        base.repayOn(DUE_2.plusDays(5), p4, "part", 3_00);
        assertThat(world.item(p4, 2).due(AllocationComponent.FEES)).isEqualTo(eur(2_00));
        assertThatThrownBy(() -> world.proposeWaiver(DUE_2.plusDays(6), p4, late.id(), 5_00, world.alice))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        BnplWaiver proposed = world.proposeWaiver(DUE_2.plusDays(6), p4, late.id(), 2_00, world.alice);
        assertThat(proposed.status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
        assertThat(fakes.book.linesOf("ledger.reverse:" + BnplWaivers.WAIVER_KEY_PREFIX)).isEmpty();
        fakes.events.clear();
        fakes.payloads.clear();
        fakes.audited.clear();
        BnplWaivers.Decided decided = world.approveWaiver(DUE_2.plusDays(6), p4, proposed.id(), world.bob);
        assertThat(decided.waiver().status()).isEqualTo(BnplCorrectionStatus.APPROVED);
        assertThat(decided.closure()).isEmpty();
        assertThat(base.labelled(fakes.book.linesOf("ledger.reverse:" + BnplWaivers.WAIVER_KEY_PREFIX + proposed.id())))
                .containsExactly("BNPL_FEE_INCOME DEBIT 200", "BNPL_FEES_DUE CREDIT 200");
        assertThat(fakes.book.reversalOf).containsEntry(decided.waiver().entry().orElseThrow(), late.entry());
        assertThat(world.item(p4, 2).billedFees()).isEqualTo(eur(3_00));
        assertThat(world.item(p4, 2).due(AllocationComponent.FEES)).isEqualTo(eur(0));
        assertThat(base.balance(base.accounts(p4).feesDue())).isEqualTo(eur(0));
        assertThat(world.feeIncome()).isEqualTo(eur(3_00));
        assertThat(fakes.events).extracting(EventEnvelope::eventType).containsExactly(BnplWaivers.FEE_WAIVED_EVENT);
        assertThat(fakes.payloads.get(0)).contains("\"amountMinor\":\"200\"").contains(late.id().toString())
                .doesNotContain(base.party.toString()).doesNotContain("complaint");
        assertThat(fakes.audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.WAIVER_APPROVED);
            assertThat(record.reason()).contains("complaint upheld");
            // An amount never - matched as a whole token, so a UUIDv7's hex digits never trip it.
            assertThat(record.changeSummary().orElseThrow()).doesNotContainPattern("\\b(200|2\\.00)\\b");
        });
        // The rows explain the ledger after it.
        new BnplReceivables(fakes.purchases, fakes.book).dueState(BnplApplicationFakes.UOW, p4.obligation(),
                base.accounts(p4));
    }

    @Test
    @DisplayName("R3: a fee a refund released, or one already waived, is never waived again - WaiverExceedsDue for a cent,"
            + " nothing posted, and BNPL_FEE_INCOME never below zero for the obligation")
    void aReleasedOrRefundedFeeIsNeverWaivedAgain() {
        BnplConditionWorld.Agreement p4 = base.payIn4();
        base.serviceOn(DUE_2, p4);
        base.serviceOn(DUE_3, p4);
        base.serviceOn(DUE_3.plusDays(5), p4);
        assertThat(world.fees(p4)).hasSize(2);
        // A refund of 350.00, latest first: instalment 4 (unbilled), instalment 3 whole - its fee released (OQ-14) - and
        // 50.00 of instalment 2, whose fee stays due.
        BnplFeeAssessment released = fee(p4, 3);
        BnplFeeAssessment standing = fee(p4, 2);
        base.refundOn(DUE_3.plusDays(5), p4, "ref", 350_00);
        assertThat(fakes.purchases.billedItems(BnplApplicationFakes.UOW, p4.obligation()).stream()
                .filter(item -> item.instalment().equals(released.instalment())).findFirst().orElseThrow()
                .due(AllocationComponent.FEES)).isEqualTo(eur(0));
        assertThat(base.obligation(p4).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        assertThat(world.feeIncome()).isEqualTo(eur(5_00));
        assertThatThrownBy(() -> world.proposeWaiver(DUE_3.plusDays(6), p4, released.id(), 1, world.alice))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        // The fee still due is waived once, whole - and then never again.
        BnplWaiver whole = world.proposeWaiver(DUE_3.plusDays(6), p4, standing.id(), 5_00, world.alice);
        world.approveWaiver(DUE_3.plusDays(6), p4, whole.id(), world.bob);
        assertThatThrownBy(() -> world.proposeWaiver(DUE_3.plusDays(6), p4, standing.id(), 1, world.alice))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        assertThat(world.feeIncome()).isEqualTo(eur(0));
        assertThat(fakes.book.keys).filteredOn(key -> key.contains(BnplWaivers.WAIVER_KEY_PREFIX)).hasSize(1);
    }

    @Test
    @DisplayName("R3 through a reversal: 2.00 waived, the 3.00 left paid and its payment reversed - the fee is due again,"
            + " forgivable to exactly the 3.00 never forgiven; 3.01 refused, 3.00 posted, then nothing more")
    void aReopenedFeeIsForgivableOnlyToWhatWasNeverForgiven() {
        BnplConditionWorld.Agreement p4 = aLateFee();
        BnplFeeAssessment late = fee(p4, 2);
        BnplWaiver first = world.proposeWaiver(DUE_2.plusDays(5), p4, late.id(), 2_00, world.alice);
        world.approveWaiver(DUE_2.plusDays(5), p4, first.id(), world.bob);
        BnplPurchaseStore.Repayment paid = base.repayOn(DUE_2.plusDays(6), p4, "fee", 3_00).repayment();
        assertThat(world.item(p4, 2).due(AllocationComponent.FEES)).isEqualTo(eur(0));
        BnplRepaymentReversal reversal = world.propose(DUE_2.plusDays(7), p4, paid.id(), world.alice);
        world.approve(DUE_2.plusDays(7), p4, paid.id(), reversal.id(), world.bob);
        assertThat(world.item(p4, 2).due(AllocationComponent.FEES)).isEqualTo(eur(3_00));
        assertThatThrownBy(() -> world.proposeWaiver(DUE_2.plusDays(7), p4, late.id(), 3_01, world.alice))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        BnplWaiver second = world.proposeWaiver(DUE_2.plusDays(7), p4, late.id(), 3_00, world.alice);
        world.approveWaiver(DUE_2.plusDays(7), p4, second.id(), world.bob);
        assertThatThrownBy(() -> world.proposeWaiver(DUE_2.plusDays(7), p4, late.id(), 1, world.alice))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        assertThat(world.feeIncome()).isEqualTo(eur(0));
        assertThat(world.item(p4, 2).billedFees()).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("a waiver racing a repayment of the fee: the repayment commits first, the approval re-judges the bound"
            + " under B4 - WaiverExceedsDue, nothing posted, the proposal still PROPOSED (and may be rejected)")
    void waiverRacingARepaymentOfTheFee() {
        BnplConditionWorld.Agreement p4 = aLateFee();
        BnplFeeAssessment late = fee(p4, 2);
        BnplWaiver proposed = world.proposeWaiver(DUE_2.plusDays(5), p4, late.id(), 5_00, world.alice);
        base.repayOn(DUE_2.plusDays(6), p4, "pays the fee", 5_00);
        assertThatThrownBy(() -> world.approveWaiver(DUE_2.plusDays(6), p4, proposed.id(), world.bob))
                .isInstanceOf(BnplWaivers.WaiverExceedsDue.class);
        assertThat(fakes.book.keys).noneMatch(key -> key.contains(BnplWaivers.WAIVER_KEY_PREFIX));
        assertThat(fakes.corrections.waiverRows.get(proposed.id()).status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
        assertThat(world.rejectWaiver(DUE_2.plusDays(6), p4, proposed.id(), world.bob).waiver().status())
                .isEqualTo(BnplCorrectionStatus.REJECTED);
    }

    @Test
    @DisplayName("a waiver of the last receivable closes the obligation REPAID in its own transaction")
    void aWaiverOfTheLastReceivableCloses() {
        base.activate(BnplTermsFixtures.payIn4().lateFee(eur(5_00)).allocationOrder(List.of(
                AllocationComponent.PRINCIPAL, AllocationComponent.INTEREST, AllocationComponent.FEES)).build());
        BnplConditionWorld.Agreement p4 = base.payIn4();
        base.serviceOn(DUE_2, p4);
        base.repayOn(DUE_2, p4, "i2", 150_00);
        base.serviceOn(DUE_3, p4);
        base.repayOn(DUE_3, p4, "i3", 150_00);
        base.serviceOn(DUE_4, p4);
        base.serviceOn(DUE_4.plusDays(5), p4);
        base.repayOn(DUE_4.plusDays(5), p4, "i4", 150_00);
        BnplFeeAssessment last = fee(p4, 4);
        assertThat(world.item(p4, 4).due(AllocationComponent.FEES)).isEqualTo(eur(5_00));
        assertThat(base.obligation(p4).status()).isEqualTo(BnplObligationStatus.ACTIVE);
        BnplWaiver proposed = world.proposeWaiver(DUE_4.plusDays(6), p4, last.id(), 5_00, world.alice);
        BnplWaivers.Decided decided = world.approveWaiver(DUE_4.plusDays(6), p4, proposed.id(), world.bob);
        assertThat(decided.closure()).hasValueSatisfying(closure -> assertThat(closure.reason())
                .isEqualTo(BnplClosureReason.REPAID));
        assertThat(base.obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertThatThrownBy(() -> world.proposeWaiver(DUE_4.plusDays(7), p4, last.id(), 1, world.alice))
                .isInstanceOf(BnplWaivers.ObligationClosed.class);
    }

    @Test
    @DisplayName("the waiver takes the income row last: B4, then - beside a credit balance its closure would refund - the"
            + " wallet (P), and only then one L8 call holding BNPL_FEE_INCOME; no L0, no L6")
    void theWaiverTakesTheIncomeRowLast() {
        BnplConditionWorld.Agreement p4 = aLateFee();
        BnplFeeAssessment late = fee(p4, 2);
        // A credit balance beside the due fee: the fee and the instalment paid, money held, then the payment reversed.
        BnplPurchaseStore.Repayment paid = base.repayOn(DUE_2.plusDays(5), p4, "all", 155_00).repayment();
        base.repayOn(DUE_2.plusDays(5), p4, "more", 20_00);
        BnplRepaymentReversal reversal = world.propose(DUE_2.plusDays(6), p4, paid.id(), world.alice);
        world.approve(DUE_2.plusDays(6), p4, paid.id(), reversal.id(), world.bob);
        assertThat(base.balance(base.accounts(p4).creditBalance())).isEqualTo(eur(20_00));
        BnplWaiver proposed = world.proposeWaiver(DUE_2.plusDays(6), p4, late.id(), 5_00, world.alice);
        fakes.log.clear();
        fakes.book.projections.clear();
        world.approveWaiver(DUE_2.plusDays(6), p4, proposed.id(), world.bob);
        List<String> order = new ArrayList<>();
        for (String entry : fakes.log) {
            if (List.of("L0", "B4", "L6", "lock wallet", "L8").contains(entry) || entry.startsWith("reverse ")) {
                order.add(entry);
            }
        }
        assertThat(order).containsExactly("B4", "lock wallet", "L8", "reverse bnpl.waiver:");
        assertThat(fakes.book.projections).singleElement().satisfies(rows -> assertThat(rows)
                .contains(base.feeIncome(), base.wallet, base.accounts(p4).feesDue().id()));
    }

    @Test
    @DisplayName("without a credit balance the waiver takes no wallet at all")
    void noCreditNoWallet() {
        BnplConditionWorld.Agreement p4 = aLateFee();
        BnplWaiver proposed = world.proposeWaiver(DUE_2.plusDays(5), p4, fee(p4, 2).id(), 5_00, world.alice);
        fakes.log.clear();
        world.approveWaiver(DUE_2.plusDays(5), p4, proposed.id(), world.bob);
        assertThat(fakes.log).doesNotContain("lock wallet", "L0", "L6");
    }

    // ------------------------------------------------------------------ four eyes and the refusals

    @Test
    @DisplayName("ten approvers, one waiver: one decision, one F7 entry; the winner's retry converges")
    void tenApproversOneWaiver() {
        BnplConditionWorld.Agreement p4 = aLateFee();
        BnplWaiver proposed = world.proposeWaiver(DUE_2.plusDays(5), p4, fee(p4, 2).id(), 5_00, world.alice);
        int approved = 0;
        int lost = 0;
        Actor winner = null;
        for (int approver = 0; approver < 10; approver++) {
            Actor other = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
            world.operatorParties.put(other.id(), UUID.randomUUID());
            try {
                world.approveWaiver(DUE_2.plusDays(5), p4, proposed.id(), other);
                approved++;
                winner = other;
            } catch (BnplWaivers.ProposalNotPending decided) {
                lost++;
            }
        }
        assertThat(approved).isEqualTo(1);
        assertThat(lost).isEqualTo(9);
        assertThat(world.approveWaiver(DUE_2.plusDays(5), p4, proposed.id(), winner).replayed()).isTrue();
        assertThat(fakes.book.keys).filteredOn(key -> key.contains(BnplWaivers.WAIVER_KEY_PREFIX)).hasSize(1);
    }

    @Test
    @DisplayName("self-approval refused at both ranks and recorded FAILED; one undecided waiver per fee (ProposalPending)")
    void selfApprovalRefusedAndOnePendingPerFee() {
        BnplConditionWorld.Agreement p4 = aLateFee();
        BnplFeeAssessment late = fee(p4, 2);
        BnplWaiver proposed = world.proposeWaiver(DUE_2.plusDays(5), p4, late.id(), 2_00, world.alice);
        assertThatThrownBy(() -> world.proposeWaiver(DUE_2.plusDays(5), p4, late.id(), 1_00, world.bob))
                .isInstanceOf(BnplWaivers.ProposalPending.class);
        BnplWaivers.SelfApprovalRefused refused = org.junit.jupiter.api.Assertions.assertThrows(
                BnplWaivers.SelfApprovalRefused.class,
                () -> world.approveWaiver(DUE_2.plusDays(5), p4, proposed.id(), world.alice));
        assertThat(fakes.book.keys).noneMatch(key -> key.contains(BnplWaivers.WAIVER_KEY_PREFIX));
        fakes.audited.clear();
        world.on(DUE_2.plusDays(5), world.alice, () -> {
            world.waivers.recordRefusal(BnplApplicationFakes.UOW, refused, world.alice, BnplConditionWorld.CORRELATION);
            return null;
        });
        assertThat(fakes.audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.SELF_APPROVAL_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary().orElseThrow()).contains("act=waiver:approve");
        });
        assertThatThrownBy(() -> fakes.corrections.decideWaiver(BnplApplicationFakes.UOW,
                new BnplCorrectionStore.WaiverDecision(BnplConditionWorld.IDS.next(), proposed.id(), world.alice.id(),
                        BnplCorrectionStatus.APPROVED, world.alice, "mine",
                        java.util.Optional.of(com.finapp.ledger.JournalEntryId.of(BnplConditionWorld.IDS.next())),
                        BnplConditionWorld.CORRELATION)))
                .isInstanceOf(BnplStorageException.class).hasMessageContaining("four_eyes");
        // The proposer withdraws it; then another proposal of the fee may be born.
        world.rejectWaiver(DUE_2.plusDays(5), p4, proposed.id(), world.alice);
        assertThat(world.proposeWaiver(DUE_2.plusDays(5), p4, late.id(), 1_00, world.bob).status())
                .isEqualTo(BnplCorrectionStatus.PROPOSED);
    }

    @Test
    @DisplayName("an operator of the customer's party never waives; another agreement's fee is NotFound; an amount in the"
            + " wrong scale or currency, or of nothing, is AmountInvalid; a closed obligation waives nothing")
    void theRefusals() {
        BnplConditionWorld.Agreement p4 = aLateFee();
        BnplFeeAssessment late = fee(p4, 2);
        assertThatThrownBy(() -> world.proposeWaiver(DUE_2.plusDays(5), p4, late.id(), 1_00, world.insider))
                .isInstanceOf(BnplRepaymentReversals.SelfDealingRefused.class);
        BnplConditionWorld.Agreement other = base.payIn4();
        assertThatThrownBy(() -> world.proposeWaiver(DUE_2.plusDays(5), other, late.id(), 1_00, world.alice))
                .isInstanceOf(BnplRepaymentReversals.NotFound.class);
        assertThatThrownBy(() -> world.on(DUE_2.plusDays(5), world.alice, () -> world.waivers.propose(
                BnplApplicationFakes.UOW, p4.agreement(), late.id(),
                com.finapp.sharedkernel.money.Money.ofPersisted(100, BnplTermsFixtures.EUR, 3), "r", world.alice,
                BnplConditionWorld.CORRELATION))).isInstanceOf(BnplWaivers.AmountInvalid.class);
        assertThatThrownBy(() -> world.proposeWaiver(DUE_2.plusDays(5), p4, late.id(), 0, world.alice))
                .isInstanceOf(BnplWaivers.AmountInvalid.class);
        assertThat(fakes.corrections.waiverRows).isEmpty();
    }

    @Test
    @DisplayName("a waiver needs a reason: a proposal, an approval and a rejection with none, a blank one, one past 1,000"
            + " characters or one holding a card number are ReasonRequired - nothing written, nothing posted, the"
            + " proposal still PROPOSED")
    void aWaiverNeedsAReason() {
        // The Phase 12 -> 13 transition gate, A1-5: BnplWaivers' three doors screen the reason first
        // (BnplReasons.reasoned); bnpl V016's CHECKs are BnplWaiverDatabaseTest#aWaiverNeedsAReasonForEveryWriter.
        BnplConditionWorld.Agreement p4 = aLateFee();
        BnplFeeAssessment late = fee(p4, 2);
        List<String> unreasoned = java.util.Arrays.asList(null, "", "   ", "r".repeat(BnplReasons.BOUND + 1),
                "card 4111 1111 1111 1111");
        for (String reason : unreasoned) {
            assertThatThrownBy(() -> world.on(DUE_2.plusDays(5), world.alice, () -> world.waivers.propose(
                    BnplApplicationFakes.UOW, p4.agreement(), late.id(), eur(1_00), reason, world.alice,
                    BnplConditionWorld.CORRELATION))).as("proposed with %s", reason)
                    .isInstanceOf(BnplReasons.ReasonRequired.class);
        }
        assertThat(fakes.corrections.waiverRows).as("nothing written").isEmpty();
        assertThat(fakes.audited).noneMatch(record -> record.operation() == BnplAuditAction.WAIVER_PROPOSED);
        BnplWaiver proposed = world.proposeWaiver(DUE_2.plusDays(5), p4, late.id(), 1_00, world.alice);
        for (String reason : unreasoned) {
            assertThatThrownBy(() -> world.on(DUE_2.plusDays(5), world.bob, () -> world.waivers.approve(
                    BnplApplicationFakes.UOW, p4.agreement(), proposed.id(), reason, world.bob,
                    BnplConditionWorld.CORRELATION))).as("approved with %s", reason)
                    .isInstanceOf(BnplReasons.ReasonRequired.class);
            assertThatThrownBy(() -> world.on(DUE_2.plusDays(5), world.bob, () -> world.waivers.reject(
                    BnplApplicationFakes.UOW, p4.agreement(), proposed.id(), reason, world.bob,
                    BnplConditionWorld.CORRELATION))).as("rejected with %s", reason)
                    .isInstanceOf(BnplReasons.ReasonRequired.class);
        }
        assertThat(fakes.book.keys).as("nothing posted").noneMatch(key -> key.contains(BnplWaivers.WAIVER_KEY_PREFIX));
        assertThat(fakes.corrections.waiverRows.get(proposed.id()).status()).isEqualTo(BnplCorrectionStatus.PROPOSED);
    }
}
