package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.DUE_3;
import static com.finapp.bnpl.BnplConditionWorld.DUE_4;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.AllocationComponent;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The BNPL proofs at rest (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 12.12, the acceptance criterion "every purchase
 * three-way zero, every obligation subledger zero and replayed {@code IDENTICAL} at rest"): histories the domain itself
 * wrote over {@link BnplProofWorld} - pay-in-4 and pay-in-N, billed, accrued, repaid, collected, refunded (every refund
 * shape), disputed, charged late, waived, reversed, paid off and closed - each proven by all three proofs at once, over
 * the whole book, and every reading non-vacuous (every obligation replayed, figures re-derived).
 */
@DisplayName("the BNPL proofs at rest: every history the domain writes holds three ways and replays IDENTICAL (P12-TSK-025)")
class BnplProofsAtRestTest {

    private final BnplProofWorld world = new BnplProofWorld();

    @Test
    @DisplayName("a pay-in-4 purchase serviced to its end and repaid holds three ways, is CLOSED all zero and replays"
            + " IDENTICAL")
    void aPayIn4ServicedToItsEndHolds() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        world.repayDueOn(DUE_2, p4, "r2");
        world.stepOn(DUE_3, p4);
        world.repayDueOn(DUE_3, p4, "r3");
        world.stepOn(DUE_4, p4);
        world.repayDueOn(DUE_4, p4, "r4");
        assertThat(world.base.obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertAtRest(1);
    }

    @Test
    @DisplayName("a pay-in-N purchase accrued daily on its declining principal, billed and repaid to its end holds three"
            + " ways and replays IDENTICAL - every accrual re-derived by ACCRUAL_ENGINE_V1")
    void aPayInNToItsEndHolds() {
        BnplConditionWorld.Agreement pn = world.base.accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        for (int sequence = 1; sequence <= 3; sequence++) {
            LocalDate due = world.base.instalment(pn, sequence).dueDate();
            world.stepOn(due, pn);
            world.repayDueOn(due, pn, "r" + sequence);
        }
        assertThat(world.base.obligation(pn).status()).isEqualTo(BnplObligationStatus.CLOSED);
        BnplRecord record = world.record(pn);
        assertThat(record.accruals()).hasSizeGreaterThan(80);
        assertThat(record.billings()).hasSize(3);
        assertAtRest(1);
    }

    @Test
    @DisplayName("every pay-in-4 refund shape holds: unbilled cancelled (schedule version 2), billed-unpaid cancelled,"
            + " a second refund, then the rest with the excess to the wallet - closed REFUNDED, every BNPL account zero")
    void everyPayIn4RefundShapeHolds() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.base.refundOn(DUE_2.minusDays(3), p4, "refund-1", 100_00);
        world.stepOn(DUE_2, p4);
        world.base.refundOn(DUE_2, p4, "refund-2", 300_00);
        world.repayDueOn(DUE_2.plusDays(1), p4, "r2");
        world.base.refundOn(DUE_2.plusDays(2), p4, "refund-3", 200_00);
        BnplRecord record = world.record(p4);
        assertThat(record.schedules()).hasSizeGreaterThan(1);
        assertThat(record.refunds()).hasSize(3);
        assertThat(record.refunds()).anySatisfy(refund -> assertThat(refund.row().excess().isPositive()).isTrue());
        assertThat(record.refunds()).anySatisfy(refund -> assertThat(refund.row().cancelledBilled().isPositive())
                .isTrue());
        assertThat(world.base.obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertAtRest(1);
    }

    @Test
    @DisplayName("a pay-in-N refund re-amortises the unbilled tail (schedule and agreement version 2) and the obligation"
            + " serviced on it to its end still holds and replays IDENTICAL")
    void aPayInNRefundReamortisedHolds() {
        BnplConditionWorld.Agreement pn = world.base.accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        LocalDate due1 = world.base.instalment(pn, 1).dueDate();
        world.stepOn(due1, pn);
        world.repayDueOn(due1, pn, "r1");
        world.accrueOn(due1.plusDays(5), pn);
        world.base.refundOn(due1.plusDays(5), pn, "refund-1", 150_00);
        BnplRecord record = world.record(pn);
        assertThat(record.schedules()).hasSize(2);
        assertThat(record.agreements()).hasSize(2);
        for (int sequence = 2; sequence <= 3; sequence++) {
            LocalDate due = world.base.instalment(pn, sequence).dueDate();
            world.stepOn(due, pn);
            world.repayDueOn(due, pn, "r" + sequence);
        }
        assertAtRest(1);
    }

    @Test
    @DisplayName("a late fee, its partial waiver and a full refund releasing the rest hold - the fee income never below"
            + " zero (R3)")
    void aLateFeeWaivedAndReleasedHolds() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepThrough(DUE_2, DUE_2.plusDays(5), p4);
        assertThat(world.corrections.fees(p4)).hasSize(1);
        BnplWaiver proposed = world.corrections.proposeWaiver(DUE_2.plusDays(6), p4, world.corrections.fees(p4).get(0)
                .id(), 2_00, world.corrections.alice);
        world.corrections.approveWaiver(DUE_2.plusDays(6), p4, proposed.id(), world.corrections.bob);
        world.base.refundOn(DUE_2.plusDays(7), p4, "refund-all", 450_00);
        assertThat(world.base.obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertAtRest(1);
    }

    @Test
    @DisplayName("a reversal re-opens what it paid beside held credit, and the next servicing step's catch-up applies it"
            + " (RG-M5 at the step's end, never between steps) - holds and replays IDENTICAL")
    void aReversalThenTheCatchUpHolds() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        BnplPurchaseStore.Repayment first = world.repayDueOn(DUE_2, p4, "r2").repayment();
        world.base.repayOn(DUE_2, p4, "held", 40_00);
        BnplRepaymentReversal proposed = world.corrections.propose(DUE_2.plusDays(1), p4, first.id(),
                world.corrections.alice);
        world.corrections.approve(DUE_2.plusDays(1), p4, first.id(), proposed.id(), world.corrections.bob);
        assertThat(world.replay(p4).verdict()).as("between steps, credit beside a re-opened item is no finding")
                .isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        world.stepOn(DUE_2.plusDays(2), p4);
        assertThat(world.record(p4).applications()).isNotEmpty();
        assertThat(world.corrections.item(p4, 2).due(AllocationComponent.PRINCIPAL)).isEqualTo(eur(110_00));
        assertAtRest(1);
    }

    @Test
    @DisplayName("a payoff of each product holds: every early billing valued the payoff's date, one PAYOFF repayment of"
            + " the quote, CLOSED PAID_OFF_EARLY")
    void aPayoffOfEachProductHolds() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        BnplConditionWorld.Agreement pn = world.base.accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_6_MONTHS);
        world.base.availableTo(5_000_00);
        world.payOffOn(DUE_2.minusDays(4), p4, "pay-p4");
        LocalDate due1 = world.base.instalment(pn, 1).dueDate();
        world.stepOn(due1, pn);
        world.repayDueOn(due1, pn, "r1");
        world.payOffOn(due1.plusDays(9), pn, "pay-pn");
        assertThat(world.record(pn).execution()).isPresent();
        assertThat(world.base.obligation(p4).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertThat(world.base.obligation(pn).status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertAtRest(2);
    }

    @Test
    @DisplayName("auto-collection, overpayment held as credit and its application at the next billing hold")
    void collectionAndApplicationsHold() {
        world.base.withFees(BnplTermsFixtures.payIn4().autoCollection(true));
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.base.repayOn(DUE_2.minusDays(1), p4, "early", 200_00);
        world.stepOn(DUE_2, p4);
        world.stepOn(DUE_3, p4);
        BnplRecord record = world.record(p4);
        assertThat(record.applications()).isNotEmpty();
        assertThat(record.attempts()).isNotEmpty();
        assertAtRest(1);
    }

    @Test
    @DisplayName("a dispute resolved as a refund (door DISPUTE) is counted by the refund identities with the others")
    void aDisputeRefundHolds() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.disputeRefundedOn(DUE_2.minusDays(3), p4, "dispute-1", 300_00);
        assertThat(world.record(p4).refunds()).singleElement()
                .satisfies(refund -> assertThat(refund.row().door()).isEqualTo(BnplRefund.Door.DISPUTE));
        assertAtRest(1);
    }

    @Test
    @DisplayName("many purchases at one merchant, every shape at once: the merchant leg sums them all")
    void manyPurchasesAtOneMerchantHold() {
        BnplConditionWorld.Agreement a = world.base.payIn4();
        BnplConditionWorld.Agreement b = world.base.payIn4();
        BnplConditionWorld.Agreement c = world.base.accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        world.base.refundOn(DUE_2.minusDays(2), a, "refund-a", 600_00);
        world.stepOn(DUE_2, b);
        world.repayDueOn(DUE_2, b, "rb");
        LocalDate due1 = world.base.instalment(c, 1).dueDate();
        world.stepOn(due1, c);
        assertAtRest(3);
        assertThat(world.reading().currencies()).singleElement()
                .satisfies(currency -> assertThat(currency.merchantLegs()).singleElement()
                        .satisfies(leg -> assertThat(leg.holds()).isTrue()));
    }

    private void assertAtRest(int obligations) {
        BnplProofs.Reading reading = world.reading();
        assertThat(reading.obligations()).as("not vacuous: every obligation read").hasSize(obligations);
        assertThat(reading.failures()).as("every obligation's subledger holds").isEmpty();
        assertThat(reading.divergences()).as("every obligation replays IDENTICAL").isEmpty();
        assertThat(reading.currencies()).as("one currency, holding three ways").singleElement()
                .satisfies(currency -> assertThat(currency.failures()).isEmpty());
        assertThat(reading.currencies().get(0).identities()).hasSize(obligations);
        assertThat(reading.obligations()).allSatisfy(proven -> assertThat(proven.replay().replayed()).isGreaterThan(2));
    }
}
