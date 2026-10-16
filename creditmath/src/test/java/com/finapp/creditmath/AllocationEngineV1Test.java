package com.finapp.creditmath;

import static com.finapp.creditmath.AllocationComponent.FEES;
import static com.finapp.creditmath.AllocationComponent.INTEREST;
import static com.finapp.creditmath.AllocationComponent.PRINCIPAL;
import static com.finapp.creditmath.EngineTermsFixtures.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AllocationEngineV1.AllocationRefused;
import com.finapp.creditmath.AllocationEngineV1.AllocationRefused.Reason;
import com.finapp.creditmath.DueState.DueItem;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code ALLOCATION_ENGINE_V1} case by case (`P11-TSK-008`; ADR-0095, {@code PHASE_11_PLAN.md} section 12.5,
 * {@code INV-LND-04}): every order the vocabulary admits; full, partial, over, zero-due and early payments; both
 * overpayment treatments; the credit-balance application and the prepayment; and every typed refusal.
 *
 * <p><strong>Moved with the engine</strong> (`P12-TSK-002`): from {@code lending}, every case and figure unedited; the
 * loan's and the line's rules built through {@link AllocationRules}' canonical constructor ({@link EngineTermsFixtures})
 * instead of lending's {@code LoanTerms}, whose mapping - and its terms accepting every order - is lending's
 * {@code LoanEnginesTest}'s.
 */
@DisplayName("ALLOCATION_ENGINE_V1 (P11-TSK-008)")
class AllocationEngineV1Test {

    static final DueItemId JANUARY = item(1);
    static final DueItemId FEBRUARY = item(2);
    static final LocalDate JAN_15 = LocalDate.of(2027, 1, 15);
    static final LocalDate FEB_15 = LocalDate.of(2027, 2, 15);

    /** The loan's rule as the plan's terms carry it: fees, interest, principal; the remainder held as credit. */
    static final AllocationRules LOAN = EngineTermsFixtures.loanRules();

    /** The line's: fees, interest, principal; the remainder paying down drawn principal, then held (A26). */
    static final AllocationRules LINE = EngineTermsFixtures.lineRules();

    // ------------------------------------------------------------------ every order

    /** Every order the vocabulary admits - each permutation of the three components, as {@code LoanTerms} accepts. */
    static List<List<AllocationComponent>> everyOrder() {
        List<List<AllocationComponent>> orders = new ArrayList<>();
        for (AllocationComponent a : AllocationComponent.values()) {
            for (AllocationComponent b : AllocationComponent.values()) {
                for (AllocationComponent c : AllocationComponent.values()) {
                    if (a != b && b != c && a != c) {
                        orders.add(List.of(a, b, c));
                    }
                }
            }
        }
        return orders;
    }

    @Test
    @DisplayName("the vocabulary admits six orders, and the rules accept each (lending's terms accepting each: its"
            + " LoanEnginesTest, P12-TSK-002)")
    void sixOrders() {
        assertThat(everyOrder()).hasSize(6);
        for (List<AllocationComponent> order : everyOrder()) {
            assertThat(withOrder(LOAN, order).order()).isEqualTo(order);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyOrder")
    @DisplayName("a partial payment pays an item's components in the pinned order, each in full before the next")
    void partialPaymentFollowsThePinnedOrder(List<AllocationComponent> order) {
        DueItem january = new DueItem(JANUARY, JAN_15, eur(10_00), eur(20_00), eur(70_00));
        // 10 + 20 + 70 = 100 due; pay the first component, the second, and 1.00 of the third.
        long first = january.due(order.get(0)).minorUnits();
        long second = january.due(order.get(1)).minorUnits();
        Money amount = eur(first + second + 1_00);

        for (AllocationRules rules : List.of(withOrder(LOAN, order), withOrder(LINE, order))) {
            List<AllocationLine> lines = AllocationEngineV1.allocate(amount, state(eur(500_00), january), rules);
            assertThat(lines).containsExactly(
                    AllocationLine.due(JANUARY, order.get(0), eur(first)),
                    AllocationLine.due(JANUARY, order.get(1), eur(second)),
                    AllocationLine.due(JANUARY, order.get(2), eur(1_00)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyOrder")
    @DisplayName("whatever the order, the older item is paid in full before the newer is touched")
    void oldestFirstUnderEveryOrder(List<AllocationComponent> order) {
        DueItem january = new DueItem(JANUARY, JAN_15, eur(5_00), eur(15_00), eur(80_00));
        DueItem february = new DueItem(FEBRUARY, FEB_15, eur(0), eur(14_00), eur(86_00));
        // February listed first: the engine orders the items itself.
        List<AllocationLine> lines = AllocationEngineV1.allocate(eur(101_00),
                new DueState(List.of(february, january), eur(800_00)), withOrder(LOAN, order));

        List<AllocationLine> expected = new ArrayList<>();
        for (AllocationComponent component : order) {
            expected.add(AllocationLine.due(JANUARY, component, january.due(component)));
        }
        AllocationComponent firstPaidOfFebruary = order.stream().filter(c -> c != FEES).findFirst().orElseThrow();
        expected.add(AllocationLine.due(FEBRUARY, firstPaidOfFebruary, eur(1_00)));
        assertThat(lines).containsExactlyElementsOf(expected);
    }

    // ------------------------------------------------------------------ full, partial, over, zero-due, early

    @Test
    @DisplayName("a full instalment is paid exactly - fees, interest, principal - and nothing overflows")
    void fullInstalment() {
        DueItem january = new DueItem(JANUARY, JAN_15, eur(15_00), eur(37_47), eur(176_00));
        assertThat(AllocationEngineV1.allocate(eur(228_47), state(eur(4_000_00), january), LOAN)).containsExactly(
                AllocationLine.due(JANUARY, FEES, eur(15_00)),
                AllocationLine.due(JANUARY, INTEREST, eur(37_47)),
                AllocationLine.due(JANUARY, PRINCIPAL, eur(176_00)));
    }

    @Test
    @DisplayName("a partial payment stops inside a component; what is left stays due")
    void partial() {
        DueItem january = new DueItem(JANUARY, JAN_15, eur(15_00), eur(37_47), eur(176_00));
        assertThat(AllocationEngineV1.allocate(eur(40_00), state(eur(4_000_00), january), LOAN)).containsExactly(
                AllocationLine.due(JANUARY, FEES, eur(15_00)),
                AllocationLine.due(JANUARY, INTEREST, eur(25_00)));
    }

    @Test
    @DisplayName("over, on a loan: the remainder is held as credit - never principal not yet due (L6)")
    void overOnALoanIsHeldAsCredit() {
        DueItem january = new DueItem(JANUARY, JAN_15, eur(0), eur(30_00), eur(170_00));
        assertThat(AllocationEngineV1.allocate(eur(250_00), state(eur(4_000_00), january), LOAN)).containsExactly(
                AllocationLine.due(JANUARY, INTEREST, eur(30_00)),
                AllocationLine.due(JANUARY, PRINCIPAL, eur(170_00)),
                AllocationLine.creditBalance(eur(50_00)));
    }

    @Test
    @DisplayName("over, on a line: the remainder pays down drawn principal, restoring the limit (A26)")
    void overOnALinePaysDownPrincipal() {
        DueItem statement = new DueItem(JANUARY, JAN_15, eur(0), eur(12_00), eur(30_00));
        assertThat(AllocationEngineV1.allocate(eur(250_00), state(eur(900_00), statement), LINE)).containsExactly(
                AllocationLine.due(JANUARY, INTEREST, eur(12_00)),
                AllocationLine.due(JANUARY, PRINCIPAL, eur(30_00)),
                AllocationLine.notDuePrincipal(eur(208_00)));
    }

    @Test
    @DisplayName("over every receivable, on a line: only what exceeds drawn principal is held as credit (A26)")
    void overEveryReceivableOnALine() {
        DueItem statement = new DueItem(JANUARY, JAN_15, eur(15_00), eur(12_00), eur(30_00));
        assertThat(AllocationEngineV1.allocate(eur(1_000_00), state(eur(900_00), statement), LINE)).containsExactly(
                AllocationLine.due(JANUARY, FEES, eur(15_00)),
                AllocationLine.due(JANUARY, INTEREST, eur(12_00)),
                AllocationLine.due(JANUARY, PRINCIPAL, eur(30_00)),
                AllocationLine.notDuePrincipal(eur(900_00)),
                AllocationLine.creditBalance(eur(43_00)));
    }

    @Test
    @DisplayName("zero due: a paid item is skipped, and a zero amount allocates nothing")
    void zeroDue() {
        DueItem paid = new DueItem(JANUARY, JAN_15, eur(0), eur(0), eur(0));
        DueItem february = new DueItem(FEBRUARY, FEB_15, eur(0), eur(10_00), eur(90_00));
        assertThat(AllocationEngineV1.allocate(eur(50_00), state(eur(800_00), paid, february), LOAN)).containsExactly(
                AllocationLine.due(FEBRUARY, INTEREST, eur(10_00)),
                AllocationLine.due(FEBRUARY, PRINCIPAL, eur(40_00)));
        assertThat(AllocationEngineV1.allocate(eur(0), state(eur(800_00), february), LOAN)).isEmpty();
        assertThat(AllocationEngineV1.allocate(eur(0), DueState.nothingBilled(eur(0)), LINE)).isEmpty();
    }

    @Test
    @DisplayName("early, before any billing: held as credit on a loan; paid down on a line")
    void early() {
        assertThat(AllocationEngineV1.allocate(eur(100_00), DueState.nothingBilled(eur(5_000_00)), LOAN))
                .containsExactly(AllocationLine.creditBalance(eur(100_00)));
        assertThat(AllocationEngineV1.allocate(eur(100_00), DueState.nothingBilled(eur(5_000_00)), LINE))
                .containsExactly(AllocationLine.notDuePrincipal(eur(100_00)));
        assertThat(AllocationEngineV1.allocate(eur(100_00), DueState.nothingBilled(eur(0)), LINE))
                .as("a line with nothing drawn holds it all")
                .containsExactly(AllocationLine.creditBalance(eur(100_00)));
    }

    @Test
    @DisplayName("the credit-balance application at billing is the same engine: what is not applied stays held (rule 6)")
    void creditBalanceApplication() {
        DueItem january = new DueItem(JANUARY, JAN_15, eur(0), eur(30_00), eur(170_00));
        // A credit balance of 50.00 applied to a fresh instalment of 200.00 - all of it applied, nothing stays.
        assertThat(AllocationEngineV1.allocate(eur(50_00), state(eur(4_000_00), january), LOAN)).containsExactly(
                AllocationLine.due(JANUARY, INTEREST, eur(30_00)),
                AllocationLine.due(JANUARY, PRINCIPAL, eur(20_00)));
        // A credit balance of 250.00: 200.00 applied, 50.00 stays held.
        assertThat(AllocationEngineV1.allocate(eur(250_00), state(eur(4_000_00), january), LOAN)).containsExactly(
                AllocationLine.due(JANUARY, INTEREST, eur(30_00)),
                AllocationLine.due(JANUARY, PRINCIPAL, eur(170_00)),
                AllocationLine.creditBalance(eur(50_00)));
    }

    // ------------------------------------------------------------------ the prepayment

    @Nested
    @DisplayName("a partial prepayment (P11-TSK-028's kind)")
    class Prepayment {

        @Test
        @DisplayName("pays principal not yet due, in one line")
        void paysPrincipalNotYetDue() {
            DueItem paid = new DueItem(JANUARY, JAN_15, eur(0), eur(0), eur(0));
            assertThat(AllocationEngineV1.allocatePrepayment(eur(1_000_00), state(eur(4_000_00), paid), LOAN))
                    .containsExactly(AllocationLine.notDuePrincipal(eur(1_000_00)));
            assertThat(AllocationEngineV1.allocatePrepayment(eur(4_000_00), DueState.nothingBilled(eur(4_000_00)), LOAN))
                    .containsExactly(AllocationLine.notDuePrincipal(eur(4_000_00)));
            assertThat(AllocationEngineV1.allocatePrepayment(eur(0), DueState.nothingBilled(eur(4_000_00)), LOAN)).isEmpty();
        }

        @Test
        @DisplayName("is refused while anything billed is due - arrears first")
        void arrearsFirst() {
            DueItem january = new DueItem(JANUARY, JAN_15, eur(0), eur(0), eur(0_01));
            assertRefused(Reason.ARREARS_OUTSTANDING,
                    () -> AllocationEngineV1.allocatePrepayment(eur(100_00), state(eur(4_000_00), january), LOAN));
        }

        @Test
        @DisplayName("is refused beyond the principal not yet due, and under a line's treatment")
        void boundedAndALoansOnly() {
            assertRefused(Reason.PREPAYMENT_EXCEEDS_PRINCIPAL,
                    () -> AllocationEngineV1.allocatePrepayment(eur(4_000_01), DueState.nothingBilled(eur(4_000_00)), LOAN));
            assertRefused(Reason.PREPAYMENT_NOT_APPLICABLE,
                    () -> AllocationEngineV1.allocatePrepayment(eur(1_00), DueState.nothingBilled(eur(4_000_00)), LINE));
            assertRefused(Reason.NEGATIVE_AMOUNT,
                    () -> AllocationEngineV1.allocatePrepayment(eur(-1), DueState.nothingBilled(eur(4_000_00)), LOAN));
        }
    }

    // ------------------------------------------------------------------ the typed refusals

    @Nested
    @DisplayName("a typed refusal")
    class Refusals {

        @Test
        @DisplayName("for a negative amount")
        void negativeAmount() {
            assertRefused(Reason.NEGATIVE_AMOUNT,
                    () -> AllocationEngineV1.allocate(eur(-1), DueState.nothingBilled(eur(0)), LOAN));
        }

        @Test
        @DisplayName("for another currency or another scale")
        void currencyAndScale() {
            CurrencyCode usd = CurrencyCode.of("USD");
            assertRefused(Reason.CURRENCY_MISMATCH,
                    () -> AllocationEngineV1.allocate(Money.ofMinorUnits(1_00, usd), DueState.nothingBilled(eur(0)), LOAN));
            assertRefused(Reason.CURRENCY_MISMATCH, () -> AllocationEngineV1.allocate(
                    Money.ofPersisted(1_000, EUR, 3), DueState.nothingBilled(eur(0)), LOAN));
            assertRefused(Reason.CURRENCY_MISMATCH, () -> new DueState(
                    List.of(new DueItem(JANUARY, JAN_15, eur(0), eur(0), Money.ofMinorUnits(1, usd))), eur(0)));
            assertRefused(Reason.CURRENCY_MISMATCH, () -> new DueState(List.of(new DueItem(JANUARY, JAN_15,
                    Money.ofMinorUnits(1, usd), Money.ofMinorUnits(1, usd), Money.ofMinorUnits(1, usd))), eur(0)));
        }

        @Test
        @DisplayName("for a malformed order - a component missing, repeated or absent - or a gap in the rules")
        void malformedOrder() {
            for (List<AllocationComponent> order : List.of(List.of(FEES, INTEREST), List.of(FEES, INTEREST, INTEREST),
                    List.of(FEES, INTEREST, PRINCIPAL, FEES), List.<AllocationComponent>of())) {
                assertRefused(Reason.MALFORMED_RULES, () -> new AllocationRules(EngineVersion.ALLOCATION_ENGINE_V1,
                        AllocationSequence.OLDEST_DUE_FIRST, order, OverpaymentTreatment.HOLD_AS_CREDIT));
            }
            assertRefused(Reason.MALFORMED_RULES, () -> new AllocationRules(EngineVersion.ALLOCATION_ENGINE_V1,
                    AllocationSequence.OLDEST_DUE_FIRST, Arrays.asList(FEES, null, PRINCIPAL),
                    OverpaymentTreatment.HOLD_AS_CREDIT));
            assertRefused(Reason.MALFORMED_RULES, () -> new AllocationRules(EngineVersion.ALLOCATION_ENGINE_V1,
                    null, List.of(FEES, INTEREST, PRINCIPAL), OverpaymentTreatment.HOLD_AS_CREDIT));
            assertRefused(Reason.MALFORMED_RULES, () -> new AllocationRules(EngineVersion.ALLOCATION_ENGINE_V1,
                    AllocationSequence.OLDEST_DUE_FIRST, List.of(FEES, INTEREST, PRINCIPAL), null));
        }

        @Test
        @DisplayName("for rules naming another engine's version")
        void anotherEngine() {
            assertRefused(Reason.MALFORMED_RULES, () -> new AllocationRules(EngineVersion.SCHEDULE_ENGINE_V1,
                    AllocationSequence.OLDEST_DUE_FIRST, List.of(FEES, INTEREST, PRINCIPAL),
                    OverpaymentTreatment.HOLD_AS_CREDIT));
        }

        @Test
        @DisplayName("for a due state no derivation produces: a negative figure, one item twice, one due date twice")
        void malformedDueState() {
            assertRefused(Reason.MALFORMED_DUE_STATE, () -> new DueItem(JANUARY, JAN_15, eur(-1), eur(0), eur(0)));
            assertRefused(Reason.MALFORMED_DUE_STATE, () -> new DueItem(JANUARY, JAN_15, eur(0), eur(-1), eur(0)));
            assertRefused(Reason.MALFORMED_DUE_STATE, () -> new DueItem(JANUARY, JAN_15, eur(0), eur(0), eur(-1)));
            assertRefused(Reason.MALFORMED_DUE_STATE, () -> DueState.nothingBilled(eur(-1)));
            DueItem january = new DueItem(JANUARY, JAN_15, eur(0), eur(1_00), eur(0));
            assertRefused(Reason.MALFORMED_DUE_STATE, () -> state(eur(0), january,
                    new DueItem(JANUARY, FEB_15, eur(0), eur(1_00), eur(0))));
            assertRefused(Reason.MALFORMED_DUE_STATE, () -> state(eur(0), january,
                    new DueItem(FEBRUARY, JAN_15, eur(0), eur(1_00), eur(0))));
            assertRefused(Reason.MALFORMED_DUE_STATE, () -> new DueState(Arrays.asList(january, null), eur(0)));
        }
    }

    @Test
    @DisplayName("a line names an item and component exactly when it pays one, and is positive")
    void lineShape() {
        assertThatThrownBy(() -> new AllocationLine(AllocationLine.Target.DUE_ITEM, Optional.empty(), Optional.of(FEES),
                eur(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AllocationLine(AllocationLine.Target.CREDIT_BALANCE, Optional.of(JANUARY),
                Optional.of(FEES), eur(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllocationLine.creditBalance(eur(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllocationLine.notDuePrincipal(eur(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the rules come from the terms: each product's engine, sequence, order and its kind's treatment")
    void rulesFromTerms() {
        assertThat(LOAN).isEqualTo(new AllocationRules(EngineVersion.ALLOCATION_ENGINE_V1,
                AllocationSequence.OLDEST_DUE_FIRST, List.of(FEES, INTEREST, PRINCIPAL), OverpaymentTreatment.HOLD_AS_CREDIT));
        assertThat(LINE.overpaymentTreatment()).isEqualTo(OverpaymentTreatment.PAY_DOWN_PRINCIPAL);
        assertThat(AllocationEngineV1.VERSION).isEqualTo(EngineVersion.ALLOCATION_ENGINE_V1);
    }

    // ------------------------------------------------------------------ plumbing

    static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    static DueState state(Money notDuePrincipal, DueItem... items) {
        return new DueState(List.of(items), notDuePrincipal);
    }

    static AllocationRules withOrder(AllocationRules rules, List<AllocationComponent> order) {
        return new AllocationRules(rules.engine(), rules.sequence(), order, rules.overpaymentTreatment());
    }

    /** A UUIDv7 made from {@code n} - deterministic, so a case reproduces on any JVM. */
    static DueItemId item(long n) {
        long msb = (n << 16 & 0xFFFF_FFFF_FFFF_0000L) | 0x7000L | (n & 0x0FFFL);
        long lsb = 0x8000_0000_0000_0000L | (n & 0x3FFF_FFFF_FFFF_FFFFL);
        return DueItemId.of(new UUID(msb, lsb));
    }

    static void assertRefused(Reason reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AllocationRefused.class,
                refused -> assertThat(refused.reason()).isEqualTo(reason));
    }
}
