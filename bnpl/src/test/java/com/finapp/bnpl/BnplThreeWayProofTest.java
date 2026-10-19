package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static com.finapp.bnpl.RecordEdits.edited;
import static com.finapp.bnpl.RecordEdits.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.LedgerAccountId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three-way proof finds what it claims to (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 12.12, gate criterion 5):
 * the merchant leg (a raw posting on a merchant's payable under a {@code bnpl.} key that no purchase explains, a payable
 * whose {@code INV-MER-02} equation fails), the customer leg (a failing subledger), the economic identity over every
 * refund shape (the excess term, the fee recognised and returned, the fee arm under the pin) and the ledger (the trial
 * balance, the capital proof, the exposure census) - each broken alone.
 */
@DisplayName("the three-way proof: merchant leg, customer leg, economic identity, ledger - each broken alone (P12-TSK-025)")
class BnplThreeWayProofTest {

    private final BnplProofWorld world = new BnplProofWorld();

    @Test
    @DisplayName("a raw posting to a merchant payable under a bnpl. key without a purchase fails the merchant leg")
    void aRawBnplKeyedPayablePostingFailsTheMerchantLeg() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        assertThat(failures()).isEmpty();
        LedgerAccountId elsewhere = world.fakes.book.external("suspense", BnplConditionWorld.IDS.next());
        world.plantPosting("bnpl.financing:" + BnplConditionWorld.IDS.next(), elsewhere, world.base.payable, 50_00,
                DUE_2);
        assertThat(failures()).containsExactly("MERCHANT_LEG");
        BnplThreeWayProof.MerchantLeg leg = world.reading().currencies().get(0).merchantLegs().get(0);
        assertThat(leg.unexplainedEntries()).isEqualTo(1);
        assertThat(leg.sumHolds()).isFalse();
        assertThat(world.subledger(p4).holds()).as("the customer leg cannot see a payable posting").isTrue();
    }

    @Test
    @DisplayName("a payable whose INV-MER-02 equation no longer holds with the BNPL terms fails the merchant leg")
    void aBrokenPayableEquationFailsTheMerchantLeg() {
        world.base.payIn4();
        world.reads.equationHolds = false;
        assertThat(failures()).containsExactly("MERCHANT_LEG");
    }

    @Test
    @DisplayName("a failing subledger fails the customer leg")
    void aFailingSubledgerFailsTheCustomerLeg() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        world.plantPosting("fixture:raw", world.base.wallet, world.base.accounts(p4).principalDue().id(), 10_00, DUE_2);
        assertThat(failures()).contains("CUSTOMER_LEG", "ECONOMIC_IDENTITY");
    }

    @Test
    @DisplayName("every identity holds over every refund shape - a refund with an excess among them - and a refund's fee"
            + " returned misstated breaks the fee income and the fee arm")
    void everyIdentityHoldsOverEveryRefundShape() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        world.repayDueOn(DUE_2, p4, "r2");
        world.base.refundOn(DUE_2.plusDays(1), p4, "refund-1", 100_00);
        world.base.refundOn(DUE_2.plusDays(2), p4, "refund-2", 500_00);
        BnplRecord record = world.record(p4);
        assertThat(record.refunds()).anySatisfy(refund -> assertThat(refund.row().excess().isPositive()).isTrue());
        BnplThreeWayProof proof = new BnplThreeWayProof(world.reads, world.fakes.merchants);
        BnplThreeWayProof.Identity identity = proof.identity(BnplProofWorld.SNAPSHOT, record);
        assertThat(identity.failures()).isEmpty();

        int last = record.refunds().size() - 1;
        BnplRefund refund = record.refunds().get(last).row();
        // Σ refunds = Σ principal cancelled + Σ excess is each refund row's own construction (and bnpl V012's CHECK), so no
        // row can break it alone: the identity's excess term is held by this case's refund with an excess (the probe).
        BnplRecord feeMisstated = with(record, "refunds", edited(record.refunds(), last, stamped ->
                new BnplRecord.Stamped<>(with(refund, "feeReturned", refund.feeReturned().plus(eur(1)), "payableDebit",
                        refund.payableDebit().minus(eur(1))), stamped.at())));
        assertThat(proof.identity(BnplProofWorld.SNAPSHOT, feeMisstated).failures()).contains("FEE_INCOME", "FEE_ARM");
    }

    @Test
    @DisplayName("the principal identity: G = repaid + cancelled + outstanding, every refund and repayment counted - a"
            + " repayment's principal line dropped breaks it")
    void thePrincipalIdentityHolds() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        world.repayDueOn(DUE_2, p4, "r2");
        world.base.refundOn(DUE_2.plusDays(1), p4, "refund-1", 200_00);
        BnplRecord record = world.record(p4);
        BnplThreeWayProof proof = new BnplThreeWayProof(world.reads, world.fakes.merchants);
        assertThat(proof.identity(BnplProofWorld.SNAPSHOT, record).holds()).isTrue();
        BnplRecord dropped = with(record, "repayments", List.of(record.repayments().get(0)));
        assertThat(proof.identity(BnplProofWorld.SNAPSHOT, dropped).failures()).contains("PRINCIPAL");
    }

    @Test
    @DisplayName("the ledger leg: a trial balance out of zero, a failing capital proof or exposure census fails the"
            + " currency by name")
    void theLedgerLegFails() {
        world.base.payIn4();
        world.fakes.book.lines.add(new BnplApplicationFakes.Book.Line("fixture:one-sided", world.base.wallet,
                com.finapp.ledger.Direction.DEBIT, eur(1), DUE_2, DUE_2));
        assertThat(failures()).containsExactly("TRIAL_BALANCE");
        world.fakes.book.lines.remove(world.fakes.book.lines.size() - 1);
        world.reads.capitalHolds = false;
        world.reads.exposureHolds = false;
        assertThat(failures()).containsExactly("CAPITAL", "EXPOSURE");
    }

    @Test
    @DisplayName("the merchant leg reads each named entry's own net: a refund's payable debit misstated is a mismatch")
    void aMisstatedPayableDebitIsAMismatch() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.base.refundOn(DUE_2.minusDays(2), p4, "refund-1", 100_00);
        BnplRecord record = world.record(p4);
        BnplRefund refund = record.refunds().get(0).row();
        BnplRecord misstated = with(record, "refunds", edited(record.refunds(), 0, stamped -> new BnplRecord.Stamped<>(
                with(refund, "payableDebit", refund.payableDebit().plus(eur(1)), "feeReturned",
                        refund.feeReturned().minus(eur(1))), stamped.at())));
        BnplThreeWayProof proof = new BnplThreeWayProof(world.reads, world.fakes.merchants);
        BnplThreeWayProof.MerchantLeg leg = proof.merchantLeg(BnplProofWorld.SNAPSHOT, world.base.merchant,
                record.obligation().currency(), List.of(misstated));
        assertThat(leg.mismatchedEntries()).isEqualTo(1);
        assertThat(leg.holds()).isFalse();
        assertThat(proof.merchantLeg(BnplProofWorld.SNAPSHOT, world.base.merchant, record.obligation().currency(),
                List.of(record)).holds()).isTrue();
    }

    private List<String> failures() {
        List<BnplThreeWayProof.CurrencyVerdict> currencies = world.reading().currencies();
        assertThat(currencies).hasSize(1);
        return currencies.get(0).failures();
    }
}
