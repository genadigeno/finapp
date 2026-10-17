package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.RefundAllocation.Cancellation;
import com.finapp.bnpl.testing.ReferenceMerchantFeeReturn;
import com.finapp.creditmath.testing.SecondJvm;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code REFUND_ALLOCATION_ENGINE_V1}'s properties over generated purchases and refund sequences (`P12-TSK-009`;
 * ADR-0105 decisions 2-3 and 10; {@code INV-BNPL-06}, {@code -07}, {@code -09}, {@code INV-MER-07} as amended).
 *
 * <p><strong>The population.</strong> Each purchase draws a currency of 0, 2 or 3 decimals, a gross from one minor unit
 * to a million in major units, a fee from zero to just below the gross (a basis-point rate and a fixed part, the
 * merchant's shape), a rounding policy from all six (the engine is policy-agnostic - the fee is the binding's) and a
 * schedule: pay-in-4's own ({@link BnplScheduleEngineV1}) or one to twelve instalments of random weights. Its life is a
 * random interleaving of billings (in due order), part and whole repayments of billed instalments, and refunds - small,
 * large, exactly the remainder, and now and then one cent too many - until the purchase is fully refunded, or, for one
 * purchase in five, until it stops part way.
 *
 * <p><strong>The oracle is this test's bookkeeping</strong>, never the engine's: the due state is kept here from the
 * events, each refund's split is checked against it - value preserved, never a paid amount cancelled, latest first, the
 * classification and wholeness as the state says - and the cancellations applied back to it. The fee is checked against
 * the cumulative identity the merchant's arithmetic promises: what a purchase's refunds return together is
 * {@code cum(Σ R)}, the same whatever the order, and exactly {@code f} at a full refund; the payable debits never exceed
 * {@code G − f}.
 */
@DisplayName("refund allocation engine v1: properties over generated refund sequences, two JVMs (P12-TSK-009)")
class RefundAllocationEngineV1PropertiesTest {

    private static final BnplTerms PAY_IN_4 = BnplTermsFixtures.payIn4().build();

    private static final List<CurrencyCode> CURRENCIES =
            List.of(CurrencyCode.of("EUR"), CurrencyCode.of("JPY"), CurrencyCode.of("BHD"));

    @Test
    @DisplayName("over 6,000 generated purchases: every refund preserves value (R = Σ cancelled + E), never cancels a paid"
            + " amount, cancels latest first, classifies by billing, returns 0 <= r_f <= R; Σ payable debits <= G − f"
            + " throughout; refunds totalling G return exactly f and leave every instalment's unpaid principal zero")
    void everyRefundSequence() {
        SplittableRandom random = new SplittableRandom(0x0b5e55ed_2026_1017L);
        Census census = new Census();
        for (int index = 0; index < 6_000; index++) {
            live(Purchase.draw(random), random, census, split -> { });
        }
        assertThat(census.refunds).as("refunds split").isGreaterThan(15_000);
        assertThat(census.fullRefunds).as("purchases fully refunded").isGreaterThan(4_000);
        assertThat(census.partialStops).as("purchases left part refunded").isGreaterThan(400);
        assertThat(census.unbilledCancelled).as("unbilled cancellations met").isGreaterThan(10_000);
        assertThat(census.billedCancelled).as("billed-unpaid cancellations met").isGreaterThan(2_000);
        assertThat(census.partCancelled).as("instalments only shortened").isGreaterThan(5_000);
        assertThat(census.allExcess).as("refunds that cancel nothing - all excess").isGreaterThan(500);
        assertThat(census.refusedOver).as("refunds one cent over, refused").isGreaterThan(500);
        assertThat(census.feeRoundingMet).as("refunds whose fee share was not a whole minor unit").isGreaterThan(5_000);
    }

    @Test
    @DisplayName("the fee returned is order-independent in total: the same refund amounts in their drawn order and in"
            + " reverse return the same total - cum(Σ R) - and f exactly when they total G")
    void theFeeIsOrderIndependentInTotal() {
        SplittableRandom random = new SplittableRandom(0x7e1e5c0be_2026L);
        for (int index = 0; index < 3_000; index++) {
            Purchase purchase = Purchase.draw(random);
            List<Money> amounts = amounts(purchase, random);
            Money forward = feeTotal(purchase, amounts);
            List<Money> reversed = new ArrayList<>(amounts);
            java.util.Collections.reverse(reversed);
            Money backward = feeTotal(purchase, reversed);
            Money total = Money.zero(purchase.currency);
            for (Money amount : amounts) {
                total = total.plus(amount);
            }
            assertThat(backward).as("%s: reversed order", purchase).isEqualTo(forward);
            assertThat(forward).as("%s: cum(Σ R)", purchase).isEqualTo(purchase.reference.cumulative(total));
            if (total.equals(purchase.gross)) {
                assertThat(forward).as("%s: a full refund returns exactly f", purchase).isEqualTo(purchase.fee);
            }
        }
    }

    // ------------------------------------------------------------------ two JVMs

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone, other identity hash codes - splits every"
            + " refund of 4,000 generated purchases byte for byte")
    void aSecondJvmProducesTheSameBytes() throws Exception {
        List<String> ours = Replay.digests();
        List<String> theirs = SecondJvm.run(Replay.class);
        assertThat(ours).as("one digest per 500 purchases, then the total").hasSize(Replay.PURCHASES / Replay.CHUNK + 1);
        assertThat(theirs).as("compared byte for byte").isEqualTo(ours);
    }

    /** The second JVM's {@code main}: one SHA-256 per 500 purchases' splits of a seeded population, then the total. */
    static final class Replay {

        static final int PURCHASES = 4_000;
        static final int CHUNK = 500;
        static final long SEED = 0x2ef0_2d5_2026_1017L;

        public static void main(String[] arguments) throws Exception {
            SecondJvm.write(Path.of(arguments[0]), digests());
        }

        static List<String> digests() throws Exception {
            SplittableRandom random = new SplittableRandom(SEED);
            List<String> lines = new ArrayList<>();
            MessageDigest all = MessageDigest.getInstance("SHA-256");
            MessageDigest chunk = MessageDigest.getInstance("SHA-256");
            long[] splits = {0};
            for (int index = 0; index < PURCHASES; index++) {
                live(Purchase.draw(random), random, new Census(), split -> {
                    byte[] bytes = canonical(split).getBytes(StandardCharsets.UTF_8);
                    all.update(bytes);
                    chunk.update(bytes);
                    splits[0]++;
                });
                if ((index + 1) % CHUNK == 0) {
                    lines.add((index + 1) + " " + HexFormat.of().formatHex(chunk.digest()));
                }
            }
            lines.add("purchases=" + PURCHASES + " splits=" + splits[0] + " all=" + HexFormat.of().formatHex(all.digest()));
            return lines;
        }

        /** Every figure of a split, in one canonical text. */
        static String canonical(RefundAllocation split) {
            StringBuilder text = new StringBuilder().append(split.engine()).append('|')
                    .append(split.currency().code()).append('|').append(split.refund().minorUnits()).append('|')
                    .append(split.excess().minorUnits()).append('|').append(split.feeReturned().minorUnits()).append('|')
                    .append(split.payableDebit().minorUnits()).append('|').append(split.leavesNoPrincipalUnpaid())
                    .append('\n');
            for (Cancellation cancellation : split.cancellations()) {
                text.append(cancellation.sequence()).append('|').append(cancellation.kind()).append('|')
                        .append(cancellation.amount().minorUnits()).append('|').append(cancellation.whole())
                        .append('\n');
            }
            return text.toString();
        }
    }

    // ------------------------------------------------------------------ the generator and the oracle

    /** Counts of what the population met - so a generator that stopped reaching a case is seen. */
    static final class Census {
        int refunds;
        int fullRefunds;
        int partialStops;
        int unbilledCancelled;
        int billedCancelled;
        int partCancelled;
        int allExcess;
        int refusedOver;
        int feeRoundingMet;
    }

    /** A drawn purchase: its figures, schedule and the fee reference bound to them. */
    static final class Purchase {
        final CurrencyCode currency;
        final Money gross;
        final Money fee;
        final RoundingPolicy policy;
        final long[] principal;
        final ReferenceMerchantFeeReturn reference;
        final boolean stopsPartWay;

        private Purchase(CurrencyCode currency, Money gross, Money fee, RoundingPolicy policy, long[] principal,
                boolean stopsPartWay) {
            this.currency = currency;
            this.gross = gross;
            this.fee = fee;
            this.policy = policy;
            this.principal = principal;
            this.reference = new ReferenceMerchantFeeReturn(gross, fee, policy);
            this.stopsPartWay = stopsPartWay;
        }

        static Purchase draw(SplittableRandom random) {
            CurrencyCode currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
            long unit = pow10(currency.minorUnits());
            long grossMinor = switch (random.nextInt(4)) {
                case 0 -> random.nextLong(1, 100 + 1);
                case 1 -> random.nextLong(4, 1_000 * unit + 1);
                case 2 -> random.nextLong(50 * unit, 5_000 * unit + 1);
                default -> random.nextLong(1, 1_000_000 * unit + 1);
            };
            // The merchant's shape: a rate in basis points and a fixed part, below the gross (ADR-0058).
            long feeMinor = grossMinor * random.nextLong(0, 1_000 + 1) / 10_000 + random.nextLong(0, 3 * unit + 1);
            if (random.nextInt(10) == 0) {
                feeMinor = random.nextLong(0, grossMinor);
            }
            feeMinor = Math.min(feeMinor, grossMinor - 1);
            RoundingPolicy policy = RoundingPolicy.values()[random.nextInt(RoundingPolicy.values().length)];
            Money gross = Money.ofMinorUnits(grossMinor, currency);
            long[] principal = schedule(currency, gross, random);
            return new Purchase(currency, gross, Money.ofMinorUnits(feeMinor, currency), policy, principal,
                    random.nextInt(5) == 0);
        }

        private static long[] schedule(CurrencyCode currency, Money gross, SplittableRandom random) {
            if (currency.code().equals("EUR") && gross.minorUnits() >= 4 && random.nextBoolean()) {
                BnplScheduleOutcome outcome = BnplScheduleEngineV1.project(PAY_IN_4, gross,
                        LocalDate.of(2027, 3, 1));
                List<BnplSchedule.Instalment> instalments = ((BnplScheduleOutcome.Projected) outcome).schedule()
                        .instalments();
                return instalments.stream().mapToLong(instalment -> instalment.principal().minorUnits()).toArray();
            }
            // One minor unit each, the rest by random weights, the remainder on the last - every instalment positive.
            int count = (int) Math.min(gross.minorUnits(), 1 + random.nextInt(12));
            long[] weights = new long[count];
            long weight = 0;
            for (int k = 0; k < count; k++) {
                weights[k] = random.nextLong(1, 100);
                weight += weights[k];
            }
            long rest = gross.minorUnits() - count;
            long[] principal = new long[count];
            long placed = 0;
            for (int k = 0; k < count; k++) {
                principal[k] = 1 + rest * weights[k] / weight;
                placed += principal[k];
            }
            principal[count - 1] += gross.minorUnits() - placed;
            return principal;
        }

        @Override
        public String toString() {
            return currency.code() + " G=" + gross.minorUnits() + " f=" + fee.minorUnits() + " " + policy + " n="
                    + principal.length;
        }
    }

    /**
     * One purchase's life: billings, repayments and refunds interleaved, each refund's split checked against this test's
     * own due state and handed to {@code sink}.
     */
    static void live(Purchase purchase, SplittableRandom random, Census census, Consumer<RefundAllocation> sink) {
        int n = purchase.principal.length;
        CurrencyCode currency = purchase.currency;
        long gross = purchase.gross.minorUnits();
        long fee = purchase.fee.minorUnits();
        boolean[] billed = new boolean[n];
        long[] allocated = new long[n];
        long[] cancelled = new long[n];
        int nextToBill = 0;
        long refunded = 0;
        long feeReturned = 0;
        long payableDebited = 0;
        int refundsLeft = purchase.stopsPartWay ? 1 + random.nextInt(3) : Integer.MAX_VALUE;
        // Instalment 1 is collected at purchase (A5) half the time; the rest of the life is drawn.
        if (random.nextBoolean()) {
            billed[0] = true;
            allocated[0] = purchase.principal[0];
            nextToBill = 1;
        }
        for (int step = 0; step < 400 && refunded < gross && refundsLeft > 0; step++) {
            int act = random.nextInt(10);
            if (act < 3 && nextToBill < n) {
                billed[nextToBill++] = true;
                continue;
            }
            if (act < 5) {
                for (int k = 0; k < n; k++) {
                    long unpaid = purchase.principal[k] - allocated[k] - cancelled[k];
                    if (billed[k] && unpaid > 0) {
                        allocated[k] += random.nextBoolean() ? unpaid : random.nextLong(1, unpaid + 1);
                        break;
                    }
                }
                continue;
            }
            long remaining = gross - refunded;
            if (act == 5 && random.nextInt(4) == 0) {
                RefundAllocationOutcome over = RefundAllocationEngineV1.allocate(
                        position(purchase, refunded, billed, allocated, cancelled),
                        Money.ofMinorUnits(remaining + 1, currency), purchase.reference);
                assertThat(over).as("%s: one minor unit over the remainder", purchase)
                        .isInstanceOfSatisfying(RefundAllocationOutcome.Refused.class, refused ->
                                assertThat(refused.reason()).isEqualTo(RefundAllocationRefusal.REFUND_EXCEEDS_PURCHASE));
                census.refusedOver++;
                continue;
            }
            long amount = switch (random.nextInt(4)) {
                case 0 -> remaining;
                case 1 -> random.nextLong(1, Math.min(remaining, 10) + 1);
                default -> random.nextLong(1, remaining + 1);
            };

            long[] unpaidBefore = new long[n];
            for (int k = 0; k < n; k++) {
                unpaidBefore[k] = purchase.principal[k] - allocated[k] - cancelled[k];
            }
            RefundAllocationOutcome outcome = RefundAllocationEngineV1.allocate(
                    position(purchase, refunded, billed, allocated, cancelled), Money.ofMinorUnits(amount, currency),
                    purchase.reference);
            assertThat(outcome).as("%s: a refund of %d after %d", purchase, amount, refunded)
                    .isInstanceOf(RefundAllocationOutcome.Allocated.class);
            RefundAllocation split = ((RefundAllocationOutcome.Allocated) outcome).allocation();
            sink.accept(split);
            census.refunds++;

            // Value preserved; never a paid amount; classified and judged whole by the state.
            long cancelledNow = 0;
            for (Cancellation cancellation : split.cancellations()) {
                int k = cancellation.sequence() - 1;
                long take = cancellation.amount().minorUnits();
                assertThat(take).as("%s: instalment %d cancels no more than it owes", purchase, k + 1)
                        .isBetween(1L, unpaidBefore[k]);
                assertThat(cancellation.kind()).as("%s: instalment %d classified by billing", purchase, k + 1)
                        .isEqualTo(billed[k] ? Cancellation.Kind.BILLED_UNPAID : Cancellation.Kind.UNBILLED);
                assertThat(cancellation.whole()).as("%s: instalment %d whole", purchase, k + 1)
                        .isEqualTo(take == unpaidBefore[k]);
                cancelled[k] += take;
                cancelledNow += take;
                if (billed[k]) {
                    census.billedCancelled++;
                } else {
                    census.unbilledCancelled++;
                }
                if (!cancellation.whole()) {
                    census.partCancelled++;
                }
            }
            assertThat(cancelledNow + split.excess().minorUnits()).as("%s: R = Σ cancelled + E", purchase)
                    .isEqualTo(amount);
            if (split.cancellations().isEmpty()) {
                census.allExcess++;
            }
            // Latest first: no instalment cancelled while a later one still owes principal; an excess only once nothing
            // is owed at all.
            long unpaidAfter = 0;
            for (int k = 0; k < n; k++) {
                long unpaid = purchase.principal[k] - allocated[k] - cancelled[k];
                unpaidAfter += unpaid;
                if (cancelled[k] > 0 && cancelledBy(split, k + 1)) {
                    for (int later = k + 1; later < n; later++) {
                        assertThat(purchase.principal[later] - allocated[later] - cancelled[later])
                                .as("%s: instalment %d cancelled while instalment %d owes", purchase, k + 1, later + 1)
                                .isZero();
                    }
                }
            }
            if (split.excess().isPositive()) {
                assertThat(unpaidAfter).as("%s: an excess only once nothing is owed", purchase).isZero();
            }
            assertThat(split.leavesNoPrincipalUnpaid()).as("%s: the full-cancellation fact", purchase)
                    .isEqualTo(unpaidAfter == 0);

            // The fee: 0 <= r_f <= R, its total cum(Σ R), the payable debits within G − f.
            long returned = split.feeReturned().minorUnits();
            assertThat(returned).as("%s: 0 <= r_f <= R", purchase).isBetween(0L, amount);
            assertThat(split.payableDebit().minorUnits()).isEqualTo(amount - returned);
            if (purchase.reference.cumulative(Money.ofMinorUnits(amount, currency)).minorUnits() * gross
                    != fee * amount) {
                census.feeRoundingMet++;
            }
            refunded += amount;
            feeReturned += returned;
            payableDebited += amount - returned;
            assertThat(feeReturned).as("%s: Σ r_f = cum(Σ R)", purchase)
                    .isEqualTo(purchase.reference.cumulative(Money.ofMinorUnits(refunded, currency)).minorUnits());
            assertThat(payableDebited).as("%s: Σ payable debits <= G − f (INV-BNPL-07, INV-MER-07)", purchase)
                    .isLessThanOrEqualTo(gross - fee);
            refundsLeft--;
        }
        if (refunded == gross) {
            census.fullRefunds++;
            assertThat(feeReturned).as("%s: a full refund returns exactly f", purchase).isEqualTo(fee);
            assertThat(payableDebited).as("%s: and debits the payable exactly G − f", purchase).isEqualTo(gross - fee);
            for (int k = 0; k < n; k++) {
                assertThat(purchase.principal[k] - allocated[k] - cancelled[k])
                        .as("%s: instalment %d owes nothing after a full refund (INV-BNPL-09)", purchase, k + 1).isZero();
            }
        } else if (refundsLeft == 0) {
            census.partialStops++;
        }
    }

    private static boolean cancelledBy(RefundAllocation split, int sequence) {
        return split.cancellations().stream().anyMatch(cancellation -> cancellation.sequence() == sequence);
    }

    private static RefundPosition position(Purchase purchase, long refunded, boolean[] billed, long[] allocated,
            long[] cancelled) {
        CurrencyCode currency = purchase.currency;
        List<RefundPosition.Instalment> instalments = new ArrayList<>();
        for (int k = 0; k < purchase.principal.length; k++) {
            instalments.add(new RefundPosition.Instalment(k + 1, Money.ofMinorUnits(purchase.principal[k], currency),
                    billed[k], Money.ofMinorUnits(allocated[k], currency), Money.ofMinorUnits(cancelled[k], currency)));
        }
        return new RefundPosition(purchase.gross, purchase.fee, Money.ofMinorUnits(refunded, currency), instalments);
    }

    /** Refund amounts drawn to total G (four in five) or less. */
    private static List<Money> amounts(Purchase purchase, SplittableRandom random) {
        long remaining = purchase.gross.minorUnits();
        boolean full = random.nextInt(5) != 0;
        List<Money> amounts = new ArrayList<>();
        while (remaining > 0 && amounts.size() < 8) {
            long amount = amounts.size() == 7 && full ? remaining : random.nextLong(1, remaining + 1);
            amounts.add(Money.ofMinorUnits(amount, purchase.currency));
            remaining -= amount;
            if (!full && random.nextInt(3) == 0) {
                break;
            }
        }
        return amounts;
    }

    /** Σ r_f over {@code amounts} refunded in order against a purchase whose every instalment is paid. */
    private static Money feeTotal(Purchase purchase, List<Money> amounts) {
        CurrencyCode currency = purchase.currency;
        List<RefundPosition.Instalment> paid = new ArrayList<>();
        for (int k = 0; k < purchase.principal.length; k++) {
            Money principal = Money.ofMinorUnits(purchase.principal[k], currency);
            paid.add(new RefundPosition.Instalment(k + 1, principal, true, principal, Money.zero(currency)));
        }
        Money refunded = Money.zero(currency);
        Money total = Money.zero(currency);
        for (Money amount : amounts) {
            RefundAllocationOutcome outcome = RefundAllocationEngineV1.allocate(
                    new RefundPosition(purchase.gross, purchase.fee, refunded, paid), amount, purchase.reference);
            total = total.plus(((RefundAllocationOutcome.Allocated) outcome).allocation().feeReturned());
            refunded = refunded.plus(amount);
        }
        return total;
    }

    private static long pow10(int exponent) {
        long value = 1;
        for (int k = 0; k < exponent; k++) {
            value *= 10;
        }
        return value;
    }
}
