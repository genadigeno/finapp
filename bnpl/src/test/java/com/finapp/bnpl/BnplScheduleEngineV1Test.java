package com.finapp.bnpl;

import static com.finapp.bnpl.BnplTermsFixtures.EUR;
import static com.finapp.bnpl.BnplTermsFixtures.euros;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code BNPL_SCHEDULE_ENGINE_V1}'s golden worked cases, its typed refusals and its record's construction
 * (`P12-TSK-008`; ADR-0102 decision 2, {@code PHASE_12_PLAN.md} section 12.2, A4, A5, OQ-3; {@code INV-BNPL-05}).
 *
 * <p><strong>Every figure here is worked by hand</strong> from the rule, never read back from the engine: {@code A =
 * floor(P / 4)} in minor units for instalments 2-4 and instalment 1 {@code P - 3A}; instalment k due {@code D1 + 14 (k -
 * 1)} calendar days. EUR 100.01 is 10,001 cents: 10,001 / 4 = 2,500 remainder 1, so 25.00 three times and 100.01 - 75.00
 * = 25.01 first. EUR 50.03: 5,003 / 4 = 1,250 remainder 3 - 12.50 three times, 12.53 first. EUR 999.99: 99,999 / 4 =
 * 24,999 remainder 3 - 249.99 three times, 250.02 first. A purchase on 29 February 2028: + 14 = 14 March (February 2028
 * has 29 days, so the 29th + 14 is 14 March), + 28 = 28 March, + 42 = 11 April. On 25 December 2027: + 14 = 8 January
 * 2028 (6 days to 31 December, then 8), + 28 = 22 January, + 42 = 5 February. A row reads
 * {@code sequence|due date|principal}.
 */
@DisplayName("BNPL schedule engine v1: golden worked cases, typed refusals, the conserving record (P12-TSK-008)")
class BnplScheduleEngineV1Test {

    private static final BnplTerms PAY_IN_4 = BnplTermsFixtures.payIn4().build();
    private static final LocalDate ORDINARY = LocalDate.of(2027, 3, 1);

    // ------------------------------------------------------------------ golden cases - the amounts

    @Test
    @DisplayName("EUR 100.00 divides evenly: 25.00 four times")
    void anEvenPrincipal() {
        assertThat(rows(projected("100.00", ORDINARY))).containsExactly(
                "1|2027-03-01|25.00", "2|2027-03-15|25.00", "3|2027-03-29|25.00", "4|2027-04-12|25.00");
    }

    @Test
    @DisplayName("EUR 100.01: the remainder on instalment 1, collected at purchase - 25.01, then 25.00 three times (OQ-3)")
    void theRemainderIsCollectedAtPurchase() {
        assertThat(amounts(projected("100.01", ORDINARY))).containsExactly("25.01", "25.00", "25.00", "25.00");
    }

    @Test
    @DisplayName("EUR 50.03 - the largest remainder, three cents, all on instalment 1: 12.53, then 12.50 three times")
    void theLargestRemainder() {
        assertThat(amounts(projected("50.03", ORDINARY))).containsExactly("12.53", "12.50", "12.50", "12.50");
    }

    @Test
    @DisplayName("EUR 100.03: ALL the remainder on instalment 1 (25.03) - never spread one unit at a time over the earliest"
            + " instalments, as Money.allocateEvenly would (25.01, 25.01, 25.01, 25.00)")
    void notAllocateEvenly() {
        assertThat(amounts(projected("100.03", ORDINARY))).containsExactly("25.03", "25.00", "25.00", "25.00");
        assertThat(euros(100_03).allocateEvenly(4)).extracting(Money::toBigDecimal).extracting(Object::toString)
                .as("the rule this engine deliberately does not reuse").containsExactly("25.01", "25.01", "25.01", "25.00");
    }

    @Test
    @DisplayName("credit's bounds: EUR 50.00 is 12.50 four times, EUR 1,000.00 is 250.00 four times, EUR 999.99 is 250.02"
            + " then 249.99 three times")
    void creditsBounds() {
        assertThat(amounts(projected("50.00", ORDINARY))).containsExactly("12.50", "12.50", "12.50", "12.50");
        assertThat(amounts(projected("1000.00", ORDINARY))).containsExactly("250.00", "250.00", "250.00", "250.00");
        assertThat(amounts(projected("999.99", ORDINARY))).containsExactly("250.02", "249.99", "249.99", "249.99");
    }

    @Test
    @DisplayName("the engine's smallest schedules: EUR 0.04 is one cent four times; EUR 0.07 is 0.04, then 0.01 three"
            + " times")
    void theSmallestSchedules() {
        assertThat(amounts(projected("0.04", ORDINARY))).containsExactly("0.01", "0.01", "0.01", "0.01");
        assertThat(amounts(projected("0.07", ORDINARY))).containsExactly("0.04", "0.01", "0.01", "0.01");
    }

    // ------------------------------------------------------------------ golden cases - the dates

    @Test
    @DisplayName("a purchase on the leap day 2028-02-29 falls due 2028-02-29, 03-14, 03-28 and 04-11 - calendar days, no"
            + " business-day adjustment (A5)")
    void theLeapDay() {
        assertThat(dates(projected("100.00", LocalDate.of(2028, 2, 29))))
                .containsExactly("2028-02-29", "2028-03-14", "2028-03-28", "2028-04-11");
    }

    @Test
    @DisplayName("a purchase on 2027-12-25 runs over the year end: 2027-12-25, 2028-01-08, 01-22 and 02-05 - a holiday"
            + " and a weekend day are ordinary days")
    void theYearEnd() {
        assertThat(dates(projected("100.00", LocalDate.of(2027, 12, 25))))
                .containsExactly("2027-12-25", "2028-01-08", "2028-01-22", "2028-02-05");
    }

    @Test
    @DisplayName("a purchase on 2027-02-15 in a non-leap year: 03-01, 03-15, 03-29 - February has 28 days")
    void aNonLeapFebruary() {
        assertThat(dates(projected("100.00", LocalDate.of(2027, 2, 15))))
                .containsExactly("2027-02-15", "2027-03-01", "2027-03-15", "2027-03-29");
    }

    @Test
    @DisplayName("the schedule names its engine, its currency, its principal and its purchase date; instalment 1 is the"
            + " one collected at purchase")
    void theScheduleNamesItsInputs() {
        BnplSchedule schedule = projected("100.01", ORDINARY);
        assertThat(schedule.engine()).isEqualTo(BnplEngine.BNPL_SCHEDULE_ENGINE_V1);
        assertThat(schedule.currency()).isEqualTo(EUR);
        assertThat(schedule.principal()).isEqualTo(euros(100_01));
        assertThat(schedule.purchaseDate()).isEqualTo(ORDINARY);
        assertThat(schedule.instalmentCount()).isEqualTo(4);
        assertThat(schedule.atPurchase().dueDate()).isEqualTo(ORDINARY);
        assertThat(schedule.atPurchase().principal()).isEqualTo(euros(25_01));
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("any other shape is refused, never approximated: pay-in-N's terms - an n other than 4, a non-zero rate,"
            + " a monthly frequency - are SHAPE_NOT_IMPLEMENTED")
    void anyOtherShapeIsRefused() {
        BnplScheduleOutcome.Refused refused = refused(
                BnplScheduleEngineV1.project(BnplTermsFixtures.payInN("0.10").build(), euros(100_00), ORDINARY));
        assertThat(refused.reason()).isEqualTo(BnplScheduleRefusal.SHAPE_NOT_IMPLEMENTED);
        assertThat(refused.detail()).contains("pay-in-4");
        assertThat(BnplScheduleEngineV1.project(BnplTermsFixtures.payInN("0").build(), euros(100_00), ORDINARY))
                .as("a zero-rate pay-in-N set is still another shape").isInstanceOf(BnplScheduleOutcome.Refused.class);
    }

    @Test
    @DisplayName("a principal of zero or less is PRINCIPAL_NOT_POSITIVE")
    void aNonPositivePrincipalIsRefused() {
        for (long minor : new long[] {0, -1, -100_00}) {
            assertThat(refused(BnplScheduleEngineV1.project(PAY_IN_4, euros(minor), ORDINARY)).reason())
                    .as("%d minor units", minor).isEqualTo(BnplScheduleRefusal.PRINCIPAL_NOT_POSITIVE);
        }
    }

    @Test
    @DisplayName("one to three minor units would make A = floor(P / 4) zero - INSTALMENT_OF_NOTHING; four schedule")
    void anInstalmentOfNothingIsRefused() {
        for (long minor = 1; minor <= 3; minor++) {
            BnplScheduleOutcome.Refused refused = refused(BnplScheduleEngineV1.project(PAY_IN_4, euros(minor), ORDINARY));
            assertThat(refused.reason()).as("%d minor units", minor).isEqualTo(BnplScheduleRefusal.INSTALMENT_OF_NOTHING);
            assertThat(refused.detail()).contains("instalment of nothing");
        }
        assertThat(BnplScheduleEngineV1.project(PAY_IN_4, euros(4), ORDINARY))
                .isInstanceOf(BnplScheduleOutcome.Projected.class);
    }

    @Test
    @DisplayName("a principal in another currency, or not at the currency's scale, is INVALID_REQUEST")
    void aMalformedPrincipalIsRefused() {
        assertThat(refused(BnplScheduleEngineV1.project(PAY_IN_4, Money.ofMinorUnits(100_00, CurrencyCode.of("USD")),
                ORDINARY)).reason()).isEqualTo(BnplScheduleRefusal.INVALID_REQUEST);
        assertThat(refused(BnplScheduleEngineV1.project(PAY_IN_4, Money.ofPersisted(100_000, EUR, 3), ORDINARY)).reason())
                .as("EUR 100.000 at three places").isEqualTo(BnplScheduleRefusal.INVALID_REQUEST);
    }

    @Test
    @DisplayName("every due date falls within the years 1 to 9999: a purchase on 9999-11-19 schedules to 9999-12-31, one"
            + " a day later or in year 0 is INVALID_REQUEST")
    void theCalendarsRange() {
        assertThat(dates(projected("100.00", LocalDate.of(9_999, 11, 19))))
                .containsExactly("9999-11-19", "9999-12-03", "9999-12-17", "9999-12-31");
        assertThat(dates(projected("100.00", LocalDate.of(1, 1, 1)))).first().isEqualTo("0001-01-01");
        for (LocalDate outside : List.of(LocalDate.of(9_999, 11, 20), LocalDate.of(0, 12, 31), LocalDate.MAX)) {
            assertThat(refused(BnplScheduleEngineV1.project(PAY_IN_4, euros(100_00), outside)).reason())
                    .as(outside.toString()).isEqualTo(BnplScheduleRefusal.INVALID_REQUEST);
        }
    }

    @Test
    @DisplayName("no refusal's detail names an amount")
    void noDetailNamesAnAmount() {
        List<BnplScheduleOutcome> outcomes = List.of(
                BnplScheduleEngineV1.project(PAY_IN_4, euros(3), ORDINARY),
                BnplScheduleEngineV1.project(PAY_IN_4, euros(-12_34), ORDINARY),
                BnplScheduleEngineV1.project(PAY_IN_4, Money.ofMinorUnits(56_78, CurrencyCode.of("USD")), ORDINARY),
                BnplScheduleEngineV1.project(BnplTermsFixtures.payInN("0.10").build(), euros(91_23), ORDINARY));
        for (BnplScheduleOutcome outcome : outcomes) {
            assertThat(refused(outcome).detail()).doesNotContain("0.03", "12.34", "56.78", "91.23", "0.10");
        }
    }

    // ------------------------------------------------------------------ the terms door's judgement

    @Test
    @DisplayName("judgeSchedulable: credit's smallest purchase at three minor units is an instalment of nothing, at four"
            + " it schedules, and another product's terms are another shape")
    void theDoorsJudgement() {
        assertThat(BnplScheduleEngineV1.judgeSchedulable(PAY_IN_4, euros(3)).orElseThrow().reason())
                .isEqualTo(BnplScheduleRefusal.INSTALMENT_OF_NOTHING);
        assertThat(BnplScheduleEngineV1.judgeSchedulable(PAY_IN_4, euros(4))).isEmpty();
        assertThat(BnplScheduleEngineV1.judgeSchedulable(PAY_IN_4, euros(50_00))).isEmpty();
        assertThat(BnplScheduleEngineV1.judgeSchedulable(BnplTermsFixtures.payInN("0.10").build(), euros(200_00))
                .orElseThrow().reason()).isEqualTo(BnplScheduleRefusal.SHAPE_NOT_IMPLEMENTED);
    }

    // ------------------------------------------------------------------ one statement of the shape

    @Test
    @DisplayName("the shape is stated once: the engine's count is pay-in-4's plan's, its interval and rounding the terms'"
            + " bounds, its version the current BNPL schedule engine and the one pay-in-4's terms pin")
    void theShapeIsStatedOnce() {
        assertThat(BnplScheduleEngineV1.INSTALMENTS).isEqualTo(BnplPlan.PAY_IN_4.instalments()).isEqualTo(4);
        assertThat(BnplScheduleEngineV1.INTERVAL_DAYS).isEqualTo(BnplTerms.PAY_IN_4_INTERVAL_DAYS).isEqualTo(14);
        assertThat(BnplScheduleEngineV1.INSTALMENT_ROUNDING).isEqualTo(BnplTerms.PAY_IN_4_INSTALMENT_ROUNDING);
        assertThat(BnplScheduleEngineV1.VERSION).isEqualTo(BnplEngine.current(BnplEngine.Kind.BNPL_SCHEDULE))
                .isEqualTo(PAY_IN_4.payIn4().orElseThrow().scheduleEngine());
    }

    // ------------------------------------------------------------------ the record

    @Test
    @DisplayName("a schedule that does not conserve does not exist - one cent short is a defect, not a schedule"
            + " (INV-BNPL-05)")
    void aScheduleThatDoesNotConserveDoesNotExist() {
        assertThatThrownBy(() -> schedule(euros(100_00), ORDINARY, 25_00, 25_00, 25_00, 24_99))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INV-BNPL-05");
        assertThatThrownBy(() -> schedule(euros(100_00), ORDINARY, 25_01, 25_00, 25_00, 25_00))
                .as("one cent over").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INV-BNPL-05");
        assertThat(schedule(euros(100_00), ORDINARY, 25_00, 25_00, 25_00, 25_00).instalmentCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("the record holds its chain: the first instalment on the purchase date, each later one strictly after its"
            + " predecessor, numbered in order, every principal positive, named for a BNPL schedule engine")
    void theRecordHoldsItsChain() {
        Money principal = euros(100_00);
        List<BnplSchedule.Instalment> notOnThePurchaseDate = List.of(
                new BnplSchedule.Instalment(1, ORDINARY.plusDays(1), euros(50_00)),
                new BnplSchedule.Instalment(2, ORDINARY.plusDays(15), euros(50_00)));
        assertThatThrownBy(() -> new BnplSchedule(BnplEngine.BNPL_SCHEDULE_ENGINE_V1, EUR, principal, ORDINARY,
                notOnThePurchaseDate)).isInstanceOf(IllegalArgumentException.class);
        List<BnplSchedule.Instalment> notForward = List.of(
                new BnplSchedule.Instalment(1, ORDINARY, euros(50_00)),
                new BnplSchedule.Instalment(2, ORDINARY, euros(50_00)));
        assertThatThrownBy(() -> new BnplSchedule(BnplEngine.BNPL_SCHEDULE_ENGINE_V1, EUR, principal, ORDINARY,
                notForward)).isInstanceOf(IllegalArgumentException.class);
        List<BnplSchedule.Instalment> outOfOrder = List.of(
                new BnplSchedule.Instalment(2, ORDINARY, euros(50_00)),
                new BnplSchedule.Instalment(1, ORDINARY.plusDays(14), euros(50_00)));
        assertThatThrownBy(() -> new BnplSchedule(BnplEngine.BNPL_SCHEDULE_ENGINE_V1, EUR, principal, ORDINARY,
                outOfOrder)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplSchedule.Instalment(1, ORDINARY, euros(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplSchedule(BnplEngine.REFUND_ALLOCATION_ENGINE_V1, EUR, principal, ORDINARY,
                List.of(new BnplSchedule.Instalment(1, ORDINARY, principal)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplSchedule(BnplEngine.BNPL_SCHEDULE_ENGINE_V1, EUR, principal, ORDINARY,
                List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString names the engine, the count, the sequence and the due date - never an amount")
    void noAmountInText() {
        BnplSchedule schedule = projected("123.45", ORDINARY);
        assertThat(schedule.toString()).isEqualTo("BnplSchedule[BNPL_SCHEDULE_ENGINE_V1, 4 instalments]");
        assertThat(schedule.atPurchase().toString()).isEqualTo("Instalment[1, due 2027-03-01]");
        assertThat(schedule.instalments().toString()).doesNotContain("30.", "123.45");
    }

    // ------------------------------------------------------------------ helpers

    private static BnplSchedule projected(String principal, LocalDate purchaseDate) {
        BnplScheduleOutcome outcome = BnplScheduleEngineV1.project(PAY_IN_4,
                Money.of(new java.math.BigDecimal(principal), EUR), purchaseDate);
        assertThat(outcome).as(principal + " on " + purchaseDate).isInstanceOf(BnplScheduleOutcome.Projected.class);
        return ((BnplScheduleOutcome.Projected) outcome).schedule();
    }

    private static BnplScheduleOutcome.Refused refused(BnplScheduleOutcome outcome) {
        assertThat(outcome).isInstanceOf(BnplScheduleOutcome.Refused.class);
        return (BnplScheduleOutcome.Refused) outcome;
    }

    private static BnplSchedule schedule(Money principal, LocalDate purchaseDate, long... minors) {
        List<BnplSchedule.Instalment> instalments = new java.util.ArrayList<>();
        for (int index = 0; index < minors.length; index++) {
            instalments.add(new BnplSchedule.Instalment(index + 1, purchaseDate.plusDays(14L * index), euros(minors[index])));
        }
        return new BnplSchedule(BnplEngine.BNPL_SCHEDULE_ENGINE_V1, EUR, principal, purchaseDate, instalments);
    }

    static List<String> rows(BnplSchedule schedule) {
        return schedule.instalments().stream().map(instalment -> instalment.sequence() + "|" + instalment.dueDate() + "|"
                + instalment.principal().toBigDecimal().toPlainString()).toList();
    }

    private static List<String> amounts(BnplSchedule schedule) {
        return schedule.instalments().stream().map(instalment -> instalment.principal().toBigDecimal().toPlainString())
                .toList();
    }

    private static List<String> dates(BnplSchedule schedule) {
        return schedule.instalments().stream().map(instalment -> instalment.dueDate().toString()).toList();
    }
}
