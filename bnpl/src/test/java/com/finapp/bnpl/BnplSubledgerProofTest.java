package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static com.finapp.bnpl.RecordEdits.edited;
import static com.finapp.bnpl.RecordEdits.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The subledger proof - the three-way proof's customer leg - finds what it claims to (`P12-TSK-025`; {@code PHASE_12_PLAN.md}
 * section 12.12 point 2; {@code INV-BNPL-01}, {@code INV-BAL-02}, {@code INV-LED-04}): each of the six identities, the
 * every-entry-named rule (a raw posting no bnpl row names - the every-writer half {@code BnplBooksHaveOnePosterTest}
 * cannot see), the wrong-side rule, the closed-all-zero rule and R3's fee income, each broken alone in a record the domain
 * wrote.
 */
@DisplayName("the subledger proof: each identity and rule, broken alone, FAILS by its own name (P12-TSK-025)")
class BnplSubledgerProofTest {

    private final BnplProofWorld world = new BnplProofWorld();
    private final BnplSubledgerProof proof = new BnplSubledgerProof();

    @Test
    @DisplayName("a raw posting to a BNPL account that no bnpl row names fails UNEXPLAINED_ENTRIES and the account's"
            + " identity - though the ledger balances")
    void aRawPostingFails() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        LedgerAccountId principalDue = world.base.accounts(p4).principalDue().id();
        world.plantPosting("fixture:raw", world.base.wallet, principalDue, 10_00, DUE_2);
        BnplSubledgerProof.Verdict verdict = world.subledger(p4);
        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.failures()).contains("UNEXPLAINED_ENTRIES", "BNPL_PRINCIPAL_DUE");
    }

    @Test
    @DisplayName("every figure is the ledger's journal lines, never a stored balance: a repayment's allocation row moved"
            + " off its line fails BNPL_PRINCIPAL_DUE and BNPL_CREDIT_BALANCE")
    void aMisstatedRowFailsItsIdentities() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        world.repayDueOn(DUE_2, p4, "r2");
        BnplRecord record = world.record(p4);
        assertThat(proof.prove(record).holds()).isTrue();
        int index = record.repayments().size() - 1;
        BnplPurchaseStore.Repayment repayment = record.repayments().get(index).row();
        BnplPurchaseStore.Allocation line = repayment.allocations().get(0);
        BnplRecord planted = with(record, "repayments", edited(record.repayments(), index, stamped ->
                new BnplRecord.Stamped<>(with(stamped.row(), "allocations", List.of(new BnplPurchaseStore.Allocation(
                        line.id(), 1, "CREDIT_BALANCE", java.util.Optional.empty(), java.util.Optional.empty(),
                        line.amount()))), stamped.at())));
        assertThat(proof.prove(planted).failures()).contains("BNPL_PRINCIPAL_DUE", "BNPL_CREDIT_BALANCE");
    }

    @Test
    @DisplayName("each of the six identities fails by its own name when only its own rows disagree")
    void eachIdentityFailsByItsName() {
        BnplConditionWorld.Agreement pn = world.base.accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        java.time.LocalDate due1 = world.base.instalment(pn, 1).dueDate();
        world.stepOn(due1, pn);
        world.accrueOn(due1.plusDays(4), pn);
        BnplRecord record = world.record(pn);
        assertThat(proof.prove(record).holds()).isTrue();
        // The financing's gross one cent more: BNPL_PRINCIPAL alone.
        MerchantFinancing financing = record.financing();
        MerchantFeeSplit split = financing.split();
        BnplRecord richer = with(record, "financing", new MerchantFinancing(financing.purchase(), financing.merchant(),
                new MerchantFeeSplit(split.gross().plus(eur(1)), split.fee(), split.net().plus(eur(1)),
                        split.feeScheduleVersionId()), financing.entry()));
        assertThat(proof.prove(richer).failures()).containsExactly("BNPL_PRINCIPAL");
        // An accrual's posted figure one cent more: BNPL_INTEREST_ACCRUED alone.
        BnplRecord accrued = with(record, "accruals", edited(record.accruals(), record.accruals().size() - 1, stamped ->
                new BnplRecord.Stamped<>(with(stamped.row(), "accrual", with(stamped.row().accrual(), "posted",
                        stamped.row().accrual().posted().plus(eur(1)))), stamped.at())));
        assertThat(proof.prove(accrued).failures()).containsExactly("BNPL_INTEREST_ACCRUED");
        // The billing's interest one cent more: BNPL_INTEREST_DUE and BNPL_INTEREST_ACCRUED.
        BnplRecord billed = with(record, "billings", edited(record.billings(), 0, billing -> with(billing, "row",
                with(billing.row(), "interest", billing.row().interest().plus(eur(1))))));
        assertThat(proof.prove(billed).failures()).containsExactly("BNPL_INTEREST_ACCRUED", "BNPL_INTEREST_DUE");
    }

    @Test
    @DisplayName("a closed obligation with a balance left fails TERMINAL_NOT_ZERO; a due account on its wrong side"
            + " fails NEGATIVE")
    void aClosedObligationIsAllZeroAndNoDueAccountNegative() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.base.refundOn(DUE_2.minusDays(2), p4, "refund-all", 600_00);
        BnplRecord record = world.record(p4);
        assertThat(record.obligation().status()).isEqualTo(BnplObligationStatus.CLOSED);
        assertThat(proof.prove(record).holds()).isTrue();
        // One extra line on BNPL_PRINCIPAL_DUE (credit) and its mirror elsewhere, named by the refund's entry.
        LedgerAccountId due = record.account(AccountPurpose.BNPL_PRINCIPAL_DUE).orElseThrow().id();
        LedgerAccountId principal = record.account(AccountPurpose.BNPL_PRINCIPAL).orElseThrow().id();
        List<PurposeBalances.DatedLine> lines = new ArrayList<>(record.journalLines());
        com.finapp.ledger.JournalEntryId refundEntry = record.refunds().get(0).row().entry();
        lines.add(new PurposeBalances.DatedLine(refundEntry, due, DUE_2, Direction.CREDIT, eur(1_00)));
        lines.add(new PurposeBalances.DatedLine(refundEntry, principal, DUE_2, Direction.DEBIT, eur(1_00)));
        BnplSubledgerProof.Verdict verdict = proof.prove(with(record, "journalLines", lines));
        assertThat(verdict.failures()).contains("NEGATIVE", "TERMINAL_NOT_ZERO");
    }

    @Test
    @DisplayName("R3: a late fee waived after a refund released it - the platform earning less than nothing on it -"
            + " fails FEE_INCOME_NEGATIVE even where the six balances agree")
    void aFeeForgivenTwiceFails() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepThrough(DUE_2, DUE_2.plusDays(5), p4);
        BnplFeeAssessment fee = world.corrections.fees(p4).get(0);
        BnplWaiver proposed = world.corrections.proposeWaiver(DUE_2.plusDays(6), p4, fee.id(), 2_00,
                world.corrections.alice);
        world.corrections.approveWaiver(DUE_2.plusDays(6), p4, proposed.id(), world.corrections.bob);
        BnplRecord record = world.record(p4);
        assertThat(proof.prove(record).holds()).isTrue();
        BnplRecord twice = with(record, "waivers", edited(record.waivers(), 0, waiver -> with(waiver, "amount",
                eur(6_00))));
        assertThat(BnplSubledgerProof.feeIncomeNonNegative(twice)).isFalse();
        assertThat(proof.prove(twice).failures()).contains("FEE_INCOME_NEGATIVE");
    }

    @Test
    @DisplayName("R3 alone: one fee forgiven beyond itself while another stays due - the six balances explained, the"
            + " account not negative - is the verdict's only failure, and the verdict FAILS")
    void anOverForgivenFeeAloneFailsTheVerdict() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepThrough(DUE_2, BnplConditionWorld.DUE_3.plusDays(5), p4);
        List<BnplFeeAssessment> fees = world.corrections.fees(p4);
        assertThat(fees).hasSize(2);
        BnplWaiver proposed = world.corrections.proposeWaiver(BnplConditionWorld.DUE_3.plusDays(6), p4, fees.get(0).id(),
                2_00, world.corrections.alice);
        world.corrections.approveWaiver(BnplConditionWorld.DUE_3.plusDays(6), p4, proposed.id(), world.corrections.bob);
        BnplRecord record = world.record(p4);
        assertThat(proof.prove(record).holds()).isTrue();
        // The waiver planted 5.00 larger, its F7 entry's line on BNPL_FEES_DUE with it: rows and ledger agree.
        BnplWaiver waiver = record.waivers().get(0);
        LedgerAccountId feesDue = record.account(AccountPurpose.BNPL_FEES_DUE).orElseThrow().id();
        List<PurposeBalances.DatedLine> lines = new ArrayList<>(record.journalLines());
        lines.add(new PurposeBalances.DatedLine(waiver.entry().orElseThrow(), feesDue,
                BnplConditionWorld.DUE_3.plusDays(6), Direction.CREDIT, eur(5_00)));
        BnplRecord planted = with(with(record, "waivers", edited(record.waivers(), 0, row -> with(row, "amount",
                row.amount().plus(eur(5_00))))), "journalLines", lines);
        BnplSubledgerProof.Verdict verdict = proof.prove(planted);
        assertThat(verdict.failures()).containsExactly("FEE_INCOME_NEGATIVE");
        assertThat(verdict.holds()).isFalse();
    }

    @Test
    @DisplayName("an obligation missing one of its six accounts fails ACCOUNTS")
    void anIncompleteChartFails() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        BnplRecord record = world.record(p4);
        BnplRecord missing = with(record, "ledgerAccounts", record.ledgerAccounts().stream()
                .filter(account -> account.purpose() != AccountPurpose.BNPL_FEES_DUE).toList());
        assertThat(proof.prove(missing).failures()).contains("ACCOUNTS");
    }
}
