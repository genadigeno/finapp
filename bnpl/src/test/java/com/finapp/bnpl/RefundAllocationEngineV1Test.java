package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.RefundAllocation.Cancellation;
import com.finapp.bnpl.RefundAllocation.Cancellation.Kind;
import com.finapp.bnpl.testing.ReferenceMerchantFeeReturn;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code REFUND_ALLOCATION_ENGINE_V1}'s golden cases (`P12-TSK-009`; ADR-0105 decisions 2-6 and its worked examples,
 * {@code PHASE_12_PLAN.md} sections 12.6 and 12.11's worked check; {@code INV-BNPL-06}, {@code -07}, {@code -09}).
 *
 * <p>The purchase throughout, unless a case says otherwise, is the plan's: pay-in-4, {@code G} = EUR 100.00, the fee
 * {@code f} = 4.00 (400 bps), four instalments of 25.00, the fee bound to the reference merchant arithmetic under
 * {@code HALF_EVEN} (A8) - {@code app}'s {@code BnplFeeArithmeticAgreesWithMerchantTest} holds it equal to merchant's.
 * Every expected figure is written out here, never read from the engine.
 */
@DisplayName("refund allocation engine v1: golden cases (P12-TSK-009)")
class RefundAllocationEngineV1Test {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money G = eur(100_00);
    private static final Money F = eur(4_00);
    private static final MerchantFeeReturn FEES = ReferenceMerchantFeeReturn.halfEven(G, F);

    // ------------------------------------------------------------------ the plan's worked examples

    @Test
    @DisplayName("the plan's worked check: instalment 1 paid, a full refund of 100.00 cancels instalments 4, 3, 2 at 25.00"
            + " each (unbilled), the excess 25.00 to the customer, the fee returned 4.00, the payable debited 96.00")
    void theWorkedCheck() {
        RefundAllocation split = allocated(position(eur(0), paid(1), unbilled(2), unbilled(3), unbilled(4)), eur(100_00));

        assertThat(split.engine()).isEqualTo(BnplEngine.REFUND_ALLOCATION_ENGINE_V1);
        assertThat(split.cancellations()).containsExactly(
                new Cancellation(4, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(3, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(2, Kind.UNBILLED, eur(25_00), true));
        assertThat(split.excess()).isEqualTo(eur(25_00));
        assertThat(split.feeReturned()).isEqualTo(eur(4_00));
        assertThat(split.payableDebit()).isEqualTo(eur(96_00));
        assertThat(split.cancelled(Kind.UNBILLED)).isEqualTo(eur(75_00));
        assertThat(split.cancelled(Kind.BILLED_UNPAID)).isEqualTo(eur(0));
        assertThat(split.whollyCancelled()).containsExactly(4, 3, 2);
        assertThat(split.leavesNoPrincipalUnpaid()).as("nothing stranded (INV-BNPL-09)").isTrue();
    }

    @Test
    @DisplayName("a full refund before anything is paid cancels all four instalments and leaves no excess - and no"
            + " principal unpaid, the fact pay-in-N's interest release reads (A11), with nothing going to the customer")
    void aFullRefundOfNothingPaidLeavesNothingOwedAndNoExcess() {
        RefundAllocation split = allocated(position(eur(0), billed(1), unbilled(2), unbilled(3), unbilled(4)),
                eur(100_00));

        assertThat(split.cancellations()).containsExactly(
                new Cancellation(4, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(3, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(2, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(1, Kind.BILLED_UNPAID, eur(25_00), true));
        assertThat(split.excess()).isEqualTo(eur(0));
        assertThat(split.leavesNoPrincipalUnpaid()).isTrue();
        assertThat(split.feeReturned()).isEqualTo(F);
    }

    @Test
    @DisplayName("a refund cancels latest first: 30.00 cancels instalment 4 whole and 5.00 of instalment 3 - never"
            + " instalment 2 while a later one owes principal; the fee returned cum(30) = 4.00 × 30 / 100 = 1.20, to the"
            + " cent")
    void refundCancelsLatestFirst() {
        RefundAllocation split = allocated(position(eur(0), paid(1), billed(2), unbilled(3), unbilled(4)), eur(30_00));

        assertThat(split.cancellations()).containsExactly(
                new Cancellation(4, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(3, Kind.UNBILLED, eur(5_00), false));
        assertThat(split.excess()).isEqualTo(eur(0));
        assertThat(split.feeReturned()).isEqualTo(eur(1_20));
        assertThat(split.payableDebit()).isEqualTo(eur(28_80));
        assertThat(split.whollyCancelled()).as("instalment 3 only shortened - its fees stand").containsExactly(4);
        assertThat(split.leavesNoPrincipalUnpaid()).isFalse();
    }

    @Test
    @DisplayName("then 70.00: instalment 3's remaining 20.00 (unbilled), instalment 2's 25.00 billed-unpaid (curing its"
            + " DPD), the excess 25.00; the fee cum(100) − cum(30) = 2.80, the payable debited 67.20 - ADR-0105's table"
            + " to the cent")
    void thenTheRest() {
        RefundPosition after30 = position(eur(30_00), paid(1), billed(2),
                instalment(3, false, 0, 5_00), instalment(4, false, 0, 25_00));

        RefundAllocation split = allocated(after30, eur(70_00));

        assertThat(split.cancellations()).containsExactly(
                new Cancellation(3, Kind.UNBILLED, eur(20_00), true),
                new Cancellation(2, Kind.BILLED_UNPAID, eur(25_00), true));
        assertThat(split.excess()).isEqualTo(eur(25_00));
        assertThat(split.feeReturned()).isEqualTo(eur(2_80));
        assertThat(split.payableDebit()).isEqualTo(eur(67_20));
        assertThat(split.cancelled(Kind.BILLED_UNPAID)).isEqualTo(eur(25_00));
        assertThat(split.leavesNoPrincipalUnpaid()).isTrue();
    }

    @Test
    @DisplayName("order independence: 70.00 then 30.00 returns cum(70) = 2.80 then 1.20 - the same 4.00 = f as 30.00 then"
            + " 70.00's 1.20 then 2.80")
    void theFeeIsOrderIndependent() {
        RefundAllocation first = allocated(position(eur(0), paid(1), unbilled(2), unbilled(3), unbilled(4)), eur(70_00));
        assertThat(first.feeReturned()).isEqualTo(eur(2_80));
        assertThat(first.cancellations()).containsExactly(
                new Cancellation(4, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(3, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(2, Kind.UNBILLED, eur(20_00), false));

        RefundAllocation second = allocated(position(eur(70_00), paid(1), instalment(2, false, 0, 20_00),
                instalment(3, false, 0, 25_00), instalment(4, false, 0, 25_00)), eur(30_00));
        assertThat(second.feeReturned()).isEqualTo(eur(1_20));
        assertThat(second.cancellations()).containsExactly(new Cancellation(2, Kind.UNBILLED, eur(5_00), true));
        assertThat(second.excess()).as("instalment 1's payment back").isEqualTo(eur(25_00));
        assertThat(first.feeReturned().plus(second.feeReturned())).isEqualTo(F);
        assertThat(first.payableDebit().plus(second.payableDebit())).as("Σ payable debits = G − f").isEqualTo(eur(96_00));
    }

    @Test
    @DisplayName("a partially paid instalment cancels only its unpaid part: instalment 2 billed with 10.00 paid, a full"
            + " refund cancels 25.00, 25.00 and 15.00 - and the 35.00 the customer had paid comes back")
    void aPartlyPaidInstalmentCancelsOnlyItsUnpaidPart() {
        RefundAllocation split = allocated(position(eur(0), paid(1), instalment(2, true, 10_00, 0),
                unbilled(3), unbilled(4)), eur(100_00));

        assertThat(split.cancellations()).containsExactly(
                new Cancellation(4, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(3, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(2, Kind.BILLED_UNPAID, eur(15_00), true));
        assertThat(split.excess()).isEqualTo(eur(35_00));
        assertThat(split.whollyCancelled()).as("instalment 2's whole unpaid principal is cancelled - its late fee"
                + " released (OQ-14)").containsExactly(4, 3, 2);
    }

    @Test
    @DisplayName("after every instalment is paid the whole refund is excess: 40.00 cancels nothing, 40.00 to the"
            + " customer, the fee cum(40) = 1.60, the payable debited 38.40 (OQ-15)")
    void afterCompletionTheWholeRefundIsExcess() {
        RefundAllocation split = allocated(position(eur(0), paid(1), paid(2), paid(3), paid(4)), eur(40_00));

        assertThat(split.cancellations()).isEmpty();
        assertThat(split.excess()).isEqualTo(eur(40_00));
        assertThat(split.feeReturned()).isEqualTo(eur(1_60));
        assertThat(split.payableDebit()).isEqualTo(eur(38_40));
        assertThat(split.whollyCancelled()).isEmpty();
        assertThat(split.leavesNoPrincipalUnpaid()).as("none was unpaid before - true, vacuously").isTrue();
    }

    @Test
    @DisplayName("a refund before anything is paid but instalment 1's remainder: billed-unpaid instalments are reached"
            + " only once every later one is exhausted")
    void billedInstalmentsAreReachedLast() {
        RefundAllocation split = allocated(position(eur(0), paid(1), billed(2), billed(3), unbilled(4)), eur(60_00));

        assertThat(split.cancellations()).containsExactly(
                new Cancellation(4, Kind.UNBILLED, eur(25_00), true),
                new Cancellation(3, Kind.BILLED_UNPAID, eur(25_00), true),
                new Cancellation(2, Kind.BILLED_UNPAID, eur(10_00), false));
        assertThat(split.cancelled(Kind.UNBILLED)).isEqualTo(eur(25_00));
        assertThat(split.cancelled(Kind.BILLED_UNPAID)).isEqualTo(eur(35_00));
        assertThat(split.excess()).isEqualTo(eur(0));
        assertThat(split.feeReturned()).as("cum(60) = 2.40").isEqualTo(eur(2_40));
    }

    @Test
    @DisplayName("a zero fee (pay-in-N's is optional, OQ-17) returns nothing - the payable is debited the whole refund")
    void aZeroFeeReturnsNothing() {
        RefundPosition position = new RefundPosition(G, eur(0), eur(0),
                List.of(paid(1), unbilled(2), unbilled(3), unbilled(4)));
        RefundAllocation split = allocated(position, eur(50_00), ReferenceMerchantFeeReturn.halfEven(G, eur(0)));

        assertThat(split.feeReturned()).isEqualTo(eur(0));
        assertThat(split.payableDebit()).isEqualTo(eur(50_00));
    }

    @Test
    @DisplayName("a one-cent fee refunded in halves under HALF_UP returns the cent once - cum(50.00) = round(0.005) ="
            + " 0.01, then cum(100.00) − 0.01 = 0.00 - where each half's own rounded share would return it twice")
    void aOneCentFeeIsReturnedOnce() {
        MerchantFeeReturn halfUp = new ReferenceMerchantFeeReturn(G, eur(1), RoundingPolicy.HALF_UP);
        RefundPosition first = new RefundPosition(G, eur(1), eur(0), List.of(paid(1), paid(2), paid(3), paid(4)));
        RefundPosition second = new RefundPosition(G, eur(1), eur(50_00), List.of(paid(1), paid(2), paid(3), paid(4)));

        Money a = allocated(first, eur(50_00), halfUp).feeReturned();
        Money b = allocated(second, eur(50_00), halfUp).feeReturned();

        assertThat(a).as("HALF_UP rounds the first half's 0.005 up").isEqualTo(eur(1));
        assertThat(b).as("the cumulative difference returns nothing more").isEqualTo(eur(0));
    }

    @Test
    @DisplayName("a zero-decimal currency (JPY): G = 10,001, f = 301, refunds of 3,333 then 6,668 return 100 then 201")
    void aZeroDecimalCurrency() {
        CurrencyCode jpy = CurrencyCode.of("JPY");
        Money gross = Money.ofMinorUnits(10_001, jpy);
        Money fee = Money.ofMinorUnits(301, jpy);
        MerchantFeeReturn fees = ReferenceMerchantFeeReturn.halfEven(gross, fee);
        List<RefundPosition.Instalment> instalments = List.of(
                new RefundPosition.Instalment(1, Money.ofMinorUnits(2_501, jpy), true, Money.ofMinorUnits(2_501, jpy),
                        Money.ofMinorUnits(0, jpy)),
                new RefundPosition.Instalment(2, Money.ofMinorUnits(2_500, jpy), false, Money.ofMinorUnits(0, jpy),
                        Money.ofMinorUnits(0, jpy)),
                new RefundPosition.Instalment(3, Money.ofMinorUnits(2_500, jpy), false, Money.ofMinorUnits(0, jpy),
                        Money.ofMinorUnits(0, jpy)),
                new RefundPosition.Instalment(4, Money.ofMinorUnits(2_500, jpy), false, Money.ofMinorUnits(0, jpy),
                        Money.ofMinorUnits(0, jpy)));

        RefundAllocation first = allocated(new RefundPosition(gross, fee, Money.ofMinorUnits(0, jpy), instalments),
                Money.ofMinorUnits(3_333, jpy), fees);
        assertThat(first.feeReturned()).as("301 × 3,333 / 10,001 = 100.3...").isEqualTo(Money.ofMinorUnits(100, jpy));
        assertThat(first.cancellations()).containsExactly(
                new Cancellation(4, Kind.UNBILLED, Money.ofMinorUnits(2_500, jpy), true),
                new Cancellation(3, Kind.UNBILLED, Money.ofMinorUnits(833, jpy), false));

        Money second = fees.returnedFee(Money.ofMinorUnits(3_333, jpy), Money.ofMinorUnits(6_668, jpy));
        assertThat(second).isEqualTo(Money.ofMinorUnits(201, jpy));
        assertThat(first.feeReturned().plus(second)).isEqualTo(fee);
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("a refund of zero or less is refused REFUND_NOT_POSITIVE")
    void aRefundOfNothingIsRefused() {
        RefundPosition position = position(eur(0), paid(1), unbilled(2), unbilled(3), unbilled(4));
        for (long minor : new long[] {0, -1, -100_00}) {
            assertThat(RefundAllocationEngineV1.allocate(position, eur(minor), FEES))
                    .isEqualTo(new RefundAllocationOutcome.Refused(RefundAllocationRefusal.REFUND_NOT_POSITIVE,
                            "the refund is positive"));
        }
    }

    @Test
    @DisplayName("a refund above what remains of the purchase is refused REFUND_EXCEEDS_PURCHASE - one cent above, and"
            + " any refund of a fully refunded purchase; exactly the remainder is admitted")
    void aRefundAboveTheRemainderIsRefused() {
        RefundPosition after30 = position(eur(30_00), paid(1), billed(2),
                instalment(3, false, 0, 5_00), instalment(4, false, 0, 25_00));
        assertThat(RefundAllocationEngineV1.allocate(after30, eur(70_01), FEES))
                .isInstanceOfSatisfying(RefundAllocationOutcome.Refused.class, refused ->
                        assertThat(refused.reason()).isEqualTo(RefundAllocationRefusal.REFUND_EXCEEDS_PURCHASE));
        assertThat(RefundAllocationEngineV1.allocate(after30, eur(70_00), FEES))
                .isInstanceOf(RefundAllocationOutcome.Allocated.class);

        RefundPosition full = position(eur(100_00), paid(1), instalment(2, false, 0, 25_00),
                instalment(3, false, 0, 25_00), instalment(4, false, 0, 25_00));
        assertThat(RefundAllocationEngineV1.allocate(full, eur(1), FEES))
                .isInstanceOfSatisfying(RefundAllocationOutcome.Refused.class, refused ->
                        assertThat(refused.reason()).isEqualTo(RefundAllocationRefusal.REFUND_EXCEEDS_PURCHASE));
    }

    @Test
    @DisplayName("a refund in another currency, or at another scale, is refused CURRENCY_MISMATCH - judged first")
    void aRefundInAnotherCurrencyIsRefused() {
        RefundPosition position = position(eur(0), paid(1), unbilled(2), unbilled(3), unbilled(4));
        for (Money refund : List.of(Money.ofMinorUnits(10_00, CurrencyCode.of("GBP")),
                Money.ofPersisted(10_000, EUR, 3), Money.ofMinorUnits(-1, CurrencyCode.of("USD")))) {
            assertThat(RefundAllocationEngineV1.allocate(position, refund, FEES))
                    .isInstanceOfSatisfying(RefundAllocationOutcome.Refused.class, refused ->
                            assertThat(refused.reason()).isEqualTo(RefundAllocationRefusal.CURRENCY_MISMATCH));
        }
    }

    @Test
    @DisplayName("a refusal's detail names the defect, never an amount")
    void aRefusalNamesNoAmount() {
        RefundPosition position = position(eur(0), paid(1), unbilled(2), unbilled(3), unbilled(4));
        RefundAllocationOutcome outcome = RefundAllocationEngineV1.allocate(position, eur(100_01), FEES);
        assertThat(((RefundAllocationOutcome.Refused) outcome).detail()).doesNotContain("100").doesNotContain("EUR");
    }

    // ------------------------------------------------------------------ the fee binding's properties

    @Test
    @DisplayName("a fee binding that rounds each refund's own share is a defect: refunds completing the purchase that do"
            + " not return exactly f are refused loudly, never posted")
    void aBindingThatDoesNotTelescopeIsADefect() {
        // f = 1 cent on G = 1.00 under HALF_EVEN: each half's own share round(0.005) = 0.00 - the cent never returned.
        Money gross = eur(1_00);
        Money fee = eur(1);
        ReferenceMerchantFeeReturn reference = ReferenceMerchantFeeReturn.halfEven(gross, fee);
        MerchantFeeReturn ownShare = (before, refunded) -> reference.cumulative(refunded);
        RefundPosition second = new RefundPosition(gross, fee, eur(50),
                List.of(new RefundPosition.Instalment(1, gross, true, gross, eur(0))));

        assertThatThrownBy(() -> RefundAllocationEngineV1.allocate(second, eur(50), ownShare))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("telescope");
        assertThat(allocated(second, eur(50), reference).feeReturned()).as("the cumulative difference").isEqualTo(fee);
    }

    @Test
    @DisplayName("a fee binding answering more than the refund, a negative figure, more than f in total or another"
            + " currency is a defect")
    void aBindingOutsideItsBoundsIsADefect() {
        RefundPosition position = position(eur(0), paid(1), unbilled(2), unbilled(3), unbilled(4));
        List<MerchantFeeReturn> wrong = List.of(
                (before, refunded) -> refunded.plus(eur(1)),
                (before, refunded) -> eur(-1),
                (before, refunded) -> eur(4_01),
                (before, refunded) -> Money.ofMinorUnits(1, CurrencyCode.of("GBP")));
        for (MerchantFeeReturn binding : wrong) {
            assertThatThrownBy(() -> RefundAllocationEngineV1.allocate(position, eur(10_00), binding))
                    .isInstanceOf(IllegalStateException.class);
        }
        RefundPosition after = position(eur(10_00), paid(1), unbilled(2), unbilled(3), instalment(4, false, 0, 10_00));
        MerchantFeeReturn tooMuchBefore = (before, refunded) -> before.isZero() ? eur(4_00) : eur(1);
        assertThatThrownBy(() -> RefundAllocationEngineV1.allocate(after, eur(10_00), tooMuchBefore))
                .as("the purchase's refunds together return more than f").isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ the values

    @Test
    @DisplayName("an inconsistent position does not exist: principal not summing to G, an instalment over-settled, an"
            + " unbilled one paid, more cancelled than refunded, f at or above G, refunds above G, numbering out of order, a"
            + " figure in another currency or scale - each an IllegalArgumentException")
    void anInconsistentPositionDoesNotExist() {
        List<Runnable> inconsistent = List.of(
                () -> position(eur(0), paid(1), unbilled(2), unbilled(3)),
                () -> position(eur(0), instalment(1, true, 25_00, 1), unbilled(2), unbilled(3), unbilled(4)),
                () -> position(eur(0), paid(1), instalment(2, false, 1, 0), unbilled(3), unbilled(4)),
                () -> position(eur(10_00), paid(1), unbilled(2), unbilled(3), instalment(4, false, 0, 10_01)),
                () -> new RefundPosition(G, G, eur(0), List.of(paid(1), unbilled(2), unbilled(3), unbilled(4))),
                () -> new RefundPosition(G, eur(-1), eur(0), List.of(paid(1), unbilled(2), unbilled(3), unbilled(4))),
                () -> position(eur(100_01), paid(1), unbilled(2), unbilled(3), unbilled(4)),
                () -> position(eur(0), paid(1), unbilled(3), unbilled(2), unbilled(4)),
                () -> new RefundPosition(eur(0), eur(0), eur(0), List.of()),
                () -> position(eur(0), instalment(1, true, -1, 0), unbilled(2), unbilled(3), unbilled(4)),
                () -> position(eur(0), paid(1), new RefundPosition.Instalment(2, eur(25_00), false, eur(0),
                        Money.ofMinorUnits(0, CurrencyCode.of("GBP"))), unbilled(3), unbilled(4)),
                () -> new RefundPosition(G, F, Money.ofPersisted(0, EUR, 3),
                        List.of(paid(1), unbilled(2), unbilled(3), unbilled(4))));
        for (Runnable construction : inconsistent) {
            assertThatThrownBy(construction::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a split that does not preserve value does not exist: Σ cancelled + excess one cent off R, the fee above"
            + " R, a payable debit that is not R − r_f, cancellations out of order or of nothing, a figure in another"
            + " currency or scale")
    void aSplitThatDoesNotConserveDoesNotExist() {
        Cancellation four = new Cancellation(4, Kind.UNBILLED, eur(25_00), true);
        Cancellation three = new Cancellation(3, Kind.UNBILLED, eur(5_00), false);
        BnplEngine v1 = BnplEngine.REFUND_ALLOCATION_ENGINE_V1;
        List<Runnable> wrong = List.of(
                () -> new RefundAllocation(v1, eur(30_00), List.of(four, three), eur(1), eur(1_00), eur(29_00), false),
                () -> new RefundAllocation(v1, eur(30_00), List.of(four), eur(5_00), eur(30_01), eur(-1), false),
                () -> new RefundAllocation(v1, eur(30_00), List.of(four, three), eur(0), eur(1_00), eur(30_00), false),
                () -> new RefundAllocation(v1, eur(30_00), List.of(three, four), eur(0), eur(1_00), eur(29_00), false),
                () -> new RefundAllocation(BnplEngine.BNPL_SCHEDULE_ENGINE_V1, eur(30_00), List.of(four, three),
                        eur(0), eur(1_00), eur(29_00), false),
                () -> new Cancellation(2, Kind.UNBILLED, eur(0), true),
                () -> new RefundAllocation(v1, eur(30_00), List.of(four, new Cancellation(3, Kind.UNBILLED,
                        Money.ofMinorUnits(5_00, CurrencyCode.of("GBP")), false)), eur(0), eur(1_00), eur(29_00), false),
                () -> new RefundAllocation(v1, eur(30_00), List.of(four, three), eur(0),
                        Money.ofPersisted(1_000, EUR, 3), eur(29_00), false));
        for (Runnable construction : wrong) {
            assertThatThrownBy(construction::run).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new RefundAllocation(v1, eur(30_00), List.of(four, three), eur(0), eur(1_00), eur(29_00), false))
                .as("the conserving one exists").isNotNull();
    }

    @Test
    @DisplayName("the values name no amount: the split its engine and sequences, the position its currency and count")
    void theValuesNameNoAmount() {
        RefundPosition position = position(eur(0), paid(1), unbilled(2), unbilled(3), unbilled(4));
        RefundAllocation split = allocated(position, eur(100_00));
        assertThat(split.toString()).isEqualTo("RefundAllocation[REFUND_ALLOCATION_ENGINE_V1, cancels [4, 3, 2]]");
        assertThat(position.toString()).isEqualTo("RefundPosition[EUR, 4 instalments]");
        assertThat(split.cancellations().get(0).toString()).isEqualTo("Cancellation[4, UNBILLED, whole]");
        assertThat(position.instalments().get(0).toString()).isEqualTo("Instalment[1, billed]");
        assertThat(ReferenceMerchantFeeReturn.halfEven(G, F).toString()).doesNotContain("100").doesNotContain("4.00");
    }

    // ------------------------------------------------------------------ helpers

    private static RefundAllocation allocated(RefundPosition position, Money refund) {
        return allocated(position, refund, FEES);
    }

    private static RefundAllocation allocated(RefundPosition position, Money refund, MerchantFeeReturn fees) {
        RefundAllocationOutcome outcome = RefundAllocationEngineV1.allocate(position, refund, fees);
        assertThat(outcome).isInstanceOf(RefundAllocationOutcome.Allocated.class);
        return ((RefundAllocationOutcome.Allocated) outcome).allocation();
    }

    /** The plan's purchase - G = 100.00, f = 4.00 - with {@code refundedBefore} and the instalments given. */
    private static RefundPosition position(Money refundedBefore, RefundPosition.Instalment... instalments) {
        return new RefundPosition(G, F, refundedBefore, new ArrayList<>(List.of(instalments)));
    }

    private static RefundPosition.Instalment paid(int sequence) {
        return instalment(sequence, true, 25_00, 0);
    }

    private static RefundPosition.Instalment billed(int sequence) {
        return instalment(sequence, true, 0, 0);
    }

    private static RefundPosition.Instalment unbilled(int sequence) {
        return instalment(sequence, false, 0, 0);
    }

    private static RefundPosition.Instalment instalment(int sequence, boolean billed, long allocated, long cancelled) {
        return new RefundPosition.Instalment(sequence, eur(25_00), billed, eur(allocated), eur(cancelled));
    }

    private static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, EUR);
    }
}
