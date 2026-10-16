package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.AmortisationWorld.Arithmetic;
import com.finapp.lending.AmortisationWorld.Generated;
import com.finapp.lending.AmortisationWorld.Kind;
import com.finapp.lending.AmortisationWorld.Tag;
import com.finapp.lending.LoanReplayProof.Check;
import com.finapp.lending.LoanReplayProof.Verdict;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The amortisation, accrual and statement battery (`P11-TST-002`; PHASE_11_PLAN.md section 13.3; {@code INV-LND-03},
 * {@code INV-LND-04}, {@code INV-LND-05}, {@code INV-LND-09}): a seeded portfolio of at least 10,000 instalment loans and
 * 2,000 credit lines ({@link AmortisationWorld}), every stored figure the battery's own arithmetic
 * ({@link AmortisationOracles}), replayed through the pinned engines by {@link LoanReplayProof} and proven by
 * {@link LoanSubledgerProof} - here and in a second JVM.
 *
 * <ul>
 *   <li>every account {@code IDENTICAL}, its subledger proof holding, its rounding residual zero per period, per item, per
 *       payment and over its life;
 *   <li>one perturbation per account - an accrual, a billing, a statement, an allocation, an agreement byte, an engine
 *       number - each flipping the verdict and naming its fact;
 *   <li>a slice re-written by an engine SWAPPED under its stored number (double rounding; the minimum payment uncapped):
 *       {@code DIVERGED} for exactly the accounts the writer predicted;
 *   <li>the second JVM's lines equal to this one's, every line.
 * </ul>
 *
 * <p>Hermetic (ADR-0028 tiers by requirement): it needs only the JVM. The size is
 * {@code FINAPP_LENDING_BATTERY_LOANS} / {@code FINAPP_LENDING_BATTERY_LINES} for a probe's quick run only - a shrunk run
 * fails {@link #theCensus} by design, so it can never pass for the gate's.
 */
@DisplayName("P11-TST-002 - the amortisation, accrual and statement battery: exact, replayed IDENTICAL in two JVMs")
class AmortisationBatteryTest {

    static final int LOANS = size("FINAPP_LENDING_BATTERY_LOANS", 10_000);
    static final int LINES = size("FINAPP_LENDING_BATTERY_LINES", 2_000);

    /** Every tenth account is also re-written by a swapped arithmetic (Q5's default). */
    static final int SLICE = 10;

    /**
     * The population's fingerprint at {@link AmortisationWorld#SEED} and full size - moved only by a reviewed change. Moved
     * by the Phase 11 gate's R5 (2026-10-16): the population's rates are drawn up to the terms door's highest admitted rate,
     * which the door's margin moved from 0.23106446 to 0.23090888 - with the old rate pinned the generator reproduced the
     * old fingerprint {@code 6830b62c...7926} and every case of this suite green, so nothing else moved it.
     */
    static final String FINGERPRINT = "b833918a0e1afcdace5c43af2363bac8a142dd42792f0b5b2a06a5433954a433";

    private static final LoanReplayProof REPLAY = new LoanReplayProof();
    private static final LoanSubledgerProof SUBLEDGER = new LoanSubledgerProof();

    private static List<Outcome> outcomes;
    private static List<String> ours;
    private static List<String> theirs;
    private static String fingerprint;

    /** The perturbations, one per account. */
    enum Perturbation {
        ACCRUAL,
        BILLING,
        STATEMENT,
        ALLOCATION,
        AGREEMENT_BYTE,
        ENGINE_NUMBER
    }

    /**
     * A perturbed or swapped record's replay. {@code namesItsRow}: the findings name the changed row itself under the
     * expected check - not merely the check's kind on some other row (a swapped slice changes many rows: always true).
     */
    record Flip(String what, Verdict verdict, Set<Check> checks, boolean ledgerHolds, boolean namesItsRow) {}

    /** What one account came to - its record discarded. */
    record Outcome(Kind kind, int index, String behaviour, AmortisationInAnotherJvm.Summary summary, Verdict verdict,
            int replayed, Set<Check> checks, List<String> ledgerFailures, List<String> residualFailures,
            int onTimeBillingsChecked, List<String> onTimeFailures, Perturbation perturbation, Flip perturbed,
            Optional<Arithmetic> swapped, boolean predicted, Optional<Flip> swappedReplay, long[] tags, int zone,
            int format, int term, int day, LocalDate start, boolean rateZero, boolean roundingSensitive, boolean capBinds,
            Optional<AmortisationWorld.ScheduleInput> scheduleInput, long generationNanos, long replayNanos) {}

    // ------------------------------------------------------------------ the run

    @BeforeAll
    static void battery() throws Exception {
        long begun = System.nanoTime();
        // The second JVM first, so it works while this one does.
        SecondJvm.Started child = SecondJvm.start(AmortisationInAnotherJvm.class, "1g", Integer.toString(LOANS),
                Integer.toString(LINES));
        long parentNanos;
        long[] cost;
        try {
            outcomes = IntStream.range(0, LOANS + LINES).parallel().mapToObj(AmortisationBatteryTest::outcome).toList();
            parentNanos = System.nanoTime() - begun;
            cost = scheduleCost();
        } catch (RuntimeException | Error failed) {
            child.abandon(); // this JVM's pass failed: no orphaned child, no temporary files
            throw failed;
        }
        theirs = child.join(Duration.ofMinutes(30));
        long wallNanos = System.nanoTime() - begun;
        ours = AmortisationInAnotherJvm.lines(outcomes.stream().map(Outcome::summary).toList(), LOANS, LINES);
        MessageDigest all = AmortisationInAnotherJvm.sha256();
        outcomes.forEach(outcome -> all.update(outcome.summary().digest().getBytes(StandardCharsets.UTF_8)));
        fingerprint = HexFormat.of().formatHex(all.digest());
        long generation = outcomes.stream().mapToLong(Outcome::generationNanos).sum();
        long replay = outcomes.stream().mapToLong(Outcome::replayNanos).sum();
        System.out.println("P11-TST-002 TIMING: wall=" + millis(wallNanos) + "ms parentPass=" + millis(parentNanos)
                + "ms secondJvm=" + millis(child.elapsedNanos()) + "ms threadSummed{generation=" + millis(generation)
                + "ms, replayAndProofs=" + millis(replay) + "ms} processors=" + Runtime.getRuntime().availableProcessors() + " scheduleEngineV1.project{mean="
                + cost[0] + "ns over " + cost[1] + " projections, 55-60-month mean=" + cost[2] + "ns over " + cost[3]
                + "}");
        System.out.println("P11-TST-002 FINGERPRINT: seed=" + AmortisationWorld.SEED + " loans=" + LOANS + " lines="
                + LINES + " fingerprint=" + fingerprint);
        System.out.println("P11-TST-002 CENSUS: " + census());
        System.out.println("P11-TST-002 REPLAY: " + replaySummary());
    }

    /** One account through the whole pipeline: generated, digested, replayed, proven, perturbed, swapped. */
    private static Outcome outcome(int position) {
        Kind kind = position < LOANS ? Kind.LOAN : Kind.LINE;
        int index = kind == Kind.LOAN ? position : position - LOANS;
        long begun = System.nanoTime();
        Generated generated = AmortisationWorld.generate(kind, index, Arithmetic.STANDARD);
        LoanRecord record = generated.record();
        long generatedAt = System.nanoTime();
        LoanReplayProof.Replay replay = REPLAY.replay(record);
        LoanSubledgerProof.Verdict ledger = SUBLEDGER.prove(record);
        AmortisationInAnotherJvm.Summary summary = AmortisationInAnotherJvm.summary(kind, index, record, replay);
        long replayedAt = System.nanoTime();
        List<String> residuals = Residuals.of(record);
        List<String> onTime = new ArrayList<>();
        int onTimeChecked = generated.behaviour().equals(AmortisationWorld.LoanCohort.ON_TIME_ON_DUE_DATE.name())
                ? onTimeExact(record, onTime) : 0;

        Perturbation perturbation = perturbationOf(generated);
        LoanRecord perturbedRecord = Perturbed.of(perturbation, generated);
        LoanReplayProof.Replay perturbed = REPLAY.replay(perturbedRecord);
        boolean perturbedHolds = perturbation != Perturbation.AGREEMENT_BYTE || SUBLEDGER.prove(perturbedRecord).holds();
        boolean namesItsRow = perturbed.findings().contains(new LoanReplayProof.Finding(Perturbed.named(perturbation),
                Perturbed.subject(perturbation, generated.record())));
        Flip flip = new Flip(Perturbed.what(perturbation, generated), perturbed.verdict(), perturbed.checks(),
                perturbedHolds, namesItsRow);

        Optional<Arithmetic> swapped = swappedArithmetic(kind, index);
        boolean predicted = false;
        Optional<Flip> swappedReplay = Optional.empty();
        if (swapped.isPresent()) {
            Generated alternate = AmortisationWorld.generate(kind, index, swapped.get());
            predicted = swapped.get() == Arithmetic.ROUNDED_TWICE ? alternate.roundingSensitive() : alternate.capBinds();
            LoanReplayProof.Replay alternateReplay = REPLAY.replay(alternate.record());
            swappedReplay = Optional.of(new Flip(swapped.get().name(), alternateReplay.verdict(), alternateReplay.checks(),
                    true, true));
        }
        long finished = System.nanoTime();
        return new Outcome(kind, index, generated.behaviour(), summary, replay.verdict(), replay.replayed(),
                replay.checks(), ledger.failures(), residuals, onTimeChecked, onTime, perturbation, flip, swapped,
                predicted, swappedReplay, generated.tags(), generated.zone(), generated.format(), generated.term(),
                generated.day(), generated.start(), new BigDecimal(record.agreements().get(0).nominalAnnualRate())
                        .signum() == 0, generated.roundingSensitive(), generated.capBinds(), generated.scheduleInput(),
                generatedAt - begun, (replayedAt - generatedAt) + (finished - replayedAt));
    }

    // ------------------------------------------------------------------ the cases

    @Test
    @DisplayName("the census: at least 10,000 loans and 2,000 lines, every coverage tag non-zero, every perturbation kind"
            + " on at least 1,000 accounts, both swapped arithmetics' predicted sets non-empty, the fingerprint equal")
    void theCensus() {
        assertThat(LOANS).as("loans").isGreaterThanOrEqualTo(10_000);
        assertThat(LINES).as("lines").isGreaterThanOrEqualTo(2_000);
        List<Outcome> loans = of(Kind.LOAN);
        List<Outcome> lines = of(Kind.LINE);
        assertThat(loans.stream().filter(Outcome::rateZero).count()).as("zero-rate loans")
                .isGreaterThanOrEqualTo(LOANS / 20);
        assertThat(lines.stream().filter(Outcome::rateZero).count()).as("zero-rate lines").isPositive();
        assertThat(loans.stream().map(Outcome::term).collect(Collectors.toSet())).as("every term 6-60")
                .isEqualTo(IntStream.rangeClosed(6, 60).boxed().collect(Collectors.toSet()));
        Set<Integer> days = IntStream.rangeClosed(1, 28).boxed().collect(Collectors.toSet());
        assertThat(loans.stream().map(Outcome::day).collect(Collectors.toSet())).as("every repayment day").isEqualTo(days);
        assertThat(lines.stream().map(Outcome::day).collect(Collectors.toSet())).as("every statement day").isEqualTo(days);
        assertThat(loans.stream().map(Outcome::start).collect(Collectors.toSet())).as("a loan starting on every day of"
                + " 2027 and 2028").containsAll(IntStream.range(0, AmortisationWorld.START_DAYS)
                        .mapToObj(AmortisationWorld.FIRST_DAY::plusDays).toList());
        assertThat(loans.stream().map(Outcome::start).filter(start -> start.getMonth() == Month.FEBRUARY
                && start.getDayOfMonth() == 29).count()).as("loans starting on 29 February 2028").isPositive();
        for (Kind kind : Kind.values()) {
            for (int zone = 0; zone < AmortisationWorld.ZONES.size(); zone++) {
                int z = zone;
                assertThat(of(kind).stream().filter(outcome -> outcome.zone() == z).count())
                        .as(kind + " in zone " + AmortisationWorld.ZONES.get(zone)).isPositive();
            }
            for (int format = 1; format <= 2; format++) {
                int f = format;
                assertThat(of(kind).stream().filter(outcome -> outcome.format() == f).count())
                        .as(kind + " agreements in canonical format " + format).isPositive();
            }
        }
        for (AmortisationWorld.LoanCohort cohort : AmortisationWorld.LoanCohort.values()) {
            assertThat(loans.stream().filter(outcome -> outcome.behaviour().equals(cohort.name())).count())
                    .as("loan cohort " + cohort).isPositive();
        }
        for (AmortisationWorld.LineProfile profile : AmortisationWorld.LineProfile.values()) {
            assertThat(lines.stream().filter(outcome -> outcome.behaviour().equals(profile.name())).count())
                    .as("line profile " + profile).isPositive();
        }
        Map<Tag, Long> loanTags = tags(loans);
        Map<Tag, Long> lineTags = tags(lines);
        for (Tag tag : List.of(Tag.ACCRUALS, Tag.ZERO_POSTINGS, Tag.LEAP_DAY_ACCRUALS, Tag.BILLINGS,
                Tag.POST_MATURITY_BILLINGS, Tag.PAYMENTS, Tag.MULTI_ITEM_PAYMENTS, Tag.APPLICATIONS, Tag.LATE_FEES,
                Tag.LATE_FEES_CAPPED, Tag.LATE_FEE_CAP_REACHED, Tag.CLOSURES, Tag.CLOSURES_BY_PAYMENT,
                Tag.CLOSURES_BY_STEP, Tag.REFUNDS, Tag.CREDIT_HELD, Tag.RAISED_SCHEDULES, Tag.ZERO_RATE,
                Tag.MONTH_END_STARTS, Tag.FORMAT_1, Tag.FORMAT_2)) {
            assertThat(loanTags.get(tag)).as("loans: " + tag).isPositive();
        }
        for (Tag tag : List.of(Tag.ACCRUALS, Tag.ZERO_POSTINGS, Tag.LEAP_DAY_ACCRUALS, Tag.IDLE_DAYS, Tag.STATEMENTS,
                Tag.EMPTY_STATEMENTS, Tag.FLOOR_DOMINATES, Tag.RATIO_DOMINATES, Tag.CAP_DOMINATES, Tag.PAYMENTS,
                Tag.MULTI_ITEM_PAYMENTS, Tag.APPLICATIONS, Tag.LATE_FEES, Tag.LATE_FEES_CAPPED, Tag.LATE_FEE_CAP_REACHED,
                Tag.PAYDOWNS, Tag.CREDIT_HELD, Tag.DRAWS,
                Tag.ZERO_RATE, Tag.MONTH_END_STARTS, Tag.FORMAT_1, Tag.FORMAT_2)) {
            assertThat(lineTags.get(tag)).as("lines: " + tag).isPositive();
        }
        // Floors at about half of the figures the full-size population is recorded with (BACKLOG.md's as-built), so a
        // change to the generator that thins a case out fails here and not only in the fingerprint a reviewer may re-pin.
        Map<Tag, Long> loanFloors = new EnumMap<>(Map.of(Tag.LEAP_DAY_ACCRUALS, 3_500L, Tag.POST_MATURITY_BILLINGS,
                2_500L, Tag.LATE_FEES_CAPPED, 750L, Tag.CLOSURES, 4_000L, Tag.REFUNDS, 500L, Tag.RAISED_SCHEDULES, 70L,
                Tag.MONTH_END_STARTS, 700L));
        Map<Tag, Long> lineFloors = new EnumMap<>(Map.of(Tag.LEAP_DAY_ACCRUALS, 350L, Tag.EMPTY_STATEMENTS, 4_500L,
                Tag.FLOOR_DOMINATES, 3_000L, Tag.RATIO_DOMINATES, 8_000L, Tag.CAP_DOMINATES, 8_000L,
                Tag.LATE_FEES_CAPPED, 130L, Tag.DRAWS, 14_000L));
        loanFloors.forEach((tag, floor) -> assertThat(loanTags.get(tag)).as("loans: " + tag).isGreaterThanOrEqualTo(floor));
        lineFloors.forEach((tag, floor) -> assertThat(lineTags.get(tag)).as("lines: " + tag).isGreaterThanOrEqualTo(floor));
        assertThat(lines.stream().filter(Outcome::capBinds).count()).as("lines on which the cap binds")
                .isGreaterThanOrEqualTo(600);
        assertThat(lines.stream().map(outcome -> outcome.start().getYear()).collect(Collectors.toSet()))
                .as("lines starting in 2027 and in 2028").contains(2027, 2028);
        for (Perturbation perturbation : Perturbation.values()) {
            assertThat(outcomes.stream().filter(outcome -> outcome.perturbation() == perturbation).count())
                    .as("accounts perturbed by " + perturbation).isGreaterThanOrEqualTo(1_000);
        }
        // The selections are the mix's, not the index's: the swapped loans meet every term and repayment day, and the
        // lines whose statement is perturbed - and the swapped lines - meet every statement day.
        List<Outcome> swappedLoans = loans.stream().filter(outcome -> outcome.swapped().isPresent()).toList();
        assertThat(swappedLoans.stream().map(Outcome::term).collect(Collectors.toSet())).as("swapped loans: every term")
                .isEqualTo(IntStream.rangeClosed(6, 60).boxed().collect(Collectors.toSet()));
        assertThat(swappedLoans.stream().map(Outcome::day).collect(Collectors.toSet()))
                .as("swapped loans: every repayment day").isEqualTo(days);
        assertThat(lines.stream().filter(outcome -> outcome.perturbation() == Perturbation.STATEMENT).map(Outcome::day)
                .collect(Collectors.toSet())).as("statement-perturbed lines: every statement day").isEqualTo(days);
        assertThat(lines.stream().filter(outcome -> outcome.swapped().isPresent()).map(Outcome::day)
                .collect(Collectors.toSet())).as("swapped lines: every statement day").isEqualTo(days);
        for (Arithmetic arithmetic : List.of(Arithmetic.ROUNDED_TWICE, Arithmetic.UNCAPPED)) {
            assertThat(outcomes.stream().filter(outcome -> outcome.swapped().equals(Optional.of(arithmetic)))
                    .filter(Outcome::predicted).count()).as("accounts predicted to diverge under " + arithmetic)
                    .isPositive();
        }
        assertThat(fingerprint).as("the population's fingerprint (seed " + AmortisationWorld.SEED + ")")
                .isEqualTo(FINGERPRINT);
    }

    @Test
    @DisplayName("every account replays IDENTICAL, re-deriving something, and its subledger proof holds")
    void everyAccountReplaysIdentical() {
        List<String> diverged = outcomes.stream().filter(outcome -> outcome.verdict() != Verdict.IDENTICAL)
                .map(outcome -> outcome.kind() + " " + outcome.index() + " " + outcome.checks()).toList();
        assertThat(diverged).as("accounts DIVERGED - " + replaySummary()).isEmpty();
        List<String> idle = outcomes.stream().filter(outcome -> outcome.replayed() <= 0)
                .map(outcome -> outcome.kind() + " " + outcome.index()).toList();
        assertThat(idle).as("accounts re-deriving nothing").isEmpty();
        List<String> failing = outcomes.stream().filter(outcome -> !outcome.ledgerFailures().isEmpty())
                .map(outcome -> outcome.kind() + " " + outcome.index() + " " + outcome.ledgerFailures()).toList();
        assertThat(failing).as("subledger proofs that do not hold").isEmpty();
    }

    @Test
    @DisplayName("the rounding residual is zero per loan and per line: schedules conserve, each period's postings are its"
            + " exact interest rounded once, every item bills what accrued, every payment sums, a closed loan's money in"
            + " is its principal, interest and fees")
    void theRoundingResidualIsZeroPerLoanAndLine() {
        List<String> failing = outcomes.stream().filter(outcome -> !outcome.residualFailures().isEmpty())
                .map(outcome -> outcome.kind() + " " + outcome.index() + " " + outcome.residualFailures()).toList();
        assertThat(failing).as("accounts with a residual").isEmpty();
    }

    @Test
    @DisplayName("on-time loans are billed exactly the schedule's interest and principal, instalment by instalment")
    void onTimeLoansAreBilledExactlyTheScheduledInterest() {
        long checked = outcomes.stream().mapToLong(Outcome::onTimeBillingsChecked).sum();
        System.out.println("P11-TST-002 ON-TIME: " + outcomes.stream().filter(outcome -> outcome.onTimeBillingsChecked() > 0)
                .count() + " loans, " + checked + " instalment billings compared with their schedule rows");
        assertThat(checked).as("on-time instalments compared").isPositive();
        List<String> failing = outcomes.stream().filter(outcome -> !outcome.onTimeFailures().isEmpty())
                .map(outcome -> "LOAN " + outcome.index() + " " + outcome.onTimeFailures()).toList();
        assertThat(failing).as("on-time loans billed other than their schedule").isEmpty();
    }

    @Test
    @DisplayName("a perturbed accrual diverges, naming ACCRUAL")
    void aPerturbedAccrualDiverges() {
        assertFlipped(Perturbation.ACCRUAL, Check.ACCRUAL);
    }

    @Test
    @DisplayName("a perturbed billing diverges, naming BILLING")
    void aPerturbedBillingDiverges() {
        assertFlipped(Perturbation.BILLING, Check.BILLING);
    }

    @Test
    @DisplayName("a perturbed statement diverges, naming STATEMENT")
    void aPerturbedStatementDiverges() {
        assertFlipped(Perturbation.STATEMENT, Check.STATEMENT);
    }

    @Test
    @DisplayName("a minor unit moved between two allocation lines of one payment diverges, naming ALLOCATION")
    void aPerturbedAllocationDiverges() {
        assertFlipped(Perturbation.ALLOCATION, Check.ALLOCATION);
    }

    @Test
    @DisplayName("a forged agreement byte, re-hashed as a forger would, diverges by TERMS alone - the subledger still"
            + " holds")
    void aForgedAgreementByteDivergesOnlyByItsHash() {
        List<Outcome> forged = perturbedBy(Perturbation.AGREEMENT_BYTE);
        assertThat(forged).as("forged agreements").isNotEmpty();
        System.out.println("P11-TST-002 PERTURBED AGREEMENT_BYTE: " + forged.size() + " accounts, "
                + forged.stream().filter(outcome -> outcome.perturbed().checks().equals(Set.of(Check.TERMS))).count()
                + " DIVERGED by TERMS alone, " + forged.stream().filter(outcome -> outcome.perturbed().verdict()
                        == Verdict.IDENTICAL).count() + " IDENTICAL, subledger holding on "
                + forged.stream().filter(outcome -> outcome.perturbed().ledgerHolds()).count());
        List<String> wrong = forged.stream().filter(outcome -> outcome.perturbed().verdict() != Verdict.DIVERGED
                || !outcome.perturbed().checks().equals(Set.of(Check.TERMS)) || !outcome.perturbed().namesItsRow()
                || !outcome.perturbed().ledgerHolds())
                .map(outcome -> outcome.kind() + " " + outcome.index() + " " + outcome.perturbed()).toList();
        assertThat(wrong).as("forged agreements not DIVERGED by TERMS alone with the money untouched").isEmpty();
    }

    @Test
    @DisplayName("an engine number this build does not hold, pinned on the agreement, diverges - ENGINE_ABSENT - for each"
            + " of the four engines")
    void anEngineNumberNotHeldDiverges() {
        List<Outcome> swapped = perturbedBy(Perturbation.ENGINE_NUMBER);
        assertFlipped(Perturbation.ENGINE_NUMBER, Check.ENGINE_ABSENT);
        Map<String, Long> perEngine = new TreeMap<>();
        swapped.forEach(outcome -> perEngine.merge(outcome.perturbed().what(), 1L, Long::sum));
        System.out.println("P11-TST-002 ENGINE_NUMBER: " + perEngine);
        assertThat(perEngine.keySet()).as("every engine's number swapped").containsExactlyInAnyOrder(
                "SCHEDULE", "ACCRUAL", "ALLOCATION", "STATEMENT");
    }

    @Test
    @DisplayName("an engine swapped under its stored number - the accrual rounded twice, the minimum payment uncapped -"
            + " diverges for exactly the accounts the writer predicted, IDENTICAL for the rest")
    void anEngineSwappedUnderItsNumberDivergesExactlyThePredictedAccounts() {
        Map<String, long[]> counts = new TreeMap<>();
        List<String> wrong = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            if (outcome.swapped().isEmpty()) {
                continue;
            }
            Arithmetic arithmetic = outcome.swapped().get();
            Flip replay = outcome.swappedReplay().orElseThrow();
            boolean diverged = replay.verdict() == Verdict.DIVERGED;
            long[] count = counts.computeIfAbsent(outcome.kind() + "/" + arithmetic, key -> new long[3]);
            count[0]++;
            count[1] += outcome.predicted() ? 1 : 0;
            count[2] += diverged ? 1 : 0;
            Check named = arithmetic == Arithmetic.ROUNDED_TWICE ? Check.ACCRUAL : Check.STATEMENT;
            if (diverged != outcome.predicted() || (diverged && !replay.checks().contains(named))) {
                wrong.add(outcome.kind() + " " + outcome.index() + " " + arithmetic + " predicted=" + outcome.predicted()
                        + " " + replay.verdict() + " " + replay.checks());
            }
        }
        StringBuilder text = new StringBuilder();
        counts.forEach((key, count) -> text.append(key).append("{rewritten=").append(count[0]).append(", predicted=")
                .append(count[1]).append(", diverged=").append(count[2]).append("} "));
        System.out.println("P11-TST-002 SWAPPED: " + text.toString().strip());
        assertThat(wrong).as("swapped accounts whose verdict is not the prediction").isEmpty();
        assertThat(counts).as("both arithmetics, on both kinds where they apply").containsKeys(
                "LOAN/ROUNDED_TWICE", "LINE/ROUNDED_TWICE", "LINE/UNCAPPED");
    }

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone, other identity hash codes - generates and"
            + " replays every account to the same line, byte for byte")
    void aSecondJvmReplaysIdentically() {
        System.out.println("P11-TST-002 TWO-JVM: ours=" + ours.size() + " theirs=" + theirs.size() + " totals(ours)="
                + ours.get(ours.size() - 1) + " totals(theirs)=" + theirs.get(theirs.size() - 1));
        assertThat(ours).as("one line per account and the totals").hasSize(LOANS + LINES + 1);
        assertThat(theirs).as("the second JVM's lines, compared line for line").isEqualTo(ours);
    }

    // ------------------------------------------------------------------ the perturbations

    /**
     * A stable 64-bit mix of an account's index (splitmix64's finaliser). Every selection below - the perturbation's
     * rotation, the engine swapped, the swapped slice - is taken from it and never from the index itself: the generator
     * walks the index ({@code term = 6 + index % 55}, {@code day = 1 + index % 28}, a zero rate on {@code index % 16}), so
     * an index-modulo selection would cover a lattice of the grid (the gate's finding) where the mix covers all of it.
     */
    static long mix(Kind kind, int index) {
        long z = (kind == Kind.LOAN ? 0x4C4F414EL : 0x4C494E45L) * 0x9E3779B97F4A7C15L + index;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** {@code mix}'s bits from {@code shift}, reduced modulo {@code modulus} - never negative. */
    static int pick(Kind kind, int index, int shift, int modulus) {
        return (int) Math.floorMod(mix(kind, index) >>> shift, (long) modulus);
    }

    /** The half of the lines - exactly half, by the mix's rank - whose perturbation is their statement. */
    private static final boolean[] STATEMENT_HALF = statementHalf();

    private static boolean[] statementHalf() {
        boolean[] half = new boolean[LINES];
        IntStream.range(0, LINES).boxed().sorted(Comparator.comparingLong(index -> mix(Kind.LINE, index)))
                .limit(LINES / 2).forEach(index -> half[index] = true);
        return half;
    }

    /** One per account, rotated by the mix - every kind on at least 1,000 accounts at full size. */
    static Perturbation perturbationOf(Generated generated) {
        int index = generated.index();
        List<Perturbation> kinds;
        if (generated.kind() == Kind.LOAN) {
            kinds = List.of(Perturbation.ACCRUAL, Perturbation.BILLING, Perturbation.ALLOCATION,
                    Perturbation.AGREEMENT_BYTE, Perturbation.ENGINE_NUMBER);
            int first = pick(Kind.LOAN, index, 0, kinds.size());
            for (int i = 0; i < kinds.size(); i++) {
                Perturbation kind = kinds.get((first + i) % kinds.size());
                if (Perturbed.applies(kind, generated.record())) {
                    return kind;
                }
            }
        } else {
            if (STATEMENT_HALF[index] && Perturbed.applies(Perturbation.STATEMENT, generated.record())) {
                return Perturbation.STATEMENT;
            }
            kinds = List.of(Perturbation.ACCRUAL, Perturbation.ALLOCATION, Perturbation.AGREEMENT_BYTE,
                    Perturbation.ENGINE_NUMBER);
            int first = pick(Kind.LINE, index, 0, kinds.size());
            for (int i = 0; i < kinds.size(); i++) {
                Perturbation kind = kinds.get((first + i) % kinds.size());
                if (Perturbed.applies(kind, generated.record())) {
                    return kind;
                }
            }
        }
        return Perturbation.AGREEMENT_BYTE;
    }

    /** One loan in ten (by the mix) rounded twice; one line in ten, by the mix's next bit, rounded twice or uncapped. */
    static Optional<Arithmetic> swappedArithmetic(Kind kind, int index) {
        if (pick(kind, index, 24, SLICE) != 0) {
            return Optional.empty();
        }
        if (kind == Kind.LOAN) {
            return Optional.of(Arithmetic.ROUNDED_TWICE);
        }
        return Optional.of(pick(kind, index, 40, 2) == 0 ? Arithmetic.UNCAPPED : Arithmetic.ROUNDED_TWICE);
    }

    /** A copy of a record with one fact changed - the original untouched (it already replayed IDENTICAL). */
    static final class Perturbed {

        private Perturbed() {}

        static boolean applies(Perturbation kind, LoanRecord record) {
            return switch (kind) {
                case ACCRUAL -> !record.accruals().isEmpty();
                case BILLING -> !record.billings().isEmpty();
                case STATEMENT -> !record.statements().isEmpty();
                case ALLOCATION -> movable(record).isPresent();
                case AGREEMENT_BYTE, ENGINE_NUMBER -> true;
            };
        }

        /** What was perturbed - for the engine number, which engine. */
        static String what(Perturbation kind, Generated generated) {
            if (kind != Perturbation.ENGINE_NUMBER) {
                return kind.name();
            }
            return engineOf(generated).name();
        }

        static LendingEngine engineOf(Generated generated) {
            List<LendingEngine> engines = generated.kind() == Kind.LOAN
                    ? List.of(LendingEngine.SCHEDULE, LendingEngine.ACCRUAL, LendingEngine.ALLOCATION)
                    : List.of(LendingEngine.STATEMENT, LendingEngine.ACCRUAL, LendingEngine.ALLOCATION);
            return engines.get(pick(generated.kind(), generated.index(), 16, engines.size()));
        }

        static LoanRecord of(Perturbation kind, Generated generated) {
            LoanRecord record = generated.record();
            return switch (kind) {
                case ACCRUAL -> accrual(record);
                case BILLING -> billing(record);
                case STATEMENT -> statement(record);
                case ALLOCATION -> allocation(record);
                case AGREEMENT_BYTE -> forged(record);
                case ENGINE_NUMBER -> engine(record, engineOf(generated));
            };
        }

        /** The check a perturbation must be named by. */
        static Check named(Perturbation kind) {
            return switch (kind) {
                case ACCRUAL -> Check.ACCRUAL;
                case BILLING -> Check.BILLING;
                case STATEMENT -> Check.STATEMENT;
                case ALLOCATION -> Check.ALLOCATION;
                case AGREEMENT_BYTE -> Check.TERMS;
                case ENGINE_NUMBER -> Check.ENGINE_ABSENT;
            };
        }

        /** The row a perturbation changes - the one its finding must name (an engine number is the agreement's). */
        static UUID subject(Perturbation kind, LoanRecord record) {
            return switch (kind) {
                case ACCRUAL -> record.accruals().get(accrualIndex(record)).id();
                case BILLING -> record.billings().get(record.billings().size() / 2).id();
                case STATEMENT -> record.statements().get(record.statements().size() / 2).id();
                case ALLOCATION -> record.repayments().get(movable(record).orElseThrow()[0]).id();
                case AGREEMENT_BYTE, ENGINE_NUMBER -> record.agreements().get(0).id();
            };
        }

        /** An accrual that is not its period's last (its successor reads it) - from the middle of the record. */
        private static int accrualIndex(LoanRecord record) {
            List<LoanRecord.Accrual> accruals = record.accruals();
            for (int i = accruals.size() / 2; i < accruals.size() - 1; i++) {
                if (accruals.get(i + 1).periodStart().equals(accruals.get(i).periodStart())) {
                    return i;
                }
            }
            return accruals.size() / 2;
        }

        /** An accrual that is not its period's last (its successor reads it), its posting one minor unit up. */
        private static LoanRecord accrual(LoanRecord record) {
            List<LoanRecord.Accrual> accruals = new ArrayList<>(record.accruals());
            int chosen = accrualIndex(record);
            LoanRecord.Accrual row = accruals.get(chosen);
            accruals.set(chosen, new LoanRecord.Accrual(row.id(), row.date(), row.periodStart(), row.periodEnd(),
                    row.base(), row.cumulativeExact(), plus(row.posted(), 1), row.agreementId(), row.agreementVersion(),
                    row.engine(), row.entry(), row.createdAt(), row.order()));
            return copy(record, record.agreements(), accruals, record.billings(), record.statements(),
                    record.repayments());
        }

        /** A billing's principal due (an instalment's) or interest due (after maturity) one minor unit up. */
        private static LoanRecord billing(LoanRecord record) {
            List<LoanRecord.Billing> billings = new ArrayList<>(record.billings());
            int chosen = billings.size() / 2;
            LoanRecord.Billing row = billings.get(chosen);
            boolean principal = "INSTALMENT".equals(row.kind());
            billings.set(chosen, new LoanRecord.Billing(row.id(), row.kind(), row.scheduleId(), row.scheduleVersion(),
                    row.instalment(), row.sequence(), row.periodStart(), row.dueDate(),
                    principal ? row.interestDue() : plus(row.interestDue(), 1),
                    principal ? plus(row.principalDue(), 1) : row.principalDue(), row.agreementId(),
                    row.agreementVersion(), row.scheduleEngine(), row.accrualEngine(), row.entry(), row.createdAt(),
                    row.order()));
            return copy(record, record.agreements(), record.accruals(), billings, record.statements(),
                    record.repayments());
        }

        /** A statement's interest and minimum payment one minor unit up together - the shape its CHECK admits. */
        private static LoanRecord statement(LoanRecord record) {
            List<LoanRecord.StatementRow> statements = new ArrayList<>(record.statements());
            int chosen = statements.size() / 2;
            LoanRecord.StatementRow row = statements.get(chosen);
            statements.set(chosen, new LoanRecord.StatementRow(row.id(), row.cycleStart(), row.cycleEnd(), row.dueDate(),
                    plus(row.interestBilled(), 1), row.feesCarried(), row.principalBilled(), plus(row.minimumPayment(), 1),
                    row.agreementId(), row.agreementVersion(), row.statementEngine(), row.accrualEngine(), row.entry(),
                    row.createdAt(), row.order()));
            return copy(record, record.agreements(), record.accruals(), record.billings(), statements,
                    record.repayments());
        }

        /** The first payment with two due-item lines, one of at least two minor units: one unit moved between them. */
        private static Optional<int[]> movable(LoanRecord record) {
            for (int p = 0; p < record.repayments().size(); p++) {
                List<LoanRecord.PaymentLine> lines = record.repayments().get(p).lines();
                int from = -1;
                int to = -1;
                for (int l = 0; l < lines.size(); l++) {
                    if (lines.get(l).target() != AllocationLine.Target.DUE_ITEM) {
                        continue;
                    }
                    if (from < 0 && lines.get(l).amount().minorUnits() >= 2) {
                        from = l;
                    } else if (to < 0) {
                        to = l;
                    }
                }
                if (from >= 0 && to >= 0) {
                    return Optional.of(new int[] {p, from, to});
                }
            }
            return Optional.empty();
        }

        private static LoanRecord allocation(LoanRecord record) {
            int[] at = movable(record).orElseThrow();
            List<LoanRecord.Payment> payments = new ArrayList<>(record.repayments());
            LoanRecord.Payment payment = payments.get(at[0]);
            List<LoanRecord.PaymentLine> lines = new ArrayList<>(payment.lines());
            lines.set(at[1], moved(lines.get(at[1]), -1));
            lines.set(at[2], moved(lines.get(at[2]), 1));
            payments.set(at[0], new LoanRecord.Payment(payment.id(), payment.kind(), payment.amount(),
                    payment.agreementId(), payment.agreementVersion(), payment.allocationEngine(), payment.entry(),
                    payment.valueDate(), payment.createdAt(), lines, payment.order()));
            return copy(record, record.agreements(), record.accruals(), record.billings(), record.statements(),
                    payments);
        }

        private static LoanRecord.PaymentLine moved(LoanRecord.PaymentLine line, long by) {
            return new LoanRecord.PaymentLine(line.id(), line.ordinal(), line.target(), line.billingId(),
                    line.component(), plus(line.amount(), by));
        }

        /**
         * The canonical terms forged - a key inserted into the template object ({@code "x":"1",}, `LendingProofDatabaseTest`'s
         * shape) - and re-hashed, as a forger would, so only re-rendering sees it.
         */
        private static LoanRecord forged(LoanRecord record) {
            LoanRecord.Agreement agreement = record.agreements().get(0);
            String terms = agreement.canonicalTerms().replace("\"template\":{", "\"template\":{\"x\":\"1\",");
            if (terms.equals(agreement.canonicalTerms())) {
                throw new IllegalStateException("the canonical terms name their template");
            }
            return copy(record, List.of(agreement(agreement, terms, agreement.scheduleEngine(),
                    agreement.accrualEngine(), agreement.allocationEngine(), agreement.statementEngine())),
                    record.accruals(), record.billings(), record.statements(), record.repayments());
        }

        /** The agreement's pinned number of {@code engine} set to 2 - a version this build does not hold. */
        private static LoanRecord engine(LoanRecord record, LendingEngine engine) {
            LoanRecord.Agreement agreement = record.agreements().get(0);
            return copy(record, List.of(agreement(agreement, agreement.canonicalTerms(),
                    engine == LendingEngine.SCHEDULE ? Optional.of(2) : agreement.scheduleEngine(),
                    engine == LendingEngine.ACCRUAL ? 2 : agreement.accrualEngine(),
                    engine == LendingEngine.ALLOCATION ? 2 : agreement.allocationEngine(),
                    engine == LendingEngine.STATEMENT ? Optional.of(2) : agreement.statementEngine())),
                    record.accruals(), record.billings(), record.statements(), record.repayments());
        }

        private static LoanRecord.Agreement agreement(LoanRecord.Agreement row, String terms, Optional<Integer> schedule,
                int accrual, int allocation, Optional<Integer> statement) {
            byte[] sha = terms.equals(row.canonicalTerms()) ? row.termsSha256() : CanonicalOfferTerms.sha256(terms);
            return new LoanRecord.Agreement(row.id(), row.version(), row.offer(), row.amendment(), row.prepayment(),
                    row.decision(), row.termsVersionId(), row.product(), row.scale(), row.amountMinor(), row.termMonths(),
                    row.nominalAnnualRate(), row.repaymentDay(), schedule, accrual, allocation, statement,
                    row.templateId(), row.templateVersion(), row.canonicalFormat(), terms, sha, row.effectiveFrom(),
                    row.createdAt(), row.order());
        }

        private static Money plus(Money money, long minor) {
            return Money.ofPersisted(money.minorUnits() + minor, money.currency(), money.scale());
        }

        private static LoanRecord copy(LoanRecord r, List<LoanRecord.Agreement> agreements,
                List<LoanRecord.Accrual> accruals, List<LoanRecord.Billing> billings,
                List<LoanRecord.StatementRow> statements, List<LoanRecord.Payment> repayments) {
            return new LoanRecord(r.loan(), agreements, r.termsVersions(), r.offer(), r.disbursement(), r.accrualStart(),
                    r.payout(), r.schedules(), r.draws(), accruals, billings, statements, r.applications(), repayments,
                    r.reversals(), r.fees(), r.waivers(), r.amendments(), r.reschedulings(), r.closure(),
                    r.payoffQuotes(), r.payoffExecutions(), r.lineClosure(), r.conditions(), r.collectionAttempts(),
                    r.ledgerAccounts(), r.journalLines());
        }
    }

    // ------------------------------------------------------------------ the residual (section 19)

    /** The rounding residual, per account, from its rows alone: every failing identity named, never an amount. */
    static final class Residuals {

        private Residuals() {}

        static List<String> of(LoanRecord record) {
            List<String> failures = new ArrayList<>();
            BigDecimal rate = new BigDecimal(record.agreements().get(0).nominalAnnualRate());
            schedules(record, failures);
            Map<LocalDate, long[]> perPeriod = periods(record, rate, failures);
            for (LoanRecord.Billing billing : record.billings()) {
                long[] period = perPeriod.getOrDefault(billing.periodStart(), new long[2]);
                if (billing.interestDue().minorUnits() != period[0]) {
                    failures.add("BILLING_INTEREST " + billing.id());
                }
            }
            Map<JournalEntryId, Long> written = written(record);
            for (LoanRecord.StatementRow statement : record.statements()) {
                long[] cycle = perPeriod.getOrDefault(statement.cycleStart(), new long[2]);
                if (statement.interestBilled().minorUnits() != cycle[0]) {
                    failures.add("STATEMENT_INTEREST " + statement.id());
                }
                long notDue = 0;
                for (PurposeBalances.DatedLine line : record.journalLines()) {
                    if (line.account().equals(record.account(AccountPurpose.LOAN_PRINCIPAL).orElseThrow().id())
                            && written.getOrDefault(line.entry(), Long.MIN_VALUE) < statement.order()) {
                        notDue += line.direction() == Direction.DEBIT ? line.amount().minorUnits()
                                : -line.amount().minorUnits();
                    }
                }
                if (statement.principalBilled().minorUnits() > notDue) {
                    failures.add("STATEMENT_PRINCIPAL_ABOVE_NOT_DUE " + statement.id());
                }
                if (statement.minimumPayment().minorUnits() != statement.interestBilled().minorUnits()
                        + statement.feesCarried().minorUnits() + statement.principalBilled().minorUnits()) {
                    failures.add("STATEMENT_MINIMUM " + statement.id());
                }
            }
            long received = 0;
            for (LoanRecord.Payment payment : record.repayments()) {
                long lines = payment.lines().stream().mapToLong(line -> line.amount().minorUnits()).sum();
                if (lines != payment.amount().minorUnits()) {
                    failures.add("PAYMENT_LINES " + payment.id());
                }
                received += payment.amount().minorUnits();
            }
            for (LoanRecord.Application application : record.applications()) {
                long lines = application.lines().stream().mapToLong(line -> line.amount().minorUnits()).sum();
                if (lines != application.amount().minorUnits()) {
                    failures.add("APPLICATION_LINES " + application.id());
                }
            }
            if (record.closure().isPresent()) {
                long interest = record.billings().stream().mapToLong(billing -> billing.interestDue().minorUnits()).sum();
                long fees = record.fees().stream().mapToLong(fee -> fee.amount().minorUnits()).sum();
                long refund = record.closure().get().refund().minorUnits();
                long billedPrincipal = record.billings().stream().mapToLong(billing -> billing.principalDue().minorUnits())
                        .sum();
                if (billedPrincipal != record.agreements().get(0).amountMinor()) {
                    failures.add("CLOSED_PRINCIPAL_BILLED");
                }
                if (received - refund != record.agreements().get(0).amountMinor() + interest + fees) {
                    failures.add("CLOSED_MONEY_IN");
                }
            }
            return failures;
        }

        /** Each schedule: principal conserved, amounts = principal + interest, the final instalment at most the level. */
        private static void schedules(LoanRecord record, List<String> failures) {
            for (LoanRecord.ScheduleVersion schedule : record.schedules()) {
                long principal = 0;
                long interest = 0;
                long amount = 0;
                LocalDate previous = schedule.startDate();
                for (LoanRecord.Instalment instalment : schedule.instalments()) {
                    principal += instalment.principal().minorUnits();
                    interest += instalment.interest().minorUnits();
                    amount += instalment.amount().minorUnits();
                    if (!instalment.periodStart().equals(previous)
                            || instalment.closing().minorUnits() != instalment.opening().minorUnits()
                                    - instalment.principal().minorUnits()) {
                        failures.add("SCHEDULE_CHAIN " + instalment.id());
                    }
                    previous = instalment.dueDate();
                }
                LoanRecord.Instalment last = schedule.instalments().get(schedule.instalments().size() - 1);
                if (principal != schedule.principal().minorUnits() || amount != principal + interest
                        || interest != schedule.totalInterest().minorUnits() || last.closing().minorUnits() != 0
                        || last.amount().minorUnits() != schedule.finalInstalment().minorUnits()
                        || schedule.finalInstalment().compareTo(schedule.instalment()) > 0) {
                    failures.add("SCHEDULE_CONSERVATION " + schedule.id());
                }
            }
        }

        /**
         * Per accrual period: its postings sum to its exact interest rounded once ({@code HALF_EVEN}), and that exact
         * interest is the sum of each date's {@code base·r/365} - re-derived here from the rows' bases.
         */
        private static Map<LocalDate, long[]> periods(LoanRecord record, BigDecimal rate, List<String> failures) {
            Map<LocalDate, long[]> perPeriod = new HashMap<>();
            Map<LocalDate, BigDecimal> exact = new HashMap<>();
            Map<LocalDate, LoanRecord.Accrual> lastOf = new HashMap<>();
            for (LoanRecord.Accrual accrual : record.accruals()) {
                long[] sum = perPeriod.computeIfAbsent(accrual.periodStart(), key -> new long[2]);
                sum[0] += accrual.posted().minorUnits();
                sum[1]++;
                exact.merge(accrual.periodStart(), AmortisationOracles.daily(accrual.base().minorUnits(), rate),
                        BigDecimal::add);
                lastOf.put(accrual.periodStart(), accrual);
            }
            for (Map.Entry<LocalDate, LoanRecord.Accrual> period : new TreeMap<>(lastOf).entrySet()) {
                BigDecimal cumulative = period.getValue().cumulativeExact();
                long once = cumulative.setScale(AmortisationOracles.SCALE, RoundingMode.HALF_EVEN).unscaledValue()
                        .longValueExact();
                if (cumulative.compareTo(exact.get(period.getKey())) != 0
                        || perPeriod.get(period.getKey())[0] != once) {
                    failures.add("PERIOD_RESIDUAL " + period.getKey());
                }
            }
            return perPeriod;
        }

        /** Each journal entry's place in the history - the order of the row that names it. */
        private static Map<JournalEntryId, Long> written(LoanRecord record) {
            Map<JournalEntryId, Long> written = new HashMap<>();
            record.disbursement().flatMap(LoanRecord.Disbursement::entry)
                    .ifPresent(entry -> written.put(entry, LoanRecord.UNORDERED));
            record.draws().forEach(draw -> written.put(draw.entry(), draw.order()));
            record.accruals().forEach(row -> row.entry().ifPresent(entry -> written.put(entry, row.order())));
            record.billings().forEach(row -> row.entry().ifPresent(entry -> written.put(entry, row.order())));
            record.statements().forEach(row -> row.entry().ifPresent(entry -> written.put(entry, row.order())));
            record.applications().forEach(row -> written.put(row.entry(), row.order()));
            record.repayments().forEach(row -> written.put(row.entry(), row.order()));
            record.fees().forEach(row -> written.put(row.entry(), row.order()));
            record.closure().ifPresent(row -> row.refundEntry().ifPresent(entry -> written.put(entry, row.order())));
            return written;
        }
    }

    /** An on-time loan's instalment billings against its schedule's own rows - interest and principal, exactly. */
    private static int onTimeExact(LoanRecord record, List<String> failures) {
        Map<UUID, LoanRecord.Instalment> instalments = new HashMap<>();
        record.schedules().get(0).instalments().forEach(instalment -> instalments.put(instalment.id(), instalment));
        int checked = 0;
        for (LoanRecord.Billing billing : record.billings()) {
            if (billing.instalment().isEmpty()) {
                failures.add("POST_MATURITY " + billing.id());
                continue;
            }
            LoanRecord.Instalment instalment = instalments.get(billing.instalment().get());
            if (!billing.interestDue().equals(instalment.interest())
                    || !billing.principalDue().equals(instalment.principal())) {
                failures.add("INSTALMENT " + instalment.sequence());
            }
            checked++;
        }
        return checked;
    }

    // ------------------------------------------------------------------ plumbing

    private static void assertFlipped(Perturbation perturbation, Check named) {
        List<Outcome> perturbed = perturbedBy(perturbation);
        assertThat(perturbed).as("accounts perturbed by " + perturbation).isNotEmpty();
        assertThat(Perturbed.named(perturbation)).as("the check " + perturbation + " must name").isEqualTo(named);
        List<String> unflipped = perturbed.stream().filter(outcome -> outcome.perturbed().verdict() != Verdict.DIVERGED
                || !outcome.perturbed().checks().contains(named) || !outcome.perturbed().namesItsRow())
                .map(outcome -> outcome.kind() + " " + outcome.index() + " " + outcome.perturbed()).toList();
        System.out.println("P11-TST-002 PERTURBED " + perturbation + ": " + perturbed.size() + " accounts, "
                + (perturbed.size() - unflipped.size()) + " DIVERGED naming " + named + " on the changed row");
        assertThat(unflipped).as("perturbed accounts not DIVERGED by " + named + " on the changed row").isEmpty();
    }

    private static List<Outcome> perturbedBy(Perturbation perturbation) {
        return outcomes.stream().filter(outcome -> outcome.perturbation() == perturbation).toList();
    }

    private static List<Outcome> of(Kind kind) {
        return outcomes.stream().filter(outcome -> outcome.kind() == kind).toList();
    }

    private static Map<Tag, Long> tags(List<Outcome> some) {
        Map<Tag, Long> sums = new EnumMap<>(Tag.class);
        for (Tag tag : Tag.values()) {
            sums.put(tag, some.stream().mapToLong(outcome -> outcome.tags()[tag.ordinal()]).sum());
        }
        return sums;
    }

    /** Counts only - never an account's figures. */
    private static String census() {
        StringBuilder text = new StringBuilder("seed=" + AmortisationWorld.SEED + " loans=" + LOANS + " lines=" + LINES
                + " maxLoanRate=" + BigDecimal.valueOf(AmortisationWorld.MAXIMUM_LOAN_RATE_UNITS, 8).toPlainString());
        for (Kind kind : Kind.values()) {
            List<Outcome> some = of(kind);
            text.append(" | ").append(kind).append(": zeroRate=").append(some.stream().filter(Outcome::rateZero).count())
                    .append(" roundingSensitive=").append(some.stream().filter(Outcome::roundingSensitive).count());
            if (kind == Kind.LINE) {
                text.append(" capBinds=").append(some.stream().filter(Outcome::capBinds).count());
            }
            Map<String, Long> behaviours = new TreeMap<>();
            some.forEach(outcome -> behaviours.merge(outcome.behaviour(), 1L, Long::sum));
            text.append(" behaviours=").append(behaviours);
            Map<Tag, Long> sums = tags(some);
            sums.forEach((tag, sum) -> text.append(' ').append(tag.name()).append('=').append(sum));
        }
        Map<Perturbation, Long> perturbations = new EnumMap<>(Perturbation.class);
        outcomes.forEach(outcome -> perturbations.merge(outcome.perturbation(), 1L, Long::sum));
        text.append(" | perturbations=").append(perturbations);
        return text.toString();
    }

    private static String replaySummary() {
        long identical = outcomes.stream().filter(outcome -> outcome.verdict() == Verdict.IDENTICAL).count();
        Map<String, Long> byCheck = new TreeMap<>();
        Map<Kind, Long> divergedByKind = new EnumMap<>(Kind.class);
        for (Outcome outcome : outcomes) {
            if (outcome.verdict() == Verdict.DIVERGED) {
                divergedByKind.merge(outcome.kind(), 1L, Long::sum);
                outcome.checks().forEach(check -> byCheck.merge(check.name(), 1L, Long::sum));
            }
        }
        long replayed = outcomes.stream().mapToLong(Outcome::replayed).sum();
        long ledgerFailing = outcomes.stream().filter(outcome -> !outcome.ledgerFailures().isEmpty()).count();
        return "accounts=" + outcomes.size() + " identical=" + identical + " diverged=" + (outcomes.size() - identical)
                + " divergedByKind=" + divergedByKind + " divergedByCheck=" + byCheck + " factsReplayed=" + replayed
                + " subledgerFailing=" + ledgerFailing + " predictedUnderDoubleRounding="
                + outcomes.stream().filter(Outcome::roundingSensitive).count() + " predictedUncapped="
                + outcomes.stream().filter(Outcome::capBinds).count();
    }

    /**
     * {@code ScheduleEngineV1.project}'s cost (ADR-0093's Consequences: "measured in the battery"): the first 2,000 loans'
     * projections, single-threaded, once to warm and once measured - the mean in nanoseconds, overall and for the
     * 55-60-month terms whose exact products are longest.
     */
    private static long[] scheduleCost() {
        List<AmortisationWorld.ScheduleInput> inputs = outcomes.stream().map(Outcome::scheduleInput)
                .flatMap(Optional::stream).limit(2_000).toList();
        long total = 0;
        long longTotal = 0;
        long longCount = 0;
        for (int pass = 0; pass < 2; pass++) {
            total = 0;
            longTotal = 0;
            longCount = 0;
            for (AmortisationWorld.ScheduleInput input : inputs) {
                long begun = System.nanoTime();
                ScheduleOutcome outcome = ScheduleEngineV1.project(input.terms(), input.principal(), input.termMonths(),
                        input.day(), input.start());
                long spent = System.nanoTime() - begun;
                if (!(outcome instanceof ScheduleOutcome.Projected)) {
                    throw new IllegalStateException("a generated loan's schedule is projected");
                }
                total += spent;
                if (input.termMonths() >= 55) {
                    longTotal += spent;
                    longCount++;
                }
            }
        }
        return new long[] {inputs.isEmpty() ? 0 : total / inputs.size(), inputs.size(),
            longCount == 0 ? 0 : longTotal / longCount, longCount};
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }

    private static int size(String variable, int fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
    }
}
