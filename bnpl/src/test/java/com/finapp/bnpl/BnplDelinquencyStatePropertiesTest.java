package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BNPL's delinquency derivation's properties over generated histories (the Phase 12 exit review's finding: the rule was
 * proven by examples only; lending's {@code DelinquencyStatePropertiesTest}, `P11-TSK-024`, applied to
 * {@link BnplDelinquencyState} - {@code PHASE_12_PLAN.md} section 12.8, A14, ADR-0106 decision 3, {@code INV-LND-12} by
 * analogy): over a generated history of billed instalments - pay-in-4's fortnights or pay-in-N's months from month ends
 * and leap days, billed fees, interest and principal, partial and full payments allocated oldest first, payments that
 * miss, an occasional reopening (a reversal) of an older instalment - and the disputes that held some of them, opened and
 * lifted (or not) on dates of their own, every business date's DPD equals an independent naive oracle (the oldest
 * instalment with anything unpaid that no open dispute holds, counted one day at a time from the later of its due date and
 * the latest lift by then of a dispute that held it), the bucket is the one the pinned bounds name (an if-chain, never
 * {@link BnplDelinquencyBucket#of}), and the default flag, evaluated daily with its previous value as its only memory,
 * equals a closed-form oracle with no memory at all: defaulted on a date exactly when some date up to it reached the
 * threshold and DPD stayed above zero on every date since (A14, no probation).
 *
 * <p>The terms are what {@link BnplTerms} admits - since the Phase 12 transition gate's A7-5 one set of bounds,
 * {@link BnplTerms#DELINQUENCY_BOUNDS}, and the threshold 90. Seeded, so a failure reproduces; named {@code ...PropertiesTest}
 * (the {@code TestTaxonomyTest} convention). Hermetic: nothing beyond the JVM.
 */
@DisplayName("BNPL's delinquency derivation's properties over generated histories, dispute holds and lifts included")
class BnplDelinquencyStatePropertiesTest {

    static final int HISTORIES = 400;
    static final long SEED = 12_020L;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("over every generated history, on every business date from before the first due date to well past the"
            + " last: DPD, bucket and the default flag each equal a naive oracle - held instalments excluded, lifted ones"
            + " counted from their lift")
    void dpdBucketAndDefaultEqualTheOracleOverAGeneratedHistory() {
        Random random = new Random(SEED);
        int judged = 0;
        int pastDue = 0;
        int defaulted = 0;
        int cured = 0;
        int heldBelowThreshold = 0;
        int heldExcluded = 0;
        int countedFromALift = 0;
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
                BnplDelinquencyState state = BnplDelinquencyState.derive(history.itemsOn(d), today, history.terms(),
                        previously, history.hold());
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
                heldExcluded += history.unpaidPastDueHeldOn(d) ? 1 : 0;
                countedFromALift += history.countsFromALiftOn(d) ? 1 : 0;
                everDefaulted |= state.defaulted();
                previously = state.defaulted();
            }
        }
        // Not vacuous: the histories reach every arm - current, past due, defaulted, held below the threshold, cured, an
        // unpaid instalment excluded by its hold, an instalment counted from its lift.
        assertThat(judged).isGreaterThan(50_000);
        assertThat(pastDue).isGreaterThan(judged / 10);
        assertThat(defaulted).isGreaterThan(judged / 100);
        assertThat(heldBelowThreshold).isPositive();
        assertThat(cured).isPositive();
        assertThat(heldExcluded).isGreaterThan(judged / 100);
        assertThat(countedFromALift).isGreaterThan(judged / 100);
    }

    @Test
    @DisplayName("DPD is a difference of dates, never a counter: judged on any date alone, the items in any order, with"
            + " either remembered flag, it is the same figure - and with no dispute, the hold-free derivation's")
    void dpdIsADifferenceOfDatesWhateverTheEvaluationsMissed() {
        Random random = new Random(SEED + 1);
        for (int h = 0; h < HISTORIES; h++) {
            History history = History.generate(random);
            for (int probe = 0; probe < 25; probe++) {
                int d = random.nextInt(history.days());
                LocalDate today = history.day(d);
                List<BnplPurchaseStore.BilledItem> items = history.itemsOn(d);
                List<BnplPurchaseStore.BilledItem> shuffled = new ArrayList<>(items);
                Collections.shuffle(shuffled, random);
                BnplDelinquencyState once = BnplDelinquencyState.derive(items, today, history.terms(), false,
                        history.hold());
                BnplDelinquencyState again = BnplDelinquencyState.derive(shuffled, today, history.terms(), true,
                        history.hold());
                assertThat(again.daysPastDue()).as(history + " on " + today).isEqualTo(once.daysPastDue());
                assertThat(again.bucket()).as(history + " on " + today).isEqualTo(once.bucket());
                assertThat(once.daysPastDue()).as(history + " on " + today).isEqualTo(naiveDpd(history, d));
                if (history.hold().windows().isEmpty()) {
                    assertThat(BnplDelinquencyState.derive(items, today, history.terms(), false).daysPastDue())
                            .as(history + " on " + today).isEqualTo(once.daysPastDue());
                }
            }
        }
    }

    // ------------------------------------------------------------------ the oracles, written out independently

    /**
     * The oldest instalment with anything unpaid on day {@code d}, not held that day, whose clock (its due date, or the
     * latest lift by then of a dispute that held it, whichever is later) started before it; its age counted one day at
     * a time.
     */
    private static int naiveDpd(History history, int d) {
        LocalDate today = history.day(d);
        LocalDate oldest = null;
        for (int i = 0; i < history.dues().size(); i++) {
            LocalDate due = history.dues().get(i);
            if (due.isAfter(today) || history.unpaid(d)[i] == 0 || history.heldOn(i, today)) {
                continue;
            }
            LocalDate start = history.clockStart(i, today);
            if (start.compareTo(today) < 0 && (oldest == null || start.compareTo(oldest) < 0)) {
                oldest = start;
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

    /** One dispute's hold of one instalment: opened on a day, lifted on a later one or never. */
    private record Held(int item, LocalDate openedOn, Optional<LocalDate> liftedOn) {}

    /**
     * Instalments billed on their due dates (every 14 days, or monthly from a start that is often a month end or a leap
     * day), each with its billed fees, interest and principal; payments on dates of their own, each allocated oldest first
     * to whatever is billed and unpaid on its date; an occasional reopening (a reversal) of an older instalment; and up to
     * three disputes, each holding the latest instalments from its opening to its lift (or for ever). Replayed once, day
     * by day, into what every instalment has unpaid at the end of every day.
     */
    private record History(BnplTerms terms, List<LocalDate> dues, List<long[]> billed, List<UUID> ids, LocalDate first,
            long[][] unpaidByDay, List<Held> holds, BnplDisputeHold hold, long seed) {

        static History generate(Random random) {
            long seed = random.nextLong();
            Random r = new Random(seed);
            LocalDate[] starts = {LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 29),
                    LocalDate.of(2025, 12, 31), LocalDate.of(2026, 3, 30), LocalDate.of(2027, 8, 31)};
            LocalDate start = r.nextInt(3) == 0 ? LocalDate.of(2023 + r.nextInt(8), 1 + r.nextInt(12), 1 + r.nextInt(28))
                    : starts[r.nextInt(starts.length)];
            boolean fortnightly = r.nextBoolean();
            int count = fortnightly ? 4 : 1 + r.nextInt(12);
            List<LocalDate> dues = new ArrayList<>();
            List<long[]> billed = new ArrayList<>();
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(new UUID(seed, i));
                dues.add(fortnightly ? start.plusDays(14L * i) : start.plusMonths(i)); // the month-end and leap-day clamp
                long principal = r.nextInt(5) == 0 ? 1L : 1L + r.nextInt(50_000);
                long interest = fortnightly || r.nextBoolean() ? 0 : r.nextInt(2_000);
                long fees = r.nextInt(4) == 0 ? 1L + r.nextInt(1_500) : 0;
                billed.add(new long[] {fees, interest, principal});
            }
            LocalDate first = start.minusDays(3);
            LocalDate last = dues.get(count - 1).plusDays(30 + r.nextInt(220));
            int days = Math.toIntExact(last.toEpochDay() - first.toEpochDay()) + 1;

            // Payments and a reopening, each on a day of the history.
            long[] paidOn = new long[days];
            int payments = r.nextInt(2 * count + 1);
            for (int p = 0; p < payments; p++) {
                long bill = total(billed.get(r.nextInt(count)));
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
                        unpaid[i] = total(billed.get(i));
                    }
                }
                long left = paidOn[d];
                for (int i = 0; i < count && left > 0; i++) { // dues ascend: oldest first
                    long paid = Math.min(left, unpaid[i]);
                    unpaid[i] -= paid;
                    left -= paid;
                }
                if (d == reopenDay && billedYet[reopenItem]) {
                    unpaid[reopenItem] = total(billed.get(reopenItem));
                }
                unpaidByDay[d] = unpaid.clone();
            }

            // Disputes: each holds the latest instalments - from a random one to the last - from its opening to its lift.
            List<Held> holds = new ArrayList<>();
            List<BnplDisputeHold.Window> windows = new ArrayList<>();
            int disputes = r.nextInt(3) == 0 ? 0 : 1 + r.nextInt(3);
            for (int k = 0; k < disputes; k++) {
                BnplDisputeId dispute = BnplDisputeId.next(IDS);
                LocalDate openedOn = first.plusDays(r.nextInt(days));
                Optional<LocalDate> liftedOn = r.nextInt(4) == 0 ? Optional.empty()
                        : Optional.of(openedOn.plusDays(r.nextInt(120)));
                for (int i = r.nextInt(count); i < count; i++) {
                    holds.add(new Held(i, openedOn, liftedOn));
                    windows.add(new BnplDisputeHold.Window(dispute, dues.get(i), openedOn, liftedOn));
                }
            }

            // The bounds and the threshold BnplTerms admits: since the Phase 12 transition gate's A7-5 exactly
            // (1, 30, 60, 90) and 90 - the buckets' labels name them. This drew any rising bounds and any threshold in
            // [30, 180] until then; deriving the labels from the bounds (the owner's choice) would bring them back.
            List<Integer> bounds = BnplTerms.DELINQUENCY_BOUNDS;
            int threshold = bounds.get(bounds.size() - 1);
            BnplTerms terms = BnplTermsFixtures.payIn4().delinquencyBounds(bounds).defaultThresholdDays(threshold)
                    .build();
            return new History(terms, List.copyOf(dues), List.copyOf(billed), List.copyOf(ids), first, unpaidByDay,
                    List.copyOf(holds), new BnplDisputeHold(windows), seed);
        }

        private static long total(long[] components) {
            return components[0] + components[1] + components[2];
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

        /** The oracle's hold: a dispute holding instalment {@code i} opened on or before {@code day}, unlifted by it. */
        boolean heldOn(int i, LocalDate day) {
            for (Held held : holds) {
                if (held.item() == i && !held.openedOn().isAfter(day)
                        && (held.liftedOn().isEmpty() || held.liftedOn().get().isAfter(day))) {
                    return true;
                }
            }
            return false;
        }

        /** The oracle's clock start: the later of the due date and every lift on or before {@code day} of its holds. */
        LocalDate clockStart(int i, LocalDate day) {
            LocalDate start = dues.get(i);
            for (Held held : holds) {
                if (held.item() == i && held.liftedOn().isPresent() && !held.liftedOn().get().isAfter(day)
                        && held.liftedOn().get().isAfter(start)) {
                    start = held.liftedOn().get();
                }
            }
            return start;
        }

        /** Whether an instalment due before day {@code d} with something unpaid is held that day - the hold's arm. */
        boolean unpaidPastDueHeldOn(int d) {
            LocalDate today = day(d);
            for (int i = 0; i < dues.size(); i++) {
                if (dues.get(i).isBefore(today) && unpaidByDay[d][i] > 0 && heldOn(i, today)) {
                    return true;
                }
            }
            return false;
        }

        /** Whether an unpaid, unheld instalment counts from a lift after its due date that day - the lift's arm. */
        boolean countsFromALiftOn(int d) {
            LocalDate today = day(d);
            for (int i = 0; i < dues.size(); i++) {
                if (!dues.get(i).isAfter(today) && unpaidByDay[d][i] > 0 && !heldOn(i, today)
                        && clockStart(i, today).isAfter(dues.get(i))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The instalments billed by day {@code d}, as the store hands them over: each component's billed figure, and what
         * every payer settled of it - the settled total split over the components oldest component first.
         */
        List<BnplPurchaseStore.BilledItem> itemsOn(int d) {
            LocalDate today = day(d);
            List<BnplPurchaseStore.BilledItem> items = new ArrayList<>();
            for (int i = 0; i < dues.size(); i++) {
                if (dues.get(i).isAfter(today)) {
                    continue; // not billed yet
                }
                long[] bill = billed.get(i);
                long settled = total(bill) - unpaidByDay[d][i];
                long paidFees = Math.min(settled, bill[0]);
                long paidInterest = Math.min(settled - paidFees, bill[1]);
                long paidPrincipal = settled - paidFees - paidInterest;
                items.add(new BnplPurchaseStore.BilledItem(new UUID(seed, 1000 + i), ids.get(i), dues.get(i),
                        eur(bill[0]), eur(bill[1]), eur(bill[2]), eur(paidFees), eur(paidInterest), eur(paidPrincipal)));
            }
            return items;
        }

        @Override
        public String toString() {
            return "History[seed=" + seed + ", dues=" + dues + ", holds=" + holds + ", threshold="
                    + terms.defaultThresholdDays() + ", bounds=" + terms.delinquencyBounds() + "]";
        }
    }

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }
}
