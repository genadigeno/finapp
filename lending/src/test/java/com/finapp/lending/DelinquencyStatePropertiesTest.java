package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The delinquency derivation's properties over generated histories (`P11-TSK-024`; ADR-0098 point 1,
 * {@code PHASE_GATES.md} section 5 Phase 11 "Delinquency", {@code INV-LND-12}): over a generated history of billed items
 * - monthly due dates from month ends and leap days, billed amounts, partial and full payments allocated oldest first,
 * payments that miss, and an occasional reopening (a reversal) of an older item - every business date's DPD equals an
 * independent naive oracle (the oldest item with anything unpaid whose due date is before the business date, its age
 * counted one day at a time), the bucket is the one the pinned bounds name (written out here as an if-chain, never
 * {@link DelinquencyBucket#of}), and the default flag, evaluated daily with its previous value as its only memory,
 * equals a closed-form oracle with no memory at all: defaulted on a date exactly when some date up to it reached the
 * threshold and DPD stayed above zero on every date since (A8, no probation).
 *
 * <p>The terms vary too: the threshold and the middle bounds are generated within what {@link LoanTerms} admits. Seeded,
 * so a failure reproduces; named {@code ...PropertiesTest} (the {@code TestTaxonomyTest} convention).
 */
@DisplayName("the delinquency derivation's properties over generated histories (P11-TSK-024)")
class DelinquencyStatePropertiesTest {

    static final int HISTORIES = 400;
    static final long SEED = 11_024L;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("over every generated history, on every business date from before the first due date to well past the"
            + " last: DPD is the business date less the oldest past-due item's due date (0 when none), the bucket the"
            + " pinned bounds name, and the default flag the threshold-and-cure rule's - each against a naive oracle")
    void dpdBucketAndDefaultEqualTheOracleOverAGeneratedHistory() {
        Random random = new Random(SEED);
        int judged = 0;
        int pastDue = 0;
        int defaulted = 0;
        int cured = 0;
        int heldBelowThreshold = 0;
        for (int h = 0; h < HISTORIES; h++) {
            History history = History.generate(random);
            int[] oracleDpd = new int[history.days()];
            for (int d = 0; d < history.days(); d++) {
                oracleDpd[d] = naiveDpd(history, d);
            }
            boolean previously = false;
            boolean everDefaulted = false;
            for (int d = 0; d < history.days(); d++) {
                LocalDate today = history.day(d);
                DelinquencyState state = DelinquencyState.derive(history.itemsOn(d), today, history.terms(), previously);
                String at = history + " on " + today;
                assertThat(state.daysPastDue()).as("DPD " + at).isEqualTo(oracleDpd[d]);
                assertThat(state.bucketName()).as("bucket " + at)
                        .isEqualTo(naiveBucket(oracleDpd[d], history.terms().delinquencyBounds()));
                assertThat(state.defaulted()).as("default " + at)
                        .isEqualTo(naiveDefault(oracleDpd, d, history.terms().defaultThresholdDays()));
                assertThat(state.pastDue()).as(at).isEqualTo(oracleDpd[d] > 0);

                judged++;
                pastDue += oracleDpd[d] > 0 ? 1 : 0;
                defaulted += state.defaulted() ? 1 : 0;
                heldBelowThreshold += state.defaulted() && oracleDpd[d] < history.terms().defaultThresholdDays() ? 1 : 0;
                cured += everDefaulted && previously && !state.defaulted() ? 1 : 0;
                everDefaulted |= state.defaulted();
                previously = state.defaulted();
            }
        }
        // Not vacuous: the histories reach every arm - current, past due, defaulted, held below the threshold, cured.
        assertThat(judged).isGreaterThan(50_000);
        assertThat(pastDue).isGreaterThan(judged / 10);
        assertThat(defaulted).isGreaterThan(judged / 100);
        assertThat(heldBelowThreshold).isPositive();
        assertThat(cured).isPositive();
    }

    @Test
    @DisplayName("DPD is a difference of dates, never a counter: a missed or repeated evaluation neither adds nor loses a"
            + " day - judged on any date alone, the items in any order, with either remembered flag, it is the same figure")
    void dpdIsADifferenceOfDatesWhateverTheEvaluationsMissed() {
        Random random = new Random(SEED + 1);
        for (int h = 0; h < HISTORIES; h++) {
            History history = History.generate(random);
            for (int probe = 0; probe < 25; probe++) {
                int d = random.nextInt(history.days());
                LocalDate today = history.day(d);
                List<DueState.DueItem> items = history.itemsOn(d);
                List<DueState.DueItem> shuffled = new ArrayList<>(items);
                Collections.shuffle(shuffled, random);
                DelinquencyState once = DelinquencyState.derive(items, today, history.terms(), false);
                DelinquencyState again = DelinquencyState.derive(shuffled, today, history.terms(), true);
                assertThat(again.daysPastDue()).as(history + " on " + today).isEqualTo(once.daysPastDue());
                assertThat(again.bucket()).as(history + " on " + today).isEqualTo(once.bucket());
                assertThat(once.daysPastDue()).as(history + " on " + today).isEqualTo(naiveDpd(history, d));
            }
        }
    }

    // ------------------------------------------------------------------ the oracles, written out independently

    /** The oldest item with anything unpaid on day {@code d} and due before it; its age counted one day at a time. */
    private static int naiveDpd(History history, int d) {
        LocalDate today = history.day(d);
        LocalDate oldest = null;
        for (int i = 0; i < history.dues().size(); i++) {
            LocalDate due = history.dues().get(i);
            if (due.compareTo(today) < 0 && history.unpaid(d)[i] > 0 && (oldest == null || due.compareTo(oldest) < 0)) {
                oldest = due;
            }
        }
        if (oldest == null) {
            return 0;
        }
        int days = 0;
        for (LocalDate walk = oldest; walk.isBefore(today); walk = walk.plusDays(1)) {
            days++;
        }
        return days;
    }

    /** The bucket the four ascending lower bounds name, as an if-chain - {@code CURRENT} at 0. */
    private static String naiveBucket(int dpd, List<Integer> bounds) {
        if (dpd == 0) {
            return "CURRENT";
        }
        if (dpd >= bounds.get(3)) {
            return "B4_90_PLUS";
        }
        if (dpd >= bounds.get(2)) {
            return "B3_60_89";
        }
        if (dpd >= bounds.get(1)) {
            return "B2_30_59";
        }
        return "B1_1_29";
    }

    /**
     * Closed form, no memory: defaulted on day {@code d} exactly when some day {@code s <= d} had DPD at or past the
     * threshold and every day from {@code s} through {@code d} had DPD above zero.
     */
    private static boolean naiveDefault(int[] dpd, int d, int threshold) {
        for (int s = d; s >= 0; s--) {
            if (dpd[s] == 0) {
                return false;
            }
            if (dpd[s] >= threshold) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the generated history

    /**
     * Instalments billed on monthly due dates from a start that is often a month end or a leap day, each with its billed
     * amount; payments on dates of their own, each allocated oldest first to whatever is billed and unpaid on its date (a
     * payment beyond everything billed is credit, outside these items); an occasional reopening (a reversal) puts an
     * older item's billed amount back - so a younger item can be paid while an older one is not. Replayed once, day by
     * day, into what every item has unpaid at the end of every day.
     */
    private record History(LoanTerms terms, List<LocalDate> dues, List<Long> billed, List<DueItemId> ids, LocalDate first,
            long[][] unpaidByDay, long seed) {

        static History generate(Random random) {
            long seed = random.nextLong();
            Random r = new Random(seed);
            LocalDate[] starts = {LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 29),
                    LocalDate.of(2025, 12, 31), LocalDate.of(2026, 3, 30), LocalDate.of(2027, 8, 31)};
            LocalDate start = r.nextInt(3) == 0 ? LocalDate.of(2023 + r.nextInt(8), 1 + r.nextInt(12), 1 + r.nextInt(28))
                    : starts[r.nextInt(starts.length)];
            int count = 1 + r.nextInt(9);
            List<LocalDate> dues = new ArrayList<>();
            List<Long> billed = new ArrayList<>();
            List<DueItemId> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(DueItemId.of(IDS.next()));
                dues.add(start.plusMonths(i)); // the calendar's month-end and leap-day clamp
                billed.add(r.nextInt(5) == 0 ? 1L : 1L + r.nextInt(50_000));
            }
            LocalDate first = start.minusDays(3);
            LocalDate last = dues.get(count - 1).plusDays(30 + r.nextInt(220));
            int days = Math.toIntExact(last.toEpochDay() - first.toEpochDay()) + 1;

            // Payments and reopenings, each on a day of the history.
            long[] paidOn = new long[days];
            int payments = r.nextInt(2 * count + 1);
            for (int p = 0; p < payments; p++) {
                long bill = billed.get(r.nextInt(count));
                long amount = switch (r.nextInt(4)) {
                    case 0 -> Math.max(1, bill - 1); // one minor unit short
                    case 1 -> bill;
                    case 2 -> 1L + r.nextLong(bill);
                    default -> bill * (1 + r.nextInt(3));
                };
                paidOn[r.nextInt(days)] += amount;
            }
            int reopenDay = r.nextInt(4) == 0 ? r.nextInt(days) : -1;
            int reopenItem = r.nextInt(count);

            long[][] unpaidByDay = new long[days][];
            long[] unpaid = new long[count];
            boolean[] billedYet = new boolean[count];
            for (int d = 0; d < days; d++) {
                LocalDate day = first.plusDays(d);
                for (int i = 0; i < count; i++) {
                    if (!billedYet[i] && !dues.get(i).isAfter(day)) {
                        billedYet[i] = true;
                        unpaid[i] = billed.get(i);
                    }
                }
                long left = paidOn[d];
                for (int i = 0; i < count && left > 0; i++) { // dues ascend: oldest first
                    long paid = Math.min(left, unpaid[i]);
                    unpaid[i] -= paid;
                    left -= paid;
                }
                if (d == reopenDay && billedYet[reopenItem]) {
                    unpaid[reopenItem] = billed.get(reopenItem);
                }
                unpaidByDay[d] = unpaid.clone();
            }

            int threshold = r.nextInt(3) == 0 ? 90
                    : LoanTerms.MINIMUM_DEFAULT_THRESHOLD_DAYS + r.nextInt(
                            LoanTerms.MAXIMUM_DEFAULT_THRESHOLD_DAYS - LoanTerms.MINIMUM_DEFAULT_THRESHOLD_DAYS + 1);
            List<Integer> bounds;
            if (threshold == 90 && r.nextBoolean()) {
                bounds = List.of(1, 30, 60, 90);
            } else {
                int second = 2 + r.nextInt(threshold - 3);
                int third = second + 1 + r.nextInt(threshold - second - 1);
                bounds = List.of(1, second, third, threshold);
            }
            LoanTerms terms = LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899")
                    .delinquencyBounds(bounds).defaultThresholdDays(threshold).build();
            return new History(terms, List.copyOf(dues), List.copyOf(billed), List.copyOf(ids), first, unpaidByDay, seed);
        }

        int days() {
            return unpaidByDay.length;
        }

        LocalDate day(int d) {
            return first.plusDays(d);
        }

        long[] unpaid(int d) {
            return unpaidByDay[d];
        }

        /** The items billed by day {@code d}, as the due state hands them over - what each has unpaid split arbitrarily. */
        List<DueState.DueItem> itemsOn(int d) {
            LocalDate today = day(d);
            List<DueState.DueItem> items = new ArrayList<>();
            Random split = new Random(seed ^ today.toEpochDay());
            for (int i = 0; i < dues.size(); i++) {
                if (dues.get(i).isAfter(today)) {
                    continue; // not billed yet
                }
                long left = unpaidByDay[d][i];
                long fees = left == 0 ? 0 : split.nextLong(left + 1);
                long interest = left - fees == 0 ? 0 : split.nextLong(left - fees + 1);
                items.add(new DueState.DueItem(ids.get(i), dues.get(i), eur(fees), eur(interest),
                        eur(left - fees - interest)));
            }
            return items;
        }

        @Override
        public String toString() {
            return "History[seed=" + seed + ", dues=" + dues + ", threshold=" + terms.defaultThresholdDays() + ", bounds="
                    + terms.delinquencyBounds() + "]";
        }
    }

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }
}
