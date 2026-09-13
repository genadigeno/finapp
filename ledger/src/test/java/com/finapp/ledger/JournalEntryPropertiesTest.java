package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The balance rule over generated line sets (`P3-TSK-004`'s acceptance criterion) — the
 * {@code P0-TST-001} discipline: every sweep asserts its own coverage, because a property test
 * that quietly generated only easy shapes reports strength it does not have.
 *
 * <p>Deterministic seed, so a failure reproduces exactly; the balance re-check is an
 * <strong>independent implementation</strong> ({@code BigDecimal} over the raw minor units),
 * so the sweep does not certify {@code Money} with {@code Money}.
 */
@DisplayName("the balance rule over generated entries (P3-TSK-004)")
class JournalEntryPropertiesTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final LocalDate POSTING = LocalDate.of(2026, 9, 13);
    private static final LocalDate VALUE = LocalDate.of(2026, 9, 13);

    /** JPY(0), USD(2), BHD(3): the three minor-unit shapes the platform's sweeps always use. */
    private enum Sampled {
        JPY,
        USD,
        BHD;

        CurrencyCode code() {
            return CurrencyCode.of(name());
        }
    }

    private static final int TRIALS = 2_000;

    @Test
    @DisplayName("every repaired line set constructs, and every one-unit perturbation throws")
    void balancedConstructsAndPerturbedThrows() {
        Random random = new Random(20260913);
        int multiCurrencyTrials = 0;
        Map<Sampled, Integer> perCurrency = new EnumMap<>(Sampled.class);

        for (int trial = 0; trial < TRIALS; trial++) {
            List<JournalLine> lines = repairedToBalance(randomLines(random), random);

            JournalEntry entry = JournalEntry.balanced(IDS, CLOCK, POSTING, VALUE, lines);
            assertBalancedIndependently(entry);

            Map<Sampled, Boolean> currencies = new EnumMap<>(Sampled.class);
            for (JournalLine line : lines) {
                Sampled sampled = Sampled.valueOf(line.amount().currency().code());
                currencies.put(sampled, true);
                perCurrency.merge(sampled, 1, Integer::sum);
            }
            if (currencies.size() > 1) {
                multiCurrencyTrials++;
            }

            // One minor unit on one random line: the smallest possible wrongness, in whichever
            // currency it lands, must throw - and if a bump of one passes anywhere, INV-LED-01
            // is a rounding error away from decorative.
            List<JournalLine> perturbed = new ArrayList<>(lines);
            int victim = random.nextInt(perturbed.size());
            JournalLine original = perturbed.get(victim);
            perturbed.set(
                    victim,
                    new JournalLine(
                            original.account(),
                            original.direction(),
                            original.amount().plus(
                                    Money.ofPersisted(
                                            1,
                                            original.amount().currency(),
                                            original.amount().scale()))));
            assertThatThrownBy(
                            () -> JournalEntry.balanced(IDS, CLOCK, POSTING, VALUE, perturbed))
                    .isInstanceOf(UnbalancedJournalEntryException.class);
        }

        // The coverage the sweep claims, asserted rather than hoped (P0-TST-001): all three
        // minor-unit shapes were exercised heavily, and a real share of trials mixed
        // currencies - the per-currency clause is only tested where there is more than one.
        for (Sampled sampled : Sampled.values()) {
            assertThat(perCurrency.getOrDefault(sampled, 0))
                    .as("%s lines generated", sampled)
                    .isGreaterThan(TRIALS / 10);
        }
        assertThat(multiCurrencyTrials)
                .as("trials touching more than one currency")
                .isGreaterThan(TRIALS / 4);
    }

    /** 1..6 seed lines: currency, direction and magnitude random; scale the currency's own. */
    private static List<JournalLine> randomLines(Random random) {
        int count = 1 + random.nextInt(6);
        List<JournalLine> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Sampled sampled = Sampled.values()[random.nextInt(Sampled.values().length)];
            long amount = 1 + random.nextLong(1_000_000_000L);
            lines.add(
                    new JournalLine(
                            LedgerAccountId.next(IDS),
                            random.nextBoolean() ? Direction.DEBIT : Direction.CREDIT,
                            Money.ofMinorUnits(amount, sampled.code())));
        }
        return lines;
    }

    /**
     * Appends one balancing line per unbalanced currency, on the lighter side — so every
     * repaired set is balanced by construction of the <em>test</em>, and the subject under
     * test is the factory agreeing.
     */
    private static List<JournalLine> repairedToBalance(List<JournalLine> lines, Random random) {
        List<JournalLine> repaired = new ArrayList<>(lines);
        for (Sampled sampled : Sampled.values()) {
            long net = 0;
            for (JournalLine line : repaired) {
                if (line.amount().currency().code().equals(sampled.name())) {
                    net += line.direction() == Direction.DEBIT
                            ? line.amount().minorUnits()
                            : -line.amount().minorUnits();
                }
            }
            if (net != 0) {
                repaired.add(
                        new JournalLine(
                                LedgerAccountId.next(IDS),
                                net > 0 ? Direction.CREDIT : Direction.DEBIT,
                                Money.ofMinorUnits(Math.abs(net), sampled.code())));
            }
        }
        // A repair can leave a single-line set only if the seed was one already-balanced
        // line, which positive amounts make impossible; still, guarantee >= 2 for the seeds
        // that net to zero with one line... which cannot exist - asserted, not assumed.
        assertThat(repaired.size()).isGreaterThanOrEqualTo(2);
        return repaired;
    }

    /** BigDecimal over raw minor units: the check that does not trust {@code Money}. */
    private static void assertBalancedIndependently(JournalEntry entry) {
        for (Sampled sampled : Sampled.values()) {
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            for (JournalLine line : entry.lines()) {
                if (!line.amount().currency().code().equals(sampled.name())) {
                    continue;
                }
                BigDecimal value =
                        BigDecimal.valueOf(line.amount().minorUnits(), line.amount().scale());
                if (line.direction() == Direction.DEBIT) {
                    debits = debits.add(value);
                } else {
                    credits = credits.add(value);
                }
            }
            assertThat(debits)
                    .as("independent %s balance of %s", sampled, entry.id())
                    .isEqualByComparingTo(credits);
        }
    }
}
