package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static com.finapp.bnpl.RecordEdits.edited;
import static com.finapp.bnpl.RecordEdits.with;
import static com.finapp.bnpl.RecordEdits.without;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.InterestAccrual;
import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The replay proof finds what it claims to (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 12.12 point 4): each fact the
 * replay re-derives, planted wrong in a record the domain otherwise wrote ({@link RecordEdits}), is {@code DIVERGED} under
 * exactly its check - a byte flipped in an accrual, a billing, an allocation, a refund's adjustment or an agreement's
 * terms; an engine version this build does not hold; and the two arms the Phase 11 gate taught: a principal fact valued
 * before its floor (R1) and held credit standing beside a past-due item at a servicing step's end (RG-M5). The same record
 * unplanted replays {@code IDENTICAL} - every finding is the plant's.
 */
@DisplayName("the replay proof: every planted divergence is DIVERGED under its own check (P12-TSK-025)")
class BnplReplayProofTest {

    private final BnplProofWorld world = new BnplProofWorld();
    private final BnplReplayProof replay = new BnplReplayProof();

    @Test
    @DisplayName("a byte flipped in an accrual's posted figure is ACCRUAL")
    void aFlippedAccrualDiverges() {
        BnplRecord record = payInNServiced();
        int index = record.accruals().size() / 2;
        BnplRecord planted = with(record, "accruals", edited(record.accruals(), index, stamped -> {
            InterestAccrual accrual = stamped.row().accrual();
            return new BnplRecord.Stamped<>(with(stamped.row(), "accrual", with(accrual, "posted",
                    accrual.posted().plus(eur(1)))), stamped.at());
        }));
        assertDiverged(record, planted, BnplReplayProof.Check.ACCRUAL, record.accruals().get(index).row().id());
    }

    @Test
    @DisplayName("an elapsed date with principal outstanding and no accrual is ACCRUAL_MISSING")
    void aMissingAccrualDiverges() {
        BnplRecord record = payInNServiced();
        int index = 10;
        BnplInterestAccrual dropped = record.accruals().get(index).row();
        BnplRecord planted = with(record, "accruals", without(record.accruals(), index));
        planted = dropped.entry().map(entry -> RecordEdits.withoutEntry(record, entry)).map(edited ->
                with(edited, "accruals", without(record.accruals(), index))).orElse(planted);
        BnplReplayProof.Replay replayed = replay.replay(planted);
        assertThat(replayed.verdict()).isEqualTo(BnplReplayProof.Verdict.DIVERGED);
        assertThat(replayed.checks()).contains(BnplReplayProof.Check.ACCRUAL_MISSING);
    }

    @Test
    @DisplayName("a billing's principal off by a cent is BILLING")
    void aFlippedBillingDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        BnplRecord record = world.record(p4);
        int index = 1;
        BnplRecord planted = with(record, "billings", edited(record.billings(), index, billing ->
                with(billing, "row", with(billing.row(), "principal", billing.row().principal().minus(eur(1))))));
        assertDiverged(record, planted, BnplReplayProof.Check.BILLING, record.billings().get(index).row().id());
    }

    @Test
    @DisplayName("an allocation line moved from one component to another is ALLOCATION")
    void aMisAllocatedRepaymentDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        world.repayDueOn(DUE_2, p4, "r2");
        world.base.repayOn(DUE_2.plusDays(1), p4, "held", 40_00);
        BnplRecord record = world.record(p4);
        int index = record.repayments().size() - 1;
        BnplPurchaseStore.Repayment held = record.repayments().get(index).row();
        BnplPurchaseStore.Allocation line = held.allocations().get(0);
        BnplPurchaseStore.Allocation moved = new BnplPurchaseStore.Allocation(line.id(), line.sequence(), "DUE_ITEM",
                Optional.of(record.billings().get(0).row().instalment()),
                Optional.of("PRINCIPAL"), line.amount());
        BnplRecord planted = with(record, "repayments", edited(record.repayments(), index, stamped ->
                new BnplRecord.Stamped<>(with(stamped.row(), "allocations", List.of(moved)), stamped.at())));
        assertDiverged(record, planted, BnplReplayProof.Check.ALLOCATION, held.id());
    }

    @Test
    @DisplayName("a refund adjustment's whole flag flipped is REFUND")
    void aFlippedRefundAdjustmentDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        world.base.refundOn(DUE_2, p4, "refund-1", 300_00);
        BnplRecord record = world.record(p4);
        BnplRefund refund = record.refunds().get(0).row();
        BnplRefund.Adjustment first = refund.adjustments().get(0);
        BnplRefund.Adjustment flipped = with(first, "whole", !first.whole());
        BnplRecord planted = with(record, "refunds", edited(record.refunds(), 0, stamped ->
                new BnplRecord.Stamped<>(with(stamped.row(), "adjustments", edited(refund.adjustments(), 0,
                        any -> flipped)), stamped.at())));
        assertDiverged(record, planted, BnplReplayProof.Check.REFUND, refund.id());
    }

    @Test
    @DisplayName("an agreement's canonical terms with one byte flipped and its hash recomputed to match (so the column"
            + " CHECK would hold) are TERMS - the re-render from the offer's rows refuses them")
    void aFlippedAgreementDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        BnplRecord record = world.record(p4);
        BnplAgreement agreement = record.agreements().get(0).row();
        byte[] bytes = agreement.canonicalTerms().getBytes(StandardCharsets.UTF_8);
        int at = bytes.length / 2;
        bytes[at] = (byte) (bytes[at] == '1' ? '2' : '1');
        String flipped = new String(bytes, StandardCharsets.UTF_8);
        BnplAgreement planted = with(with(agreement, "canonicalTerms", flipped), "termsSha256",
                BnplOfferTerms.sha256(flipped));
        BnplRecord broken = with(record, "agreements", edited(record.agreements(), 0, version ->
                with(version, "row", planted)));
        assertDiverged(record, broken, BnplReplayProof.Check.TERMS, agreement.id().value());
    }

    @Test
    @DisplayName("an agreement pinning an engine version this build does not hold is ENGINE_ABSENT, never skipped")
    void anAbsentEngineDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        BnplRecord record = world.record(p4);
        BnplAgreement agreement = record.agreements().get(0).row();
        BnplRecord broken = with(record, "agreements", edited(record.agreements(), 0, version ->
                with(version, "row", with(agreement, "refundAllocationEngine", "REFUND_ALLOCATION_ENGINE_V9"))));
        assertThat(replay.replay(broken).checks()).contains(BnplReplayProof.Check.ENGINE_ABSENT);
    }

    @Test
    @DisplayName("schedule version 1 with an instalment's due date moved is SCHEDULE")
    void aMovedScheduleDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        BnplRecord record = world.record(p4);
        BnplPurchaseStore.StoredSchedule first = record.schedules().get(0).row();
        BnplPurchaseStore.StoredInstalment last = first.instalments().get(3);
        BnplPurchaseStore.StoredSchedule moved = with(first, "instalments", edited(first.instalments(), 3,
                any -> with(last, "dueDate", last.dueDate().plusDays(1))));
        BnplRecord broken = with(record, "schedules", edited(record.schedules(), 0, version ->
                with(version, "row", moved)));
        assertDiverged(record, broken, BnplReplayProof.Check.SCHEDULE, first.id());
    }

    @Test
    @DisplayName("R1's arm: a planted repayment valued before the obligation's floor (its row and its lines dated back)"
            + " is VALUE_DATE")
    void aPreFloorRepaymentDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        world.repayDueOn(DUE_2.plusDays(2), p4, "r2");
        BnplRecord record = world.record(p4);
        int index = record.repayments().size() - 1;
        BnplPurchaseStore.Repayment repayment = record.repayments().get(index).row();
        LocalDate before = DUE_2.minusDays(1); // the billing on DUE_2 put the floor at DUE_2
        BnplRecord planted = RecordEdits.redated(with(record, "repayments", edited(record.repayments(), index,
                stamped -> new BnplRecord.Stamped<>(with(stamped.row(), "valueDate", before), stamped.at()))),
                repayment.entry(), before);
        assertDiverged(record, planted, BnplReplayProof.Check.VALUE_DATE, repayment.id());
    }

    @Test
    @DisplayName("R1's arm on the accrual: a principal line dated back into an already-accrued date changes the final"
            + " ledger's base under that accrual - ACCRUAL")
    void aPrincipalLineDatedIntoAnAccruedDateDiverges() {
        BnplConditionWorld.Agreement pn = world.base.accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        LocalDate due1 = world.base.instalment(pn, 1).dueDate();
        world.stepOn(due1, pn);
        // The dates up to the repayment's eve accrued BEFORE it is written: their own as-of base cannot see it.
        world.accrueOn(due1.plusDays(4), pn);
        world.repayDueOn(due1.plusDays(4), pn, "r1");
        world.accrueOn(due1.plusDays(9), pn);
        BnplRecord record = world.record(pn);
        BnplPurchaseStore.Repayment repayment = record.repayments().get(record.repayments().size() - 1).row();
        BnplRecord planted = RecordEdits.redated(record, repayment.entry(), due1.plusDays(1));
        BnplReplayProof.Replay replayed = replay.replay(planted);
        assertThat(replay.replay(record).verdict()).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        // Only the accruals of due1+1 .. due1+3 - written before the repayment, so re-derived exactly on their own base -
        // see the line dated back into them: through the final ledger's base, R1's arm.
        java.util.Set<java.util.UUID> accruedBefore = new java.util.HashSet<>();
        record.accruals().stream().filter(stamped -> stamped.row().accrual().date().isAfter(due1)
                && stamped.row().accrual().date().isBefore(due1.plusDays(4)))
                .forEach(stamped -> accruedBefore.add(stamped.row().id()));
        assertThat(accruedBefore).isNotEmpty();
        assertThat(replayed.findings()).filteredOn(finding -> finding.check() == BnplReplayProof.Check.ACCRUAL)
                .extracting(BnplReplayProof.Finding::subject).containsAnyElementsOf(accruedBefore);
    }

    @Test
    @DisplayName("RG-M5: held credit beside a past-due item at a servicing step's end - the step's catch-up application"
            + " planted away, its later slots written - is CATCH_UP_MISSING")
    void heldCreditBesideAPastDueItemAtAStepsEndDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        BnplPurchaseStore.Repayment first = world.repayDueOn(DUE_2, p4, "r2").repayment();
        world.base.repayOn(DUE_2, p4, "held", 40_00);
        BnplRepaymentReversal proposed = world.corrections.propose(DUE_2.plusDays(1), p4, first.id(),
                world.corrections.alice);
        world.corrections.approve(DUE_2.plusDays(1), p4, first.id(), proposed.id(), world.corrections.bob);
        world.stepOn(DUE_2.plusDays(2), p4);
        BnplRecord record = world.record(p4);
        int index = record.applications().size() - 1;
        BnplCreditBalanceApplication catchUp = record.applications().get(index).row();
        assertThat(catchUp.catchUp()).isPresent();
        assertThat(record.conditions()).as("the step's later slot was written").isNotEmpty();
        BnplRecord planted = RecordEdits.withoutEntry(with(record, "applications", without(record.applications(),
                index)), catchUp.entry());
        BnplReplayProof.Replay replayed = replay.replay(planted);
        assertThat(replay.replay(record).verdict()).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        assertThat(replayed.checks()).contains(BnplReplayProof.Check.CATCH_UP_MISSING);
        assertThat(new BnplSubledgerProof().prove(planted).holds())
                .as("the plant is consistent: rows and ledger both lost the application - only the replay sees it")
                .isTrue();
    }

    @Test
    @DisplayName("credit held beside a billing at its birth with no application at the billing is APPLICATION_MISSING")
    void aMissingApplicationAtBillingDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.base.repayOn(DUE_2.minusDays(1), p4, "early", 40_00);
        world.stepOn(DUE_2, p4);
        BnplRecord record = world.record(p4);
        BnplCreditBalanceApplication atBilling = record.applications().get(0).row();
        assertThat(atBilling.billing()).isPresent();
        BnplRecord planted = RecordEdits.withoutEntry(with(record, "applications", without(record.applications(), 0)),
                atBilling.entry());
        assertThat(replay.replay(planted).checks()).contains(BnplReplayProof.Check.APPLICATION_MISSING);
    }

    @Test
    @DisplayName("a late fee above the pinned fee is FEE; a waiver beyond its room is WAIVER")
    void aWrongFeeAndAnOverWaiverDiverge() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepThrough(DUE_2, DUE_2.plusDays(5), p4);
        BnplFeeAssessment fee = world.corrections.fees(p4).get(0);
        BnplWaiver proposed = world.corrections.proposeWaiver(DUE_2.plusDays(6), p4, fee.id(), 2_00,
                world.corrections.alice);
        world.corrections.approveWaiver(DUE_2.plusDays(6), p4, proposed.id(), world.corrections.bob);
        BnplRecord record = world.record(p4);
        BnplRecord dearer = with(record, "fees", edited(record.fees(), 0, stamped -> new BnplRecord.Stamped<>(
                with(stamped.row(), "amount", stamped.row().amount().plus(eur(1))), stamped.at())));
        assertDiverged(record, dearer, BnplReplayProof.Check.FEE, fee.id());
        BnplRecord overWaived = with(record, "waivers", edited(record.waivers(), 0, waiver ->
                with(waiver, "amount", eur(6_00))));
        assertThat(replay.replay(overWaived).checks()).contains(BnplReplayProof.Check.WAIVER);
    }

    @Test
    @DisplayName("a reversal negating less than its repayment paid is REVERSAL; a closure refunding other than the credit"
            + " held is CLOSURE")
    void aShortReversalAndAWrongClosureDiverge() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        BnplPurchaseStore.Repayment first = world.repayDueOn(DUE_2, p4, "r2").repayment();
        BnplRepaymentReversal proposed = world.corrections.propose(DUE_2.plusDays(1), p4, first.id(),
                world.corrections.alice);
        world.corrections.approve(DUE_2.plusDays(1), p4, first.id(), proposed.id(), world.corrections.bob);
        BnplRecord record = world.record(p4);
        BnplRecord shortReversal = with(record, "reversals", edited(record.reversals(), 0, reversal ->
                with(reversal, "negations", edited(reversal.negations(), 0, negation ->
                        new BnplRecord.Negation(negation.allocation(), negation.amount().minus(eur(1)))))));
        assertDiverged(record, shortReversal, BnplReplayProof.Check.REVERSAL, proposed.id());

        world.base.refundOn(DUE_2.plusDays(2), p4, "refund-all", 600_00);
        BnplRecord closed = world.record(p4);
        assertThat(closed.closure()).isPresent();
        BnplClosure closure = closed.closure().get().row();
        Money refund = closure.refund().plus(eur(1));
        BnplRecord wrong = with(closed, "closure", Optional.of(new BnplRecord.Stamped<>(new BnplClosure(closure.id(),
                closure.obligation(), closure.reason(), refund, Optional.of(closure.refundEntry().orElse(
                        JournalEntryId.of(BnplConditionWorld.IDS.next()))), closure.repayment(), closure.closingRefund(),
                closure.correlation()), closed.closure().get().at())));
        assertThat(replay.replay(wrong).checks()).contains(BnplReplayProof.Check.CLOSURE);
    }

    @Test
    @DisplayName("a payoff quote whose balances are not the ledger's at its birth is PAYOFF")
    void aPayoffAwayFromItsQuoteDiverges() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.payOffOn(DUE_2.minusDays(4), p4, "pay");
        BnplRecord record = world.record(p4);
        BnplPayoffQuote quote = record.quotes().get(0).row();
        BnplRecord planted = with(record, "quotes", edited(record.quotes(), 0, stamped -> new BnplRecord.Stamped<>(
                with(quote, "principal", quote.principal().minus(eur(1)), "feesDue", quote.feesDue().plus(eur(1))),
                stamped.at())));
        assertThat(replay.replay(planted).checks()).contains(BnplReplayProof.Check.PAYOFF);
    }

    @Test
    @DisplayName("a pay-in-N quote's projection beyond its date re-derives by ACCRUAL_ENGINE_V1 - and one cent more of"
            + " projected interest (its amount with it, the row's own CHECK kept) is PAYOFF")
    void aQuotesProjectionReDerives() {
        BnplConditionWorld.Agreement pn = world.base.accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_6_MONTHS);
        LocalDate due1 = world.base.instalment(pn, 1).dueDate();
        world.stepOn(due1, pn);
        world.repayDueOn(due1, pn, "r1");
        BnplPayoffQuote quote = world.quoteOn(due1.plusDays(3), pn, "quote-ahead",
                Optional.of(due1.plusDays(12))).quote();
        assertThat(quote.projectedDays()).isEqualTo(9);
        assertThat(quote.projectedInterest().isPositive()).isTrue();
        BnplRecord record = world.record(pn);
        BnplRecord planted = with(record, "quotes", edited(record.quotes(), 0, stamped -> new BnplRecord.Stamped<>(
                with(quote, "projectedInterest", quote.projectedInterest().plus(eur(1)), "amount",
                        quote.amount().plus(eur(1))), stamped.at())));
        assertDiverged(record, planted, BnplReplayProof.Check.PAYOFF, quote.id());
    }

    // ------------------------------------------------------------------

    private BnplRecord payInNServiced() {
        BnplConditionWorld.Agreement pn = world.base.accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        LocalDate due1 = world.base.instalment(pn, 1).dueDate();
        world.stepOn(due1, pn);
        world.repayDueOn(due1, pn, "r1");
        world.accrueOn(due1.plusDays(5), pn);
        return world.record(pn);
    }

    /** The record unplanted replays IDENTICAL; planted, it is DIVERGED under {@code check} for {@code subject}. */
    private void assertDiverged(BnplRecord record, BnplRecord planted, BnplReplayProof.Check check,
            java.util.UUID subject) {
        assertThat(replay.replay(record).verdict()).as("unplanted, the record replays IDENTICAL")
                .isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        BnplReplayProof.Replay replayed = replay.replay(planted);
        assertThat(replayed.verdict()).isEqualTo(BnplReplayProof.Verdict.DIVERGED);
        assertThat(replayed.findings()).contains(new BnplReplayProof.Finding(check, subject));
    }
}
