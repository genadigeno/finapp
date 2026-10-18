package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPayoutWorld.PAID;
import static com.finapp.app.bnpl.BnplPayoutWorld.PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.HoldStatus;
import com.finapp.merchant.MerchantPayable;
import com.finapp.merchant.MerchantPayoutOutcomes;
import com.finapp.merchant.MerchantPayoutResolution;
import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.merchant.MerchantPayoutUnfundedException;
import com.finapp.merchant.MerchantPayouts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Payouts of BNPL-financed money through the existing on-demand payout ONLY (`P12-TSK-016`; {@code PHASE_12_PLAN.md}
 * §2.1 - bnpl never pays a merchant itself; mandatory scenario 5; {@code INV-MER-05}, {@code INV-BAL-04},
 * {@code INV-PAY-04}, {@code INV-IDEM-01}, {@code INV-MER-07} as amended by B7) - hermetic, over {@link BnplPayoutWorld}:
 * merchant's real payout, outcome and resolution code, the ledger's real hold and posting services, the platform's real
 * idempotent executor and the real payout adapter on the scheme simulator; only the stores are in memory.
 *
 * <p>The payout code is unchanged by this task. What is proven is that a payable F1 credited is paid out by it exactly
 * as a captured one is - bounded by the payable whatever funded it, a lost answer held and never re-sent, concluded
 * once - and that the financing it was paid out of is never touched. The races themselves (ten instances, two
 * resolvers, a payout against a held acceptance) are {@code BnplMerchantPayoutDatabaseTest}'s.
 */
@DisplayName("payouts of BNPL-financed money through the existing payout (P12-TSK-016)")
class BnplMerchantPayoutTest {

    private static final long GROSS = 600_00L;
    private static final long FEE = 24_00L;

    private BnplPayoutWorld world;

    @BeforeEach
    void world() {
        world = new BnplPayoutWorld();
    }

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("scenario 5 - the scheme executes the send and the answer is lost: UNKNOWN with its hold standing; ten"
            + " retries under the same key replay the recorded answer and dispatch nothing; the query concludes"
            + " COMPLETED and posts DR MERCHANT_PAYABLE / CR PAYOUT_CLEARING once; one payout at the provider, the"
            + " dispatch counted equal on both sides; the financing untouched")
    void aLostPayoutResponseIsHeldAndTheRetryReplays() {
        BnplPayoutWorld.Shop shop = world.shop();
        UUID purchase = world.financed(shop, GROSS, FEE);
        List<String> financing = world.entryLines(MerchantPayable.BNPL_FINANCING_NAMESPACE + purchase);
        world.scheme.receivesTheRequestThenLosesTheResponse(PATH);
        String key = "payout-" + world.ids.next();

        MerchantPayouts.Initiated lost = world.payOut(shop, 500_00L, key);

        assertThat(lost.status()).isEqualTo(MerchantPayoutStatus.UNKNOWN);
        assertThat(lost.replayed()).isFalse();
        assertThat(world.holdStatuses(shop)).as("UNKNOWN is not terminal: the hold stands").containsExactly(HoldStatus.ACTIVE);
        assertThat(world.positionMinor(shop)).as("a hold is not a posting").isEqualTo(GROSS - FEE);
        assertThat(world.availableMinor(shop)).isEqualTo(GROSS - FEE - 500_00L);
        assertThatThrownBy(() -> world.payOut(shop, 100_00L, "payout-" + world.ids.next()))
                .as("but it narrows what the next payout may take (INV-MER-05, cumulative)")
                .isInstanceOf(MerchantPayoutUnfundedException.class);

        List<MerchantPayouts.Initiated> retries = new ArrayList<>();
        for (int retry = 0; retry < 10; retry++) {
            retries.add(world.payOut(shop, 500_00L, key));
        }
        assertThat(retries).allSatisfy(retried -> {
            assertThat(retried.replayed()).as("the retry replays the recorded answer").isTrue();
            assertThat(retried.status()).isEqualTo(MerchantPayoutStatus.UNKNOWN);
            assertThat(retried.payout()).isEqualTo(lost.payout());
        });
        assertCountedOnBothSides(shop, 1);

        world.queryAnswers(lost.payout(), "paid");
        MerchantPayoutResolution.SweepResult swept = world.resolver().sweep();
        assertThat(swept.actingJudgements()).containsExactly(MerchantPayoutStatus.COMPLETED);
        assertThat(world.stored(lost.payout()).status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(world.holdStatuses(shop)).containsExactly(HoldStatus.RELEASED);
        assertThat(world.entryLines(MerchantPayoutOutcomes.POSTING_KEY_PREFIX + lost.payout().value()))
                .containsExactlyInAnyOrder("DEBIT:MERCHANT_PAYABLE:50000", "CREDIT:PAYOUT_CLEARING:50000");
        assertThat(world.wire.queries.get()).as("the conclusion asked; it never sent").isEqualTo(1);

        MerchantPayoutResolution.SweepResult again = world.resolver().sweep();
        assertThat(again.candidates()).as("a concluded payout is no candidate").isZero();
        assertThat(world.payOut(shop, 500_00L, key).status())
                .as("a retry after the conclusion still answers the judgement it was given")
                .isEqualTo(MerchantPayoutStatus.UNKNOWN);
        assertCountedOnBothSides(shop, 1);
        assertThat(world.entriesUnder(MerchantPayoutOutcomes.POSTING_KEY_PREFIX)).as("posted once").isEqualTo(1);
        assertThat(world.state.expectations).as("one MERCHANT_PAYOUT expectation, the acting exit's").hasSize(1);

        MerchantPayable.Payable payable = world.payable(shop);
        assertThat(payable.bnplFinanced().minorUnits()).isEqualTo(GROSS);
        assertThat(payable.bnplFees().minorUnits()).isEqualTo(FEE);
        assertThat(payable.paidOut().minorUnits()).isEqualTo(500_00L);
        assertThat(payable.position().minorUnits()).isEqualTo(GROSS - FEE - 500_00L);
        assertThat(payable.terms()).isEqualTo(payable.position());
        assertThat(world.entryLines(MerchantPayable.BNPL_FINANCING_NAMESPACE + purchase))
                .as("the financing it was paid out of is untouched").isEqualTo(financing);
        assertThat(world.entriesUnder("bnpl.")).as("the payout posted nothing of bnpl's").isEqualTo(1);
    }

    @Test
    @DisplayName("a payout of financed money is bounded by the payable whatever funded it: a card capture plus a"
            + " financing pays out to the minor unit and not one more, a refusal writes nothing and leaves the key"
            + " unspent; a BNPL refund after the payout leaves the payable below zero by at most that purchase's net"
            + " (B7), every payout refused until a later financing recovers it")
    void aPayoutOfFinancedMoneyIsBoundedByThePayable() {
        BnplPayoutWorld.Shop shop = world.shop();
        world.captured(shop, 100_00L, 3_00L);
        world.financed(shop, GROSS, FEE);
        world.scheme.succeedsWith(PATH, 200, PAID);
        long owed = 100_00L - 3_00L + GROSS - FEE;
        String key = "payout-" + world.ids.next();

        assertThatThrownBy(() -> world.payOut(shop, owed + 1, key)).isInstanceOf(MerchantPayoutUnfundedException.class);
        assertThat(world.payoutRows(shop)).as("the refusal rolled the dispatch back").isZero();
        assertThat(world.holdStatuses(shop)).isEmpty();
        assertThat(world.wire.sends.get()).isZero();
        assertThat(world.payOut(shop, owed, key).status())
                .as("the refused key was never spent: the same key pays the payable to the minor unit")
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(world.positionMinor(shop)).isZero();
        assertThatThrownBy(() -> world.payOut(shop, 1, "payout-" + world.ids.next()))
                .isInstanceOf(MerchantPayoutUnfundedException.class);

        UUID refund = world.refundedByBnpl(shop, GROSS, 150_00L, FEE);
        MerchantPayable.Payable indebted = world.payable(shop);
        assertThat(indebted.position().minorUnits())
                .as("B7: below zero by at most the purchase's own net, G - f").isEqualTo(-(GROSS - FEE));
        assertThat(indebted.bnplRefunded().minorUnits()).isEqualTo(GROSS);
        assertThat(indebted.bnplFeesReturned().minorUnits()).isEqualTo(FEE);
        assertThat(indebted.refunded().minorUnits()).as("never a wallet capture's refund").isZero();
        assertThat(indebted.terms()).isEqualTo(indebted.position());
        assertThat(refund).isNotNull();
        assertThatThrownBy(() -> world.payOut(shop, 1, "payout-" + world.ids.next()))
                .as("a payable below zero refuses every payout").isInstanceOf(MerchantPayoutUnfundedException.class);

        world.financed(shop, 1_000_00L, 40_00L);
        long recovered = 1_000_00L - 40_00L - (GROSS - FEE);
        assertThat(world.positionMinor(shop)).as("the debt recovered from the later financing first").isEqualTo(recovered);
        assertThatThrownBy(() -> world.payOut(shop, recovered + 1, "payout-" + world.ids.next()))
                .isInstanceOf(MerchantPayoutUnfundedException.class);
        assertThat(world.payOut(shop, recovered, "payout-" + world.ids.next()).status())
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(world.positionMinor(shop)).isZero();
        assertCountedOnBothSides(shop, 2);
    }

    @Test
    @DisplayName("a payout and a financing on one payable, both orders: the financing first - the payout may take"
            + " it; the payout first - bounded by what it found, the financing's credit never borrowed in advance")
    void aPayoutAndAFinancingAreBoundedByWhatEachFound() {
        world.scheme.succeedsWith(PATH, 200, PAID);
        BnplPayoutWorld.Shop financedFirst = world.shop();
        world.captured(financedFirst, 100_00L, 3_00L);
        world.financed(financedFirst, GROSS, FEE);
        assertThat(world.payOut(financedFirst, 97_00L + GROSS - FEE, "payout-" + world.ids.next()).status())
                .isEqualTo(MerchantPayoutStatus.COMPLETED);

        BnplPayoutWorld.Shop payoutFirst = world.shop();
        world.captured(payoutFirst, 100_00L, 3_00L);
        assertThatThrownBy(() -> world.payOut(payoutFirst, 97_00L + 1, "payout-" + world.ids.next()))
                .isInstanceOf(MerchantPayoutUnfundedException.class);
        MerchantPayouts.Initiated before = world.payOut(payoutFirst, 97_00L, "payout-" + world.ids.next());
        world.financed(payoutFirst, GROSS, FEE);
        assertThat(world.positionMinor(payoutFirst)).isEqualTo(GROSS - FEE);
        assertThat(world.payOut(payoutFirst, GROSS - FEE, "payout-" + world.ids.next()).status())
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(before.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(world.positionMinor(payoutFirst)).isZero();
        assertThat(world.payable(payoutFirst).terms()).isEqualTo(world.payable(payoutFirst).position());
    }

    @Test
    @DisplayName("ten retries of one key: one payout row, one send by the adapter, one request at the scheme, one"
            + " posting - nine replays of the first answer")
    void tenRetriesOneDispatch() {
        BnplPayoutWorld.Shop shop = world.shop();
        world.financed(shop, GROSS, FEE);
        world.scheme.succeedsWith(PATH, 200, PAID);
        String key = "payout-" + world.ids.next();
        List<MerchantPayouts.Initiated> answers = new ArrayList<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            answers.add(world.payOut(shop, 300_00L, key));
        }
        assertThat(answers).extracting(MerchantPayouts.Initiated::status).containsOnly(MerchantPayoutStatus.COMPLETED);
        assertThat(answers).filteredOn(MerchantPayouts.Initiated::replayed).hasSize(9);
        assertThat(answers).extracting(MerchantPayouts.Initiated::payout).containsOnly(answers.get(0).payout());
        assertCountedOnBothSides(shop, 1);
        assertThat(world.entriesUnder(MerchantPayoutOutcomes.POSTING_KEY_PREFIX)).isEqualTo(1);
        assertThat(world.positionMinor(shop)).isEqualTo(GROSS - FEE - 300_00L);
    }

    @Test
    @DisplayName("two resolvers conclude once: the second finds nothing to judge - one acting COMPLETED, one posting,"
            + " one completion event, DISPATCHED -> UNKNOWN and UNKNOWN -> COMPLETED each recorded once")
    void twoResolversConcludeOnce() {
        BnplPayoutWorld.Shop shop = world.shop();
        world.financed(shop, GROSS, FEE);
        world.scheme.receivesTheRequestThenLosesTheResponse(PATH);
        MerchantPayouts.Initiated lost = world.payOut(shop, 400_00L, "payout-" + world.ids.next());
        world.queryAnswers(lost.payout(), "paid");

        MerchantPayoutResolution first = world.resolver();
        MerchantPayoutResolution second = world.resolver();
        List<MerchantPayoutStatus> judged = new ArrayList<>(first.sweep().actingJudgements());
        judged.addAll(second.sweep().actingJudgements());

        assertThat(judged).containsExactly(MerchantPayoutStatus.COMPLETED);
        assertThat(world.entriesUnder(MerchantPayoutOutcomes.POSTING_KEY_PREFIX)).isEqualTo(1);
        assertThat(world.events("merchant.MerchantPayoutCompleted", lost.payout())).isEqualTo(1);
        assertThat(world.audited("merchant.MerchantPayoutOutcomeApplied", lost.payout())).isEqualTo(2);
        assertCountedOnBothSides(shop, 1);
    }

    @Test
    @DisplayName("a failed payout releases its hold and posts nothing: declined at the send - FAILED, the hold"
            + " released, the payable whole again and paid out in full by the next payout; concluded declined by the"
            + " query after a lost answer - the same")
    void aFailedPayoutReleasesItsHold() {
        BnplPayoutWorld.Shop shop = world.shop();
        world.financed(shop, GROSS, FEE);
        world.scheme.succeedsWith(PATH, 200, "{\"status\":\"declined\"}");

        MerchantPayouts.Initiated declined = world.payOut(shop, 500_00L, "payout-" + world.ids.next());
        assertThat(declined.status()).isEqualTo(MerchantPayoutStatus.FAILED);
        assertThat(world.holdStatuses(shop)).containsExactly(HoldStatus.RELEASED);
        assertThat(world.entriesUnder(MerchantPayoutOutcomes.POSTING_KEY_PREFIX)).isZero();
        assertThat(world.availableMinor(shop)).isEqualTo(GROSS - FEE);

        world.scheme.reset();
        world.scheme.receivesTheRequestThenLosesTheResponse(PATH);
        MerchantPayouts.Initiated lost = world.payOut(shop, GROSS - FEE, "payout-" + world.ids.next());
        assertThat(lost.status()).isEqualTo(MerchantPayoutStatus.UNKNOWN);
        world.queryAnswers(lost.payout(), "declined");
        assertThat(world.resolver().sweep().actingJudgements()).containsExactly(MerchantPayoutStatus.FAILED);
        assertThat(world.holdStatuses(shop)).containsOnly(HoldStatus.RELEASED);
        assertThat(world.positionMinor(shop)).isEqualTo(GROSS - FEE);

        world.scheme.reset();
        world.scheme.succeedsWith(PATH, 200, PAID);
        assertThat(world.payOut(shop, GROSS - FEE, "payout-" + world.ids.next()).status())
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(world.positionMinor(shop)).isZero();
    }

    /**
     * The dispatch counted on both sides: the platform's payout rows and its adapter's send calls, and the scheme's
     * received sends (the Phase 11 exit review's owner item #16 applied forward).
     */
    private void assertCountedOnBothSides(BnplPayoutWorld.Shop shop, int payouts) {
        assertThat(world.payoutRows(shop)).as("merchant_payout rows").isEqualTo(payouts);
        assertThat(world.wire.sends.get()).as("the adapter's send calls").isEqualTo(payouts);
        assertThat(world.scheme.requestCount(PATH)).as("the scheme's received sends").isEqualTo(payouts);
    }
}
