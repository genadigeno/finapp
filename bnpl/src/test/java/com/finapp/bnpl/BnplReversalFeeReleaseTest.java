package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static com.finapp.bnpl.BnplConditionWorld.DUE_3;
import static com.finapp.bnpl.BnplConditionWorld.eur;
import static com.finapp.bnpl.RecordEdits.edited;
import static com.finapp.bnpl.RecordEdits.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.ledger.AccountPurpose;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A reversal releases the late fee it re-opens on an instalment a refund cancelled whole - OQ-14 kept whichever of the two
 * acts comes first (the Phase 12 transition gate's A7-3; {@code bnpl V019}; {@code INV-BNPL-06}, {@code INV-BNPL-01},
 * {@code INV-REV-01}). A fee already PAID when a refund cancels its instalment whole is not released by the refund; a
 * reversal of the payment re-opens it exactly (F8) - and before the repair left it owed, though the two acts in the other
 * order release it. Over {@link BnplProofWorld}: T-ref, T-rev and both proofs; the database's own ranks are
 * {@code BnplRepaymentReversalDatabaseTest}'s.
 *
 * <p>The history: pay-in-4, instalment 2 repaid whole on its due date (P1); instalment 3 billed, its late fee (5.00)
 * assessed and repaid exactly by R; P1 reversed, so instalment 2 owes again and a refund of 300.00 - instalments 4 and 3,
 * latest first, each cancelled whole - leaves the obligation {@code ACTIVE}. Then the refund and R's reversal, in either
 * order.
 */
@DisplayName("a reversal releases the late fee it re-opens where a refund cancelled the instalment whole - both orders,"
        + " one end state (the Phase 12 transition gate's A7-3, OQ-14)")
class BnplReversalFeeReleaseTest {

    private static final LocalDate PAID = DUE_3.plusDays(6);
    private static final LocalDate FIRST = PAID.plusDays(1);
    private static final LocalDate SECOND = PAID.plusDays(2);

    /** One history up to the two acts: its world, its agreement, R (the fee's payment) and instalment 3's id. */
    private record Prelude(BnplProofWorld world, BnplConditionWorld.Agreement p4, BnplPurchaseStore.Repayment fee,
            UUID second, UUID third) {}

    private static Prelude prelude() {
        BnplProofWorld world = new BnplProofWorld();
        BnplConditionWorld base = world.base;
        BnplConditionWorld.Agreement p4 = base.payIn4();
        base.serviceOn(DUE_2, p4);
        BnplPurchaseStore.Repayment second = base.repayOn(DUE_2, p4, "p1", 150_00).repayment();
        base.serviceOn(DUE_3, p4);
        base.serviceOn(DUE_3.plusDays(5), p4);
        assertThat(base.fees(p4)).as("instalment 3's late fee").containsExactly("3 " + DUE_3.plusDays(5) + " 500");
        BnplPurchaseStore.Repayment fee = base.repayOn(PAID, p4, "fee", 5_00).repayment();
        UUID third = base.instalment(p4, 3).id();
        UUID secondInstalment = base.instalment(p4, 2).id();
        assertThat(fee.allocations()).as("R paid instalment 3's fee and nothing else").singleElement()
                .satisfies(line -> {
                    assertThat(line.component()).contains("FEES");
                    assertThat(line.instalment()).contains(third);
                });
        reverse(world, PAID, p4, second);
        assertThat(world.corrections.item(p4, 2).due(AllocationComponent.PRINCIPAL)).isEqualTo(eur(150_00));
        return new Prelude(world, p4, fee, secondInstalment, third);
    }

    private static BnplRepaymentReversal reverse(BnplProofWorld world, LocalDate date, BnplConditionWorld.Agreement p4,
            BnplPurchaseStore.Repayment repayment) {
        BnplRepaymentReversal proposed = world.corrections.propose(date, p4, repayment.id(), world.corrections.alice);
        return world.corrections.approve(date, p4, repayment.id(), proposed.id(), world.corrections.bob).reversal();
    }

    private static BnplRefund refund(Prelude prelude, LocalDate date) {
        BnplRefund refund = prelude.world().base.refundOn(date, prelude.p4(), "refund", 300_00).refund();
        assertThat(refund.adjustments()).as("instalments 4 and 3 cancelled whole, latest first")
                .extracting(BnplRefund.Adjustment::sequence, BnplRefund.Adjustment::whole)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(4, true), org.assertj.core.groups.Tuple.tuple(3, true));
        assertThat(prelude.world().base.obligation(prelude.p4()).status()).as("instalment 2 still owes")
                .isEqualTo(BnplObligationStatus.ACTIVE);
        return refund;
    }

    // ------------------------------------------------------------------ the two orders

    @Test
    @DisplayName("the refund first, then the reversal: the refund releases nothing on instalment 3 (its fee was paid); the"
            + " reversal re-opens the fee and releases it in its own transaction - one row, one entry of its own (DR"
            + " BNPL_FEE_INCOME / CR BNPL_FEES_DUE), nothing owed on the instalment, the proofs agreeing")
    void theRefundFirstThenTheReversal() {
        Prelude prelude = prelude();
        BnplRefund refund = refund(prelude, FIRST);
        assertThat(refund.lateFeeReleased().isZero()).as("nothing due of the fee when the refund came").isTrue();
        prelude.world().fakes.events.clear();
        prelude.world().fakes.payloads.clear();

        BnplRepaymentReversal reversal = reverse(prelude.world(), SECOND, prelude.p4(), prelude.fee());

        assertThat(prelude.world().fakes.corrections.feeReleases).singleElement().satisfies(release -> {
            assertThat(release.reversal()).isEqualTo(reversal.id());
            assertThat(release.instalment()).isEqualTo(prelude.third());
            assertThat(release.amount()).isEqualTo(eur(5_00));
        });
        assertThat(prelude.world().base.labelled(prelude.world().fakes.book.linesOf(
                BnplRepaymentReversals.FEE_RELEASE_KEY_PREFIX + reversal.id())))
                .containsExactlyInAnyOrder("BNPL_FEE_INCOME DEBIT 500", "BNPL_FEES_DUE CREDIT 500");
        assertThat(prelude.world().fakes.payloads.get(0)).contains("\"lateFeeReleasedMinor\":\"500\"");
        assertNothingOwedOnTheCancelledInstalment(prelude);
        assertProven(prelude);
    }

    @Test
    @DisplayName("the reversal first, then the refund: the reversal re-opens the fee and releases nothing (no refund has"
            + " cancelled instalment 3 yet); the refund cancels it whole and releases the fee (OQ-14) - nothing owed on"
            + " the instalment, the proofs agreeing")
    void theReversalFirstThenTheRefund() {
        Prelude prelude = prelude();
        reverse(prelude.world(), FIRST, prelude.p4(), prelude.fee());
        assertThat(prelude.world().fakes.corrections.feeReleases).as("nothing to release yet").isEmpty();
        assertThat(billed(prelude, prelude.third()).due(AllocationComponent.FEES)).isEqualTo(eur(5_00));

        BnplRefund refund = refund(prelude, SECOND);
        assertThat(refund.lateFeeReleased()).as("the refund releases the re-opened fee").isEqualTo(eur(5_00));
        assertNothingOwedOnTheCancelledInstalment(prelude);
        assertProven(prelude);
    }

    @Test
    @DisplayName("both orders reach ONE end state: the obligation's six balances, the fee income, the wallet, every"
            + " instalment's due components and the fee's waiver room equal - only who released the fee differs")
    void bothOrdersReachOneEndState() {
        Prelude refundFirst = prelude();
        refund(refundFirst, FIRST);
        reverse(refundFirst.world(), SECOND, refundFirst.p4(), refundFirst.fee());

        Prelude reversalFirst = prelude();
        reverse(reversalFirst.world(), FIRST, reversalFirst.p4(), reversalFirst.fee());
        refund(reversalFirst, SECOND);

        assertThat(endState(refundFirst)).isEqualTo(endState(reversalFirst));
        assertThat(endState(refundFirst)).containsEntry("fees due on instalment 3", 0L)
                .containsEntry("room of instalment 3's fee", 0L).containsEntry("principal due on instalment 2", 150_00L);
    }

    // ------------------------------------------------------------------ the replay's arm

    @Test
    @DisplayName("the replay re-derives the release by the domain's rule: a release planted a cent short - or dropped - is"
            + " REVERSAL; unplanted, IDENTICAL")
    void aPlantedReleaseDiverges() {
        Prelude prelude = prelude();
        refund(prelude, FIRST);
        BnplRepaymentReversal reversal = reverse(prelude.world(), SECOND, prelude.p4(), prelude.fee());
        BnplRecord record = prelude.world().record(prelude.p4());
        BnplReplayProof replay = new BnplReplayProof();
        assertThat(replay.replay(record).verdict()).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        int index = indexOf(record, reversal.id());
        BnplRecord shortRelease = with(record, "reversals", edited(record.reversals(), index, stamped ->
                with(stamped, "releases", edited(stamped.releases(), 0, release ->
                        new BnplRecord.FeeRelease(release.instalment(), release.amount().minus(eur(1)), release.entry())))));
        assertThat(replay.replay(shortRelease).findings())
                .contains(new BnplReplayProof.Finding(BnplReplayProof.Check.REVERSAL, reversal.id()));
        BnplRecord dropped = with(record, "reversals", edited(record.reversals(), index, stamped ->
                with(stamped, "releases", List.of())));
        assertThat(replay.replay(dropped).findings())
                .contains(new BnplReplayProof.Finding(BnplReplayProof.Check.REVERSAL, reversal.id()));
    }

    // ------------------------------------------------------------------ plumbing

    private static void assertNothingOwedOnTheCancelledInstalment(Prelude prelude) {
        BnplPurchaseStore.BilledItem third = billed(prelude, prelude.third());
        assertThat(third.due(AllocationComponent.FEES).isZero()).as("no fee owed on a wholly cancelled instalment")
                .isTrue();
        assertThat(third.due(AllocationComponent.PRINCIPAL).isZero()).isTrue();
        BnplFeeAssessment fee = prelude.world().corrections.fees(prelude.p4()).get(0);
        assertThat(prelude.world().fakes.corrections.room(fee).isZero()).as("nothing released is forgiven again")
                .isTrue();
    }

    private static void assertProven(Prelude prelude) {
        BnplSubledgerProof.Verdict subledger = prelude.world().subledger(prelude.p4());
        assertThat(subledger.holds()).as("the subledger proof: %s", subledger).isTrue();
        BnplReplayProof.Replay replay = prelude.world().replay(prelude.p4());
        assertThat(replay.findings()).as("the replay").isEmpty();
        assertThat(replay.verdict()).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
    }

    private static Map<String, Long> endState(Prelude prelude) {
        BnplConditionWorld base = prelude.world().base;
        BnplLedgerAccounts accounts = base.accounts(prelude.p4());
        Map<String, Long> state = new LinkedHashMap<>();
        for (com.finapp.ledger.LedgerAccount account : accounts.all()) {
            state.put(account.purpose().name(), base.balance(account).minorUnits());
        }
        state.put(AccountPurpose.BNPL_FEE_INCOME.name(), prelude.world().corrections.feeIncome().minorUnits());
        state.put("wallet", prelude.world().fakes.book.availableUnderLock(BnplApplicationFakes.UOW, base.wallet)
                .minorUnits());
        for (int sequence = 2; sequence <= 3; sequence++) {
            BnplPurchaseStore.BilledItem item = billed(prelude, sequence == 2 ? prelude.second() : prelude.third());
            for (AllocationComponent component : AllocationComponent.values()) {
                state.put(component.name().toLowerCase(java.util.Locale.ROOT) + " due on instalment " + sequence,
                        item.due(component).minorUnits());
            }
        }
        BnplFeeAssessment fee = prelude.world().corrections.fees(prelude.p4()).get(0);
        state.put("room of instalment 3's fee", prelude.world().fakes.corrections.room(fee).minorUnits());
        state.put("status " + base.obligation(prelude.p4()).status(), 1L);
        return state;
    }

    /** The billed item of {@code instalment} as the due state reads it - by the billing's own instalment id. */
    private static BnplPurchaseStore.BilledItem billed(Prelude prelude, UUID instalment) {
        return prelude.world().fakes.purchases.billedItems(BnplApplicationFakes.UOW, prelude.p4().obligation()).stream()
                .filter(row -> row.instalment().equals(instalment)).findFirst().orElseThrow();
    }

    private static int indexOf(BnplRecord record, UUID reversal) {
        List<UUID> ids = new ArrayList<>();
        record.reversals().forEach(stamped -> ids.add(stamped.row().id()));
        return ids.indexOf(reversal);
    }
}
