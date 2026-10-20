package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplBatteryWorld.Arithmetic;
import com.finapp.bnpl.BnplBatteryWorld.Cohort;
import com.finapp.bnpl.BnplBatteryWorld.Generated;
import com.finapp.bnpl.BnplBatteryWorld.Tag;
import com.finapp.bnpl.BnplReplayProof.Check;
import com.finapp.bnpl.BnplReplayProof.Verdict;
import com.finapp.bnpl.testing.ReferenceMerchantFeeReturn;
import com.finapp.creditmath.testing.SecondJvm;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
 * The BNPL battery (`P12-TST-002`; {@code PHASE_12_PLAN.md} section 13.3; {@code INV-BNPL-04}, {@code -05}, {@code -06},
 * {@code -07}, {@code -09}, {@code INV-LND-02}/{@code -03}/{@code -04}/{@code -09} by analogy): a seeded portfolio of at
 * least 10,000 pay-in-4 and pay-in-N purchases on every plan ({@link BnplBatteryWorld}), every stored figure the battery's
 * own arithmetic ({@link BnplBatteryOracles}), replayed through the pinned engines by {@link BnplReplayProof} and proven by
 * {@link BnplSubledgerProof} and {@link BnplThreeWayProof}'s per-purchase arms - here and in a second JVM.
 *
 * <ul>
 *   <li>every purchase {@code IDENTICAL}, its subledger and three-way identities holding, its rounding residual zero;
 *   <li>every refund preserving value ({@code INV-BNPL-06}) and, over every order and split of its purchase's refunds by the
 *       production allocator, cancelling latest first and returning a fee that telescopes to exactly {@code f};
 *   <li>pay-in-4's split conserving {@code P} with the remainder on instalment 1 (A4), every schedule conserving principal
 *       ({@code INV-BNPL-05});
 *   <li>one perturbation per purchase - an allocation, a refund adjustment, an accrual, a billing, an agreement byte, an
 *       engine name - each flipping the verdict and naming its row ({@link Flip#namesItsRow});
 *   <li>a slice re-written by an engine SWAPPED under its stored name (pay-in-4's remainder last; pay-in-N's accrual rounded
 *       twice): {@code DIVERGED} for exactly the purchases the writer predicted;
 *   <li>the second JVM's lines equal to this one's, every line.
 * </ul>
 *
 * <p>Hermetic (ADR-0028 tiers by requirement): it needs only the JVM. The size is {@code FINAPP_BNPL_BATTERY_PURCHASES}
 * for a probe's quick run only - a shrunk run fails {@link #theCensus} by design, so it can never pass for the gate's.
 */
@DisplayName("P12-TST-002 - the BNPL battery: exact, value-preserving, replayed IDENTICAL in two JVMs")
class BnplBatteryTest {

    static final int PURCHASES = size("FINAPP_BNPL_BATTERY_PURCHASES", 10_000);

    /** Every tenth purchase (by the mix) is also re-written by a swapped arithmetic. */
    static final int SLICE = 10;

    /** The population's fingerprint at {@link BnplBatteryWorld#SEED} and full size - moved only by a reviewed change. */
    static final String FINGERPRINT = "bf21d7fd167ba53120715d6bbd58cedc819d9778b1c29f3cea86875564fa2b88";

    private static final BnplReplayProof REPLAY = new BnplReplayProof();
    private static final BnplSubledgerProof SUBLEDGER = new BnplSubledgerProof();
    private static final CurrencyCode EUR = BnplBatteryWorld.EUR;
    private static final Connection SNAPSHOT = BnplProofWorld.SNAPSHOT;

    private static List<Outcome> outcomes;
    private static List<String> ours;
    private static List<String> theirs;
    private static String fingerprint;

    /** The perturbations, one per purchase. */
    enum Perturbation {
        ALLOCATION,
        REFUND_ADJUSTMENT,
        ACCRUAL,
        BILLING,
        FEE,
        AGREEMENT_BYTE,
        ENGINE_NAME
    }

    /**
     * A perturbed or swapped record's replay. {@code namesItsRow}: the findings name the changed row itself under the
     * expected check - not merely the check's kind on some other row (the Phase 11 gate's finding; a swapped slice changes
     * many rows: always true).
     */
    record Flip(String what, Verdict verdict, Set<Check> checks, boolean ledgerHolds, boolean namesItsRow) {}

    /** What one purchase came to - its record discarded. */
    record Outcome(int index, BnplProduct product, BnplPlan plan, Cohort cohort,
            BnplBatteryInAnotherJvm.Summary summary, Verdict verdict, int replayed, Set<Check> checks,
            List<String> ledgerFailures, List<String> threeWayFailures, List<String> residualFailures,
            List<String> orderFailures, long ordersChecked, long fullOrders, List<String> splitFailures,
            Perturbation perturbation, Flip perturbed, Optional<Arithmetic> swapped, boolean predicted,
            Optional<Flip> swappedReplay, long[] tags, long gross, LocalDate purchaseDate, long generationNanos,
            long replayNanos) {}

    // ------------------------------------------------------------------ the run

    @BeforeAll
    static void battery() throws Exception {
        long begun = System.nanoTime();
        // The second JVM first, so it works while this one does.
        SecondJvm.Started child = SecondJvm.start(BnplBatteryInAnotherJvm.class, "1g", Integer.toString(PURCHASES));
        long parentNanos;
        try {
            outcomes = IntStream.range(0, PURCHASES).parallel().mapToObj(BnplBatteryTest::outcome).toList();
            parentNanos = System.nanoTime() - begun;
        } catch (RuntimeException | Error failed) {
            child.abandon(); // this JVM's pass failed: no orphaned child, no temporary files
            throw failed;
        }
        theirs = child.join(Duration.ofMinutes(30));
        long wallNanos = System.nanoTime() - begun;
        ours = BnplBatteryInAnotherJvm.lines(outcomes.stream().map(Outcome::summary).toList(), PURCHASES);
        MessageDigest all = BnplBatteryInAnotherJvm.sha256();
        outcomes.forEach(outcome -> all.update(outcome.summary().digest().getBytes(StandardCharsets.UTF_8)));
        fingerprint = HexFormat.of().formatHex(all.digest());
        long generation = outcomes.stream().mapToLong(Outcome::generationNanos).sum();
        long replay = outcomes.stream().mapToLong(Outcome::replayNanos).sum();
        System.out.println("P12-TST-002 TIMING: wall=" + millis(wallNanos) + "ms parentPass=" + millis(parentNanos)
                + "ms secondJvm=" + millis(child.elapsedNanos()) + "ms threadSummed{generation=" + millis(generation)
                + "ms, replayAndProofs=" + millis(replay) + "ms} processors=" + Runtime.getRuntime().availableProcessors());
        System.out.println("P12-TST-002 FINGERPRINT: seed=" + BnplBatteryWorld.SEED + " purchases=" + PURCHASES
                + " fingerprint=" + fingerprint);
        System.out.println("P12-TST-002 CENSUS: " + census());
        System.out.println("P12-TST-002 REPLAY: " + replaySummary());
    }

    /** One purchase through the whole pipeline: generated, digested, replayed, proven, perturbed, swapped. */
    private static Outcome outcome(int index) {
        long begun = System.nanoTime();
        Generated generated = BnplBatteryWorld.generate(index, Arithmetic.STANDARD);
        BnplRecord record = generated.record();
        long generatedAt = System.nanoTime();
        BnplReplayProof.Replay replay = REPLAY.replay(record);
        BnplSubledgerProof.Verdict ledger = SUBLEDGER.prove(record);
        BnplBatteryInAnotherJvm.Summary summary = BnplBatteryInAnotherJvm.summary(index, record, replay);
        List<String> threeWay = ThreeWay.of(record);
        List<String> residuals = Residuals.of(record, generated);
        List<String> orderFailures = new ArrayList<>();
        long[] orders = generated.refundCase().map(found -> Orders.check(found, orderFailures)).orElse(new long[2]);
        List<String> splitFailures = Splits.of(generated);

        Perturbation perturbation = perturbationOf(generated);
        BnplRecord perturbedRecord = Perturbed.of(perturbation, generated);
        BnplReplayProof.Replay perturbed = REPLAY.replay(perturbedRecord);
        boolean perturbedHolds = perturbation != Perturbation.AGREEMENT_BYTE || SUBLEDGER.prove(perturbedRecord).holds();
        boolean namesItsRow = perturbed.findings().contains(new BnplReplayProof.Finding(Perturbed.named(perturbation),
                Perturbed.subject(perturbation, generated)));
        Flip flip = new Flip(Perturbed.what(perturbation, generated), perturbed.verdict(), perturbed.checks(),
                perturbedHolds, namesItsRow);

        Optional<Arithmetic> swapped = swappedArithmetic(generated);
        boolean predicted = false;
        Optional<Flip> swappedReplay = Optional.empty();
        if (swapped.isPresent()) {
            Generated alternate = BnplBatteryWorld.generate(index, swapped.get());
            predicted = alternate.predicted();
            BnplReplayProof.Replay alternateReplay = REPLAY.replay(alternate.record());
            swappedReplay = Optional.of(new Flip(swapped.get().name(), alternateReplay.verdict(), alternateReplay.checks(),
                    true, true));
        }
        long finished = System.nanoTime();
        return new Outcome(index, generated.product(), generated.plan(), generated.cohort(), summary, replay.verdict(),
                replay.replayed(), replay.checks(), ledger.failures(), threeWay, residuals, orderFailures, orders[0],
                orders[1], splitFailures, perturbation, flip, swapped, predicted, swappedReplay, generated.tags(),
                generated.gross(), generated.purchaseDate(), generatedAt - begun, finished - generatedAt);
    }

    // ------------------------------------------------------------------ the cases

    @Test
    @DisplayName("the census: at least 10,000 purchases over both products and every plan, every coverage floor met, every"
            + " perturbation kind on at least 500 purchases, the swapped predictions non-empty, the fingerprint equal")
    void theCensus() {
        assertThat(PURCHASES).as("purchases").isGreaterThanOrEqualTo(10_000);
        Map<BnplPlan, Long> plans = new EnumMap<>(BnplPlan.class);
        outcomes.forEach(outcome -> plans.merge(outcome.plan(), 1L, Long::sum));
        assertThat(plans.keySet()).as("every plan").containsExactlyInAnyOrder(BnplPlan.values());
        assertThat(plans.get(BnplPlan.PAY_IN_4)).as("pay-in-4 purchases").isGreaterThanOrEqualTo(PURCHASES / 2);
        for (BnplPlan plan : List.of(BnplPlan.PAY_IN_3_MONTHS, BnplPlan.PAY_IN_6_MONTHS, BnplPlan.PAY_IN_12_MONTHS)) {
            assertThat(plans.get(plan)).as(plan + " purchases").isGreaterThanOrEqualTo(PURCHASES / 8);
        }
        for (BnplProduct product : BnplProduct.values()) {
            for (Cohort cohort : Cohort.values()) {
                assertThat(outcomes.stream().filter(outcome -> outcome.product() == product
                        && outcome.cohort() == cohort).count()).as(product + " " + cohort).isPositive();
            }
        }
        assertThat(outcomes.stream().map(Outcome::purchaseDate).collect(Collectors.toSet()))
                .as("a purchase on every day of 2027 and 2028").containsAll(IntStream.range(0, BnplBatteryWorld.START_DAYS)
                        .mapToObj(BnplBatteryWorld.FIRST_DAY::plusDays).toList());
        Map<Tag, Long> tags = tags(outcomes);
        Map<Tag, Long> floors = floors();
        floors.forEach((tag, floor) -> assertThat(tags.get(tag)).as("coverage: " + tag).isGreaterThanOrEqualTo(floor));
        for (Perturbation perturbation : Perturbation.values()) {
            assertThat(outcomes.stream().filter(outcome -> outcome.perturbation() == perturbation).count())
                    .as("purchases perturbed by " + perturbation).isGreaterThanOrEqualTo(500);
        }
        // The selections are the mix's, not the index's: the swapped purchases meet every plan and every cohort.
        List<Outcome> swapped = outcomes.stream().filter(outcome -> outcome.swapped().isPresent()).toList();
        assertThat(swapped.stream().map(Outcome::plan).collect(Collectors.toSet())).as("swapped: every plan")
                .containsExactlyInAnyOrder(BnplPlan.values());
        assertThat(swapped.stream().map(Outcome::cohort).collect(Collectors.toSet())).as("swapped: every cohort")
                .containsExactlyInAnyOrder(Cohort.values());
        for (Arithmetic arithmetic : List.of(Arithmetic.REMAINDER_LAST, Arithmetic.ROUNDED_TWICE)) {
            assertThat(swapped.stream().filter(outcome -> outcome.swapped().equals(Optional.of(arithmetic)))
                    .filter(Outcome::predicted).count()).as("purchases predicted to diverge under " + arithmetic)
                    .isPositive();
            assertThat(swapped.stream().filter(outcome -> outcome.swapped().equals(Optional.of(arithmetic)))
                    .filter(outcome -> !outcome.predicted()).count()).as("purchases predicted IDENTICAL under "
                            + arithmetic).isPositive();
        }
        assertThat(fingerprint).as("the population's fingerprint (seed " + BnplBatteryWorld.SEED + ")")
                .isEqualTo(FINGERPRINT);
    }

    /**
     * About half of each figure the full-size population is recorded with (BACKLOG.md's as-built), so a change to the
     * generator that thins a case out fails here and not only in the fingerprint a reviewer may re-pin - every tag floored.
     */
    static Map<Tag, Long> floors() {
        Map<Tag, Long> floors = new EnumMap<>(Tag.class);
        floors.put(Tag.PAY_IN_4, 2_500L);
        floors.put(Tag.PAY_IN_3, 800L);
        floors.put(Tag.PAY_IN_6, 800L);
        floors.put(Tag.PAY_IN_12, 800L);
        floors.put(Tag.NON_DIVISIBLE_PAY_IN_4, 1_800L);
        floors.put(Tag.AMOUNT_EDGE, 450L);
        floors.put(Tag.LEAP_DAY_PURCHASES, 35L);
        floors.put(Tag.MONTH_END_PURCHASES, 850L);
        floors.put(Tag.OFFERED_DAY_BEFORE, 100L);
        floors.put(Tag.RATE_ZERO, 150L);
        floors.put(Tag.RATE_MAXIMUM, 150L);
        floors.put(Tag.ZERO_MERCHANT_FEE, 800L);
        floors.put(Tag.ACCRUALS, 450_000L);
        floors.put(Tag.ZERO_POSTINGS, 55_000L);
        floors.put(Tag.LEAP_DAY_ACCRUALS, 600L);
        floors.put(Tag.BILLINGS, 24_000L);
        floors.put(Tag.EARLY_BILLINGS, 900L);
        floors.put(Tag.CUSTOMER_REPAYMENTS, 18_000L);
        floors.put(Tag.MULTI_ITEM_REPAYMENTS, 1_000L);
        floors.put(Tag.AUTO_COLLECTIONS, 3_000L);
        floors.put(Tag.COLLECTED, 2_300L);
        floors.put(Tag.PARTIALLY_COLLECTED, 650L);
        floors.put(Tag.INSUFFICIENT_FUNDS, 6_900L);
        floors.put(Tag.NOTHING_DUE, 650L);
        floors.put(Tag.APPLICATIONS_AT_BILLING, 4_000L);
        floors.put(Tag.CREDIT_HELD, 4_000L);
        floors.put(Tag.LATE_FEES, 2_300L);
        floors.put(Tag.LATE_FEES_CAPPED, 240L);
        floors.put(Tag.LATE_FEE_CAP_REACHED, 200L);
        floors.put(Tag.REFUNDS, 2_300L);
        floors.put(Tag.REFUNDS_PARTIAL, 1_600L);
        floors.put(Tag.REFUNDS_FULL, 700L);
        floors.put(Tag.REFUNDS_BEFORE, 500L);
        floors.put(Tag.REFUNDS_DURING, 950L);
        floors.put(Tag.REFUNDS_AFTER_BILLED, 75L);
        floors.put(Tag.REFUNDS_AFTER_CLOSURE, 750L);
        floors.put(Tag.REFUNDS_WITH_EXCESS, 1_300L);
        floors.put(Tag.REFUNDS_TO_CREDIT, 150L);
        floors.put(Tag.REFUND_FEE_RETURNED, 1_900L);
        floors.put(Tag.REFUND_SCHEDULE_VERSIONS, 650L);
        floors.put(Tag.REFUND_REAMORTISED, 800L);
        floors.put(Tag.REFUND_INTEREST_RELEASED, 350L);
        floors.put(Tag.REFUND_LATE_FEE_RELEASED, 45L);
        floors.put(Tag.FULLY_REFUNDED, 700L);
        floors.put(Tag.DISPUTES_REFUNDED, 330L);
        floors.put(Tag.DISPUTES_UPHELD, 330L);
        floors.put(Tag.HELD_INSTALMENTS, 1_800L);
        floors.put(Tag.FEES_AFTER_LIFT, 30L);
        floors.put(Tag.PAYOFF_QUOTES, 330L);
        floors.put(Tag.PAYOFF_PROJECTED, 150L);
        floors.put(Tag.QUOTES_STALE, 7L);
        floors.put(Tag.PAYOFFS, 320L);
        floors.put(Tag.CLOSED_REPAID, 3_600L);
        floors.put(Tag.CLOSED_PAID_OFF_EARLY, 320L);
        floors.put(Tag.CLOSED_REFUNDED, 400L);
        floors.put(Tag.CLOSED_REFUNDED_PARTIALLY_REPAID, 320L);
        floors.put(Tag.CLOSURE_REFUNDS, 490L);
        floors.put(Tag.STILL_ACTIVE, 340L);
        if (floors.size() != Tag.values().length) {
            throw new IllegalStateException("every coverage tag has its floor");
        }
        return floors;
    }

    @Test
    @DisplayName("every purchase replays IDENTICAL, re-deriving something, and its subledger proof holds")
    void everyPurchaseReplaysIdentical() {
        List<String> diverged = outcomes.stream().filter(outcome -> outcome.verdict() != Verdict.IDENTICAL)
                .map(outcome -> outcome.index() + " " + outcome.cohort() + " " + outcome.checks()).toList();
        assertThat(diverged).as("purchases DIVERGED - " + replaySummary()).isEmpty();
        assertThat(outcomes.stream().filter(outcome -> outcome.replayed() <= 0).map(Outcome::index).toList())
                .as("purchases re-deriving nothing").isEmpty();
        List<String> failing = outcomes.stream().filter(outcome -> !outcome.ledgerFailures().isEmpty())
                .map(outcome -> outcome.index() + " " + outcome.ledgerFailures()).toList();
        assertThat(failing).as("subledger proofs that do not hold").isEmpty();
    }

    @Test
    @DisplayName("every purchase's three-way identities hold over the oracle's figures: the merchant leg, G = repaid +"
            + " cancelled + outstanding, refunds = cancelled + excess, the fee income and the fee arm")
    void everyPurchaseHoldsItsThreeWayIdentities() {
        List<String> failing = outcomes.stream().filter(outcome -> !outcome.threeWayFailures().isEmpty())
                .map(outcome -> outcome.index() + " " + outcome.cohort() + " " + outcome.threeWayFailures()).toList();
        assertThat(failing).as("purchases whose three-way identities fail").isEmpty();
    }

    @Test
    @DisplayName("the rounding residual is zero per purchase: schedules conserve, each period's postings are its exact"
            + " interest rounded once, every billing bills what accrued, every payment and refund sums, a closed"
            + " obligation's money in is its principal, interest and fees")
    void theRoundingResidualIsZeroPerPurchase() {
        List<String> failing = outcomes.stream().filter(outcome -> !outcome.residualFailures().isEmpty())
                .map(outcome -> outcome.index() + " " + outcome.cohort() + " " + outcome.residualFailures()).toList();
        assertThat(failing).as("purchases with a residual").isEmpty();
    }

    @Test
    @DisplayName("every refund preserves value, and over every order and split of each purchase's refunds the production"
            + " allocator cancels latest first and the fee telescopes to exactly f at a full refund (INV-BNPL-06, -07)")
    void everyRefundOrderPreservesValueAndTelescopes() {
        long orders = outcomes.stream().mapToLong(Outcome::ordersChecked).sum();
        long full = outcomes.stream().mapToLong(Outcome::fullOrders).sum();
        long purchases = outcomes.stream().filter(outcome -> outcome.ordersChecked() > 0).count();
        System.out.println("P12-TST-002 ORDERS: " + purchases + " refunded purchases, " + orders
                + " refund orders and splits through REFUND_ALLOCATION_ENGINE_V1, " + full + " of them full refunds");
        assertThat(orders).as("refund orders checked").isGreaterThan(PURCHASES);
        assertThat(full).as("full-refund orders - where telescoping is judged").isGreaterThan(PURCHASES);
        List<String> failing = outcomes.stream().filter(outcome -> !outcome.orderFailures().isEmpty())
                .map(outcome -> outcome.index() + " " + outcome.orderFailures().stream().limit(4).toList()).toList();
        System.out.println("P12-TST-002 ORDERS FAILING: " + failing.size() + " purchases "
                + Orders.kinds(outcomes.stream().flatMap(outcome -> outcome.orderFailures().stream()).toList()));
        assertThat(failing).as("purchases a refund order breaks").isEmpty();
    }

    @Test
    @DisplayName("pay-in-4's split conserves P with the remainder on instalment 1 (A4), and every schedule the pinned"
            + " engines project conserves principal (INV-BNPL-05), equal to the oracle's")
    void theSplitsConserve() {
        long nonDivisible = outcomes.stream().filter(outcome -> outcome.product() == BnplProduct.BNPL_PAY_IN_4
                && outcome.gross() % 4 != 0).count();
        System.out.println("P12-TST-002 SPLITS: " + outcomes.size() + " schedules projected, " + nonDivisible
                + " pay-in-4 grosses not divisible by 4");
        assertThat(nonDivisible).as("pay-in-4 grosses not divisible by 4").isGreaterThan(PURCHASES / 4);
        List<String> failing = outcomes.stream().filter(outcome -> !outcome.splitFailures().isEmpty())
                .map(outcome -> outcome.index() + " " + outcome.splitFailures()).toList();
        assertThat(failing).as("schedules that do not conserve or put the remainder elsewhere").isEmpty();
    }

    @Test
    @DisplayName("a minor unit moved between two allocation lines of one repayment diverges, naming ALLOCATION on it")
    void aPerturbedAllocationDiverges() {
        assertFlipped(Perturbation.ALLOCATION, Check.ALLOCATION);
    }

    @Test
    @DisplayName("a refund adjustment one minor unit off, moved into the excess, diverges, naming REFUND on the refund")
    void aPerturbedRefundAdjustmentDiverges() {
        assertFlipped(Perturbation.REFUND_ADJUSTMENT, Check.REFUND);
    }

    @Test
    @DisplayName("a perturbed accrual diverges, naming ACCRUAL on it")
    void aPerturbedAccrualDiverges() {
        assertFlipped(Perturbation.ACCRUAL, Check.ACCRUAL);
    }

    @Test
    @DisplayName("a perturbed billing diverges, naming BILLING on it")
    void aPerturbedBillingDiverges() {
        assertFlipped(Perturbation.BILLING, Check.BILLING);
    }

    @Test
    @DisplayName("a late fee one minor unit off diverges, naming FEE on it - disputed purchases among them")
    void aPerturbedFeeDiverges() {
        assertFlipped(Perturbation.FEE, Check.FEE);
        long disputed = perturbedBy(Perturbation.FEE).stream().filter(outcome -> outcome.cohort() == Cohort.DISPUTE_UPHELD
                || outcome.cohort() == Cohort.DISPUTE_REFUNDED).count();
        System.out.println("P12-TST-002 PERTURBED FEE on disputed purchases: " + disputed);
        assertThat(disputed).as("fee perturbations on disputed purchases").isPositive();
    }

    @Test
    @DisplayName("a forged agreement byte, re-hashed as a forger would, diverges by TERMS alone - the subledger still holds")
    void aForgedAgreementByteDivergesOnlyByItsHash() {
        List<Outcome> forged = perturbedBy(Perturbation.AGREEMENT_BYTE);
        assertThat(forged).as("forged agreements").isNotEmpty();
        System.out.println("P12-TST-002 PERTURBED AGREEMENT_BYTE: " + forged.size() + " purchases, "
                + forged.stream().filter(outcome -> outcome.perturbed().checks().equals(Set.of(Check.TERMS))).count()
                + " DIVERGED by TERMS alone, " + forged.stream().filter(outcome -> outcome.perturbed().verdict()
                        == Verdict.IDENTICAL).count() + " IDENTICAL, subledger holding on "
                + forged.stream().filter(outcome -> outcome.perturbed().ledgerHolds()).count());
        List<String> wrong = forged.stream().filter(outcome -> outcome.perturbed().verdict() != Verdict.DIVERGED
                || !outcome.perturbed().checks().equals(Set.of(Check.TERMS)) || !outcome.perturbed().namesItsRow()
                || !outcome.perturbed().ledgerHolds())
                .map(outcome -> outcome.index() + " " + outcome.perturbed()).toList();
        assertThat(wrong).as("forged agreements not DIVERGED by TERMS alone with the money untouched").isEmpty();
    }

    @Test
    @DisplayName("an engine name this build does not hold, pinned on the agreement, diverges - ENGINE_ABSENT - for each"
            + " engine an agreement pins")
    void anEngineNameNotHeldDiverges() {
        assertFlipped(Perturbation.ENGINE_NAME, Check.ENGINE_ABSENT);
        Map<String, Long> perEngine = new TreeMap<>();
        perturbedBy(Perturbation.ENGINE_NAME).forEach(outcome -> perEngine.merge(outcome.perturbed().what(), 1L,
                Long::sum));
        System.out.println("P12-TST-002 ENGINE_NAME: " + perEngine);
        assertThat(perEngine.keySet()).as("every pinned engine's name swapped").containsExactlyInAnyOrder(
                "SCHEDULE", "ACCRUAL", "ALLOCATION", "REFUND_ALLOCATION");
    }

    @Test
    @DisplayName("an engine swapped under its stored name - pay-in-4's remainder last, pay-in-N's accrual rounded twice -"
            + " diverges for exactly the purchases the writer predicted, IDENTICAL for the rest")
    void anEngineSwappedUnderItsNameDivergesExactlyThePredictedPurchases() {
        Map<String, long[]> counts = new TreeMap<>();
        List<String> wrong = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            if (outcome.swapped().isEmpty()) {
                continue;
            }
            Arithmetic arithmetic = outcome.swapped().get();
            Flip replay = outcome.swappedReplay().orElseThrow();
            boolean diverged = replay.verdict() == Verdict.DIVERGED;
            long[] count = counts.computeIfAbsent(arithmetic.name(), key -> new long[3]);
            count[0]++;
            count[1] += outcome.predicted() ? 1 : 0;
            count[2] += diverged ? 1 : 0;
            Check named = arithmetic == Arithmetic.ROUNDED_TWICE ? Check.ACCRUAL : Check.SCHEDULE;
            if (diverged != outcome.predicted() || (diverged && !replay.checks().contains(named))) {
                wrong.add(outcome.index() + " " + arithmetic + " predicted=" + outcome.predicted() + " "
                        + replay.verdict() + " " + replay.checks());
            }
        }
        StringBuilder text = new StringBuilder();
        counts.forEach((key, count) -> text.append(key).append("{rewritten=").append(count[0]).append(", predicted=")
                .append(count[1]).append(", diverged=").append(count[2]).append("} "));
        System.out.println("P12-TST-002 SWAPPED: " + text.toString().strip());
        assertThat(wrong).as("swapped purchases whose verdict is not the prediction").isEmpty();
        assertThat(counts).as("both arithmetics").containsKeys(Arithmetic.REMAINDER_LAST.name(),
                Arithmetic.ROUNDED_TWICE.name());
    }

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone, other identity hash codes - generates and"
            + " replays every purchase to the same line, byte for byte")
    void aSecondJvmReplaysIdentically() {
        System.out.println("P12-TST-002 TWO-JVM: ours=" + ours.size() + " theirs=" + theirs.size() + " totals(ours)="
                + ours.get(ours.size() - 1) + " totals(theirs)=" + theirs.get(theirs.size() - 1));
        assertThat(ours).as("one line per purchase and the totals").hasSize(PURCHASES + 1);
        assertThat(theirs).as("the second JVM's lines, compared line for line").isEqualTo(ours);
    }

    // ------------------------------------------------------------------ the selections (the mix, never the walk)

    /**
     * A stable 64-bit mix of a purchase's index (splitmix64's finaliser). Every selection below - the perturbation's
     * rotation, the engine named, the swapped slice - is taken from it and never from the index itself: the generator walks
     * the index (the product on its parity, the cohort on {@code index / 2 mod 15}, month ends on {@code index mod 7}), so
     * an index-modulo selection would cover a lattice of the population (the Phase 11 gate's finding) where the mix covers
     * all of it.
     */
    static long mix(int index) {
        long z = 0x424E504C42415454L * 0x9E3779B97F4A7C15L + index;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** {@code mix}'s bits from {@code shift}, reduced modulo {@code modulus} - never negative. */
    static int pick(int index, int shift, int modulus) {
        return (int) Math.floorMod(mix(index) >>> shift, (long) modulus);
    }

    /** One per purchase, rotated by the mix (pay-in-N's accrual twice in its rotation); half the refunded, a refund adjustment. */
    static Perturbation perturbationOf(Generated generated) {
        int index = generated.index();
        if (pick(index, 8, 2) == 0 && Perturbed.applies(Perturbation.REFUND_ADJUSTMENT, generated)) {
            return Perturbation.REFUND_ADJUSTMENT;
        }
        // A charged purchase's late fee on half of them - the fee check this task's fix judges by instant must still bite.
        if (pick(index, 12, 2) == 0 && Perturbed.applies(Perturbation.FEE, generated)) {
            return Perturbation.FEE;
        }
        List<Perturbation> kinds = generated.product() == BnplProduct.BNPL_PAY_IN_N
                ? List.of(Perturbation.ACCRUAL, Perturbation.ALLOCATION, Perturbation.ACCRUAL, Perturbation.BILLING,
                        Perturbation.AGREEMENT_BYTE, Perturbation.ENGINE_NAME)
                : List.of(Perturbation.ALLOCATION, Perturbation.BILLING, Perturbation.AGREEMENT_BYTE,
                        Perturbation.ENGINE_NAME);
        int first = pick(index, 0, kinds.size());
        for (int i = 0; i < kinds.size(); i++) {
            Perturbation kind = kinds.get((first + i) % kinds.size());
            if (Perturbed.applies(kind, generated)) {
                return kind;
            }
        }
        return Perturbation.AGREEMENT_BYTE;
    }

    /** One purchase in ten (by the mix) re-written by its product's swapped arithmetic. */
    static Optional<Arithmetic> swappedArithmetic(Generated generated) {
        if (pick(generated.index(), 24, SLICE) != 0) {
            return Optional.empty();
        }
        return Optional.of(generated.product() == BnplProduct.BNPL_PAY_IN_4 ? Arithmetic.REMAINDER_LAST
                : Arithmetic.ROUNDED_TWICE);
    }

    // ------------------------------------------------------------------ the perturbations

    /** A copy of a record with one fact changed - the original untouched (it already replayed IDENTICAL). */
    static final class Perturbed {

        private Perturbed() {}

        static boolean applies(Perturbation kind, Generated generated) {
            BnplRecord record = generated.record();
            return switch (kind) {
                case ALLOCATION -> movable(record).isPresent();
                case REFUND_ADJUSTMENT -> adjustable(record).isPresent();
                case ACCRUAL -> accrualIndex(record).isPresent();
                case BILLING -> billingIndex(record).isPresent();
                case FEE -> !record.fees().isEmpty();
                case AGREEMENT_BYTE, ENGINE_NAME -> true;
            };
        }

        static String what(Perturbation kind, Generated generated) {
            return kind == Perturbation.ENGINE_NAME ? engineOf(generated) : kind.name();
        }

        /** The engine whose name an ENGINE_NAME perturbation swaps - chosen by the mix among those the agreement pins. */
        static String engineOf(Generated generated) {
            List<String> engines = generated.product() == BnplProduct.BNPL_PAY_IN_N
                    ? List.of("SCHEDULE", "ACCRUAL", "ALLOCATION", "REFUND_ALLOCATION")
                    : List.of("SCHEDULE", "ALLOCATION", "REFUND_ALLOCATION");
            return engines.get(pick(generated.index(), 16, engines.size()));
        }

        static BnplRecord of(Perturbation kind, Generated generated) {
            BnplRecord record = generated.record();
            return switch (kind) {
                case ALLOCATION -> allocation(record);
                case REFUND_ADJUSTMENT -> refundAdjustment(record);
                case ACCRUAL -> accrual(record);
                case BILLING -> billing(record);
                case FEE -> fee(record);
                case AGREEMENT_BYTE -> forged(record);
                case ENGINE_NAME -> engine(record, engineOf(generated));
            };
        }

        static Check named(Perturbation kind) {
            return switch (kind) {
                case ALLOCATION -> Check.ALLOCATION;
                case REFUND_ADJUSTMENT -> Check.REFUND;
                case ACCRUAL -> Check.ACCRUAL;
                case BILLING -> Check.BILLING;
                case FEE -> Check.FEE;
                case AGREEMENT_BYTE -> Check.TERMS;
                case ENGINE_NAME -> Check.ENGINE_ABSENT;
            };
        }

        /** The row a perturbation changes - the one its finding must name (an engine name is the agreement's). */
        static UUID subject(Perturbation kind, Generated generated) {
            BnplRecord record = generated.record();
            return switch (kind) {
                case ALLOCATION -> record.repayments().get(movable(record).orElseThrow()[0]).row().id();
                case REFUND_ADJUSTMENT -> record.refunds().get(adjustable(record).orElseThrow()[0]).row().id();
                case ACCRUAL -> record.accruals().get(accrualIndex(record).orElseThrow()).row().id();
                case BILLING -> record.billings().get(billingIndex(record).orElseThrow()).row().id();
                case FEE -> record.fees().get(record.fees().size() / 2).row().id();
                case AGREEMENT_BYTE, ENGINE_NAME -> record.agreements().get(0).row().id().value();
            };
        }

        /** The first repayment with two due-item lines, one of at least two minor units: one unit moved between them. */
        static Optional<int[]> movable(BnplRecord record) {
            for (int p = 0; p < record.repayments().size(); p++) {
                List<BnplPurchaseStore.Allocation> lines = record.repayments().get(p).row().allocations();
                int from = -1;
                int to = -1;
                for (int l = 0; l < lines.size(); l++) {
                    if (!"DUE_ITEM".equals(lines.get(l).target())) {
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

        static BnplRecord allocation(BnplRecord record) {
            int[] at = movable(record).orElseThrow();
            BnplRecord.Stamped<BnplPurchaseStore.Repayment> stamped = record.repayments().get(at[0]);
            List<BnplPurchaseStore.Allocation> lines = new ArrayList<>(stamped.row().allocations());
            lines.set(at[1], RecordEdits.with(lines.get(at[1]), "amount", plus(lines.get(at[1]).amount(), -1)));
            lines.set(at[2], RecordEdits.with(lines.get(at[2]), "amount", plus(lines.get(at[2]).amount(), 1)));
            BnplPurchaseStore.Repayment repayment = RecordEdits.with(stamped.row(), "allocations", lines);
            return RecordEdits.with(record, "repayments", RecordEdits.edited(record.repayments(), at[0],
                    found -> new BnplRecord.Stamped<>(repayment, found.at())));
        }

        /** The first refund with an adjustment of at least two minor units cancelled: one moved into its excess. */
        static Optional<int[]> adjustable(BnplRecord record) {
            for (int r = 0; r < record.refunds().size(); r++) {
                List<BnplRefund.Adjustment> adjustments = record.refunds().get(r).row().adjustments();
                for (int a = 0; a < adjustments.size(); a++) {
                    if (adjustments.get(a).principalCancelled().minorUnits() >= 2) {
                        return Optional.of(new int[] {r, a});
                    }
                }
            }
            return Optional.empty();
        }

        static BnplRecord refundAdjustment(BnplRecord record) {
            int[] at = adjustable(record).orElseThrow();
            BnplRecord.Stamped<BnplRefund> stamped = record.refunds().get(at[0]);
            BnplRefund refund = stamped.row();
            BnplRefund.Adjustment adjustment = refund.adjustments().get(at[1]);
            List<BnplRefund.Adjustment> adjustments = RecordEdits.edited(refund.adjustments(), at[1],
                    found -> RecordEdits.with(found, "principalCancelled", plus(found.principalCancelled(), -1)));
            boolean unbilled = adjustment.kind() == RefundAllocation.Cancellation.Kind.UNBILLED;
            BnplRefund edited = new BnplRefund(refund.id(), refund.purchase(), refund.obligation(), refund.merchant(),
                    refund.door(), refund.requestedBy(), refund.refundKey(), refund.amount(), refund.refundedBefore(),
                    refund.feeReturned(), refund.payableDebit(),
                    unbilled ? plus(refund.cancelledUnbilled(), -1) : refund.cancelledUnbilled(),
                    unbilled ? refund.cancelledBilled() : plus(refund.cancelledBilled(), -1), plus(refund.excess(), 1),
                    BnplRefund.ExcessTo.WALLET, refund.interestAccruedReleased(), refund.interestDueReleased(),
                    refund.allocationEngine(), refund.agreement(), refund.agreementVersion(), refund.scheduleVersion(),
                    refund.reason(), refund.entry(), refund.valueDate(), refund.createdAt(), refund.correlation(),
                    adjustments);
            return RecordEdits.with(record, "refunds", RecordEdits.edited(record.refunds(), at[0],
                    found -> new BnplRecord.Stamped<>(edited, found.at())));
        }

        /** A posting accrual that is not its period's last (its successor reads it) - from the middle of the record. */
        static Optional<Integer> accrualIndex(BnplRecord record) {
            List<BnplRecord.Stamped<BnplInterestAccrual>> accruals = record.accruals();
            for (int i = accruals.size() / 2; i < accruals.size() - 1; i++) {
                if (accruals.get(i).row().accrual().posted().isPositive() && accruals.get(i + 1).row().accrual()
                        .period().equals(accruals.get(i).row().accrual().period())) {
                    return Optional.of(i);
                }
            }
            for (int i = 0; i < accruals.size(); i++) {
                if (accruals.get(i).row().accrual().posted().isPositive()) {
                    return Optional.of(i);
                }
            }
            return Optional.empty();
        }

        static BnplRecord accrual(BnplRecord record) {
            int chosen = accrualIndex(record).orElseThrow();
            return RecordEdits.with(record, "accruals", RecordEdits.edited(record.accruals(), chosen, stamped -> {
                BnplInterestAccrual row = stamped.row();
                return new BnplRecord.Stamped<>(RecordEdits.with(row, "accrual", RecordEdits.with(row.accrual(),
                        "posted", plus(row.accrual().posted(), 1))), stamped.at());
            }));
        }

        /** A billing that posted something - from the middle of the record. */
        static Optional<Integer> billingIndex(BnplRecord record) {
            List<BnplRecord.Billing> billings = record.billings();
            for (int i = billings.size() / 2; i < billings.size(); i++) {
                if (billings.get(i).row().entry().isPresent()) {
                    return Optional.of(i);
                }
            }
            return Optional.empty();
        }

        static BnplRecord billing(BnplRecord record) {
            int chosen = billingIndex(record).orElseThrow();
            return RecordEdits.with(record, "billings", RecordEdits.edited(record.billings(), chosen, stamped -> {
                BnplBilling row = stamped.row();
                BnplBilling edited = row.principal().isPositive()
                        ? RecordEdits.with(row, "principal", plus(row.principal(), 1))
                        : RecordEdits.with(row, "interest", plus(row.interest(), 1));
                return RecordEdits.with(stamped, "row", edited);
            }));
        }

        /** The middle late fee one minor unit up - the amount the pinned fee and cap no longer explain. */
        static BnplRecord fee(BnplRecord record) {
            int chosen = record.fees().size() / 2;
            return RecordEdits.with(record, "fees", RecordEdits.edited(record.fees(), chosen, stamped ->
                    new BnplRecord.Stamped<>(RecordEdits.with(stamped.row(), "amount", plus(stamped.row().amount(), 1)),
                            stamped.at())));
        }

        /**
         * Every agreement version's canonical terms forged - a key inserted at the front of the object - and re-hashed, as a
         * forger would, so only re-rendering sees it (every version alike: a later version still copies its predecessor).
         */
        static BnplRecord forged(BnplRecord record) {
            List<BnplRecord.Agreement> versions = new ArrayList<>();
            for (BnplRecord.Agreement version : record.agreements()) {
                String terms = version.row().canonicalTerms().replace("{\"format\"", "{\"x\":\"1\",\"format\"");
                if (terms.equals(version.row().canonicalTerms())) {
                    throw new IllegalStateException("the canonical terms open with their format");
                }
                versions.add(RecordEdits.with(version, "row", RecordEdits.with(version.row(), "canonicalTerms", terms,
                        "termsSha256", BnplOfferTerms.sha256(terms))));
            }
            return RecordEdits.with(record, "agreements", versions);
        }

        /** Every agreement version's pinned name of {@code engine} set to version 2 - a version this build does not hold. */
        static BnplRecord engine(BnplRecord record, String engine) {
            List<BnplRecord.Agreement> versions = new ArrayList<>();
            for (BnplRecord.Agreement version : record.agreements()) {
                BnplAgreement row = version.row();
                BnplAgreement edited = switch (engine) {
                    case "SCHEDULE" -> RecordEdits.with(row, "scheduleEngine", row.scheduleEngine().replace("_V1", "_V2"));
                    case "ACCRUAL" -> RecordEdits.with(row, "accrualEngine", row.accrualEngine()
                            .map(name -> name.replace("_V1", "_V2")));
                    case "ALLOCATION" -> RecordEdits.with(row, "allocationEngine",
                            row.allocationEngine().replace("_V1", "_V2"));
                    default -> RecordEdits.with(row, "refundAllocationEngine",
                            row.refundAllocationEngine().replace("_V1", "_V2"));
                };
                versions.add(RecordEdits.with(version, "row", edited));
            }
            return RecordEdits.with(record, "agreements", versions);
        }

        private static Money plus(Money money, long minor) {
            return Money.ofPersisted(money.minorUnits() + minor, money.currency(), money.scale());
        }
    }

    // ------------------------------------------------------------------ the three-way identities, per purchase

    /** {@link BnplThreeWayProof}'s merchant leg and economic identity over one purchase's own record. */
    static final class ThreeWay {

        private ThreeWay() {}

        static List<String> of(BnplRecord record) {
            MerchantFeeSplit split = record.financing().split();
            ReferenceMerchantFeeReturn reference = ReferenceMerchantFeeReturn.halfEven(split.gross(), split.fee());
            BnplThreeWayProof proof = new BnplThreeWayProof(new OwnLeg(record), new FeeArithmetic(record, reference));
            List<String> failures = new ArrayList<>();
            BnplThreeWayProof.Identity identity = proof.identity(SNAPSHOT, record);
            failures.addAll(identity.failures());
            BnplThreeWayProof.MerchantLeg leg = proof.merchantLeg(SNAPSHOT, record.purchase().merchant(), EUR,
                    List.of(record));
            if (!leg.holds()) {
                failures.add("MERCHANT_LEG");
            }
            return failures;
        }

        /** The payable's lines of this purchase's own financing and refund entries - its share of the merchant leg. */
        private record OwnLeg(BnplRecord record) implements BnplProofReads {

            @Override
            public Optional<PayableLeg> payableLeg(Connection snapshot, UUID merchant, CurrencyCode currency) {
                LedgerAccountId payable = record.payable().orElseThrow();
                List<KeyedLine> lines = new ArrayList<>();
                for (BnplRecord.EntryLines entry : record.sharedEntries()) {
                    String namespace = entry.entry().equals(record.financing().entry()) ? "bnpl.financing:"
                            : "bnpl.refund:";
                    for (JournalLine line : entry.lines()) {
                        if (line.account().equals(payable)) {
                            lines.add(new KeyedLine(entry.entry(), line.direction(), line.amount(), namespace));
                        }
                    }
                }
                return Optional.of(new PayableLeg(payable, lines, true));
            }

            @Override
            public boolean trialBalanced(Connection snapshot, CurrencyCode currency) {
                throw new UnsupportedOperationException("the battery proves its own entries balance (Residuals)");
            }

            @Override
            public boolean capitalHolds(Connection snapshot, CurrencyCode currency) {
                throw new UnsupportedOperationException("capital is the storm's (P12-TST-001)");
            }

            @Override
            public boolean exposureHolds(Connection snapshot) {
                throw new UnsupportedOperationException("exposure is the storm's (P12-TST-001)");
            }
        }

        /** Merchant's fee arithmetic, as bnpl's suites bind it: the reference `app` proves equal to FeeCalculation. */
        private record FeeArithmetic(BnplRecord record, ReferenceMerchantFeeReturn reference) implements BnplMerchants {

            @Override
            public Money returnedFee(Connection unitOfWork, BnplPurchaseId purchaseId, Money refundedBefore,
                    Money refunded) {
                if (!purchaseId.equals(record.purchase().id())) {
                    throw new IllegalStateException("one purchase's arithmetic");
                }
                return reference.returnedFee(refundedBefore, refunded);
            }

            @Override
            public Standing standing(Connection unitOfWork, UUID merchantRef, BnplProduct product) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean coversFee(Connection unitOfWork, UUID merchantRef, BnplProduct product, Money gross) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<LedgerAccountId> payableAccount(Connection unitOfWork, UUID merchantRef,
                    CurrencyCode currency) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MerchantFeeSplit pinFee(Connection unitOfWork, UUID merchantRef, BnplProduct product,
                    BnplPurchaseId purchaseId, Money gross) {
                throw new UnsupportedOperationException();
            }
        }
    }

    // ------------------------------------------------------------------ every refund order (INV-BNPL-06, -07)

    /**
     * The purchase's position at its first refund, refunded in every order of its refund amounts - each permutation, and
     * each completed to the gross by its remainder whole and split in two - through the PRODUCTION allocator
     * ({@link RefundAllocationEngineV1}) bound to merchant's reference fee arithmetic: every split preserves value, cancels
     * latest first exactly as the oracle's walk does, and the fee returned telescopes to exactly {@code f} at a full refund,
     * whatever the order and split.
     */
    static final class Orders {

        private Orders() {}

        /** {@code [orders checked, full refunds among them]}. */
        static long[] check(BnplBatteryWorld.RefundCase found, List<String> failures) {
            List<List<Long>> sequences = new ArrayList<>();
            permute(found.amounts(), 0, new ArrayList<>(), new boolean[found.amounts().size()], sequences);
            long refunded = found.amounts().stream().mapToLong(Long::longValue).sum();
            long remainder = found.gross() - refunded;
            List<List<Long>> completed = new ArrayList<>();
            for (List<Long> sequence : sequences) {
                completed.add(sequence);
                if (remainder > 0) {
                    List<Long> whole = new ArrayList<>(sequence);
                    whole.add(remainder);
                    completed.add(whole);
                    if (remainder > 1) {
                        List<Long> halves = new ArrayList<>(sequence);
                        halves.add(remainder / 2);
                        halves.add(remainder - remainder / 2);
                        completed.add(halves);
                    }
                }
            }
            long full = 0;
            ReferenceMerchantFeeReturn fees = ReferenceMerchantFeeReturn.halfEven(money(found.gross()),
                    money(found.fee()));
            for (List<Long> sequence : completed) {
                full += run(found, sequence, fees, failures) ? 1 : 0;
            }
            return new long[] {completed.size(), full};
        }

        /** One order: each refund through the engine over the position the earlier ones left; whether it was full. */
        private static boolean run(BnplBatteryWorld.RefundCase found, List<Long> sequence, ReferenceMerchantFeeReturn fees,
                List<String> failures) {
            int n = found.slots().size();
            long[] principal = new long[n];
            boolean[] billed = new boolean[n];
            long[] allocated = new long[n];
            long[] cancelled = new long[n];
            for (int i = 0; i < n; i++) {
                long[] slot = found.slots().get(i);
                principal[i] = slot[0];
                billed[i] = slot[1] == 1;
                allocated[i] = slot[2];
                cancelled[i] = slot[3];
            }
            long before = 0;
            long feeTotal = 0;
            for (long amount : sequence) {
                List<RefundPosition.Instalment> instalments = new ArrayList<>();
                List<BnplBatteryOracles.Slot> oracle = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    instalments.add(new RefundPosition.Instalment(i + 1, money(principal[i]), billed[i],
                            money(allocated[i]), money(cancelled[i])));
                    oracle.add(new BnplBatteryOracles.Slot(i + 1, billed[i], principal[i] - allocated[i] - cancelled[i]));
                }
                RefundPosition position = new RefundPosition(money(found.gross()), money(found.fee()), money(before),
                        instalments);
                RefundAllocation split;
                try {
                    RefundAllocationOutcome outcome = RefundAllocationEngineV1.allocate(position, money(amount), fees);
                    if (!(outcome instanceof RefundAllocationOutcome.Allocated allocatedOutcome)) {
                        failures.add("REFUSED " + ((RefundAllocationOutcome.Refused) outcome).reason());
                        return false;
                    }
                    split = allocatedOutcome.allocation();
                } catch (IllegalStateException binding) {
                    failures.add("TELESCOPING (the engine refused the binding: " + binding.getMessage() + ")");
                    return false;
                }
                // Value preserved, and the payable debited the refund net of the fee (INV-BNPL-06).
                if (split.cancelled().plus(split.excess()).minorUnits() != amount
                        || split.feeReturned().plus(split.payableDebit()).minorUnits() != amount) {
                    failures.add("VALUE");
                }
                // Latest first, exactly the oracle's walk.
                BnplBatteryOracles.Split expected = BnplBatteryOracles.split(oracle, amount);
                List<String> engine = new ArrayList<>();
                split.cancellations().forEach(cancellation -> engine.add(cancellation.sequence() + ":"
                        + cancellation.kind() + ":" + cancellation.amount().minorUnits() + ":" + cancellation.whole()));
                List<String> theirs = new ArrayList<>();
                expected.cancellations().forEach(cancellation -> theirs.add((cancellation.index() + 1) + ":"
                        + (cancellation.billed() ? RefundAllocation.Cancellation.Kind.BILLED_UNPAID
                                : RefundAllocation.Cancellation.Kind.UNBILLED) + ":" + cancellation.amount() + ":"
                        + cancellation.whole()));
                if (!engine.equals(theirs) || split.excess().minorUnits() != expected.excess()
                        || split.leavesNoPrincipalUnpaid() != expected.leavesNothingUnpaid()) {
                    failures.add("LATEST_FIRST");
                }
                // No earlier instalment cancelled while a later one still owes principal after it.
                for (RefundAllocation.Cancellation cancellation : split.cancellations()) {
                    cancelled[cancellation.sequence() - 1] += cancellation.amount().minorUnits();
                }
                boolean owing = false;
                for (int i = n - 1; i >= 0; i--) {
                    boolean touched = false;
                    for (RefundAllocation.Cancellation cancellation : split.cancellations()) {
                        touched |= cancellation.sequence() == i + 1;
                    }
                    if (touched && owing) {
                        failures.add("LATEST_FIRST_ORDER");
                    }
                    owing |= principal[i] - allocated[i] - cancelled[i] > 0;
                }
                if (split.feeReturned().minorUnits() != BnplBatteryOracles.feeReturned(found.fee(), before, amount,
                        found.gross())) {
                    failures.add("FEE_RETURNED");
                }
                feeTotal += split.feeReturned().minorUnits();
                before += amount;
            }
            if (before == found.gross() && feeTotal != found.fee()) {
                failures.add("TELESCOPING");
            }
            return before == found.gross();
        }

        private static void permute(List<Long> amounts, int depth, List<Long> current, boolean[] used,
                List<List<Long>> out) {
            if (depth == amounts.size()) {
                out.add(List.copyOf(current));
                return;
            }
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < amounts.size(); i++) {
                if (used[i] || !seen.add(amounts.get(i))) {
                    continue;
                }
                used[i] = true;
                current.add(amounts.get(i));
                permute(amounts, depth + 1, current, used, out);
                current.remove(current.size() - 1);
                used[i] = false;
            }
        }

        static Map<String, Long> kinds(List<String> failures) {
            Map<String, Long> kinds = new TreeMap<>();
            failures.forEach(failure -> kinds.merge(failure.split(" ")[0], 1L, Long::sum));
            return kinds;
        }
    }

    // ------------------------------------------------------------------ the splits (A4, INV-BNPL-05)

    /** Each purchase's schedule projected by its pinned engine: conserving, pay-in-4's remainder first, the oracle's. */
    static final class Splits {

        private Splits() {}

        static List<String> of(Generated generated) {
            List<String> failures = new ArrayList<>();
            BnplRecord record = generated.record();
            BnplTerms terms = record.termsVersions().values().iterator().next().orElseThrow().terms();
            BnplPurchaseStore.StoredSchedule stored = record.schedules().get(0).row();
            BnplIllustration projected = BnplReplayer.project(stored.engine(), terms, record.obligation().plan(),
                    money(generated.gross()), record.obligation().purchaseDate());
            if (projected.lines().size() != stored.instalments().size()) {
                failures.add("INSTALMENT_COUNT");
                return failures;
            }
            long sum = 0;
            for (BnplIllustration.Line line : projected.lines()) {
                sum += line.principal().minorUnits();
            }
            if (sum != generated.gross()) {
                failures.add("CONSERVATION");
            }
            if (generated.product() == BnplProduct.BNPL_PAY_IN_4) {
                long[] oracle = BnplBatteryOracles.payIn4(generated.gross());
                for (int k = 0; k < 4; k++) {
                    if (projected.lines().get(k).principal().minorUnits() != oracle[k]) {
                        failures.add("A4_INSTALMENT_" + (k + 1));
                    }
                }
            }
            for (int k = 0; k < projected.lines().size(); k++) {
                BnplPurchaseStore.StoredInstalment row = stored.instalments().get(k);
                if (!row.principal().equals(projected.lines().get(k).principal())
                        || !row.dueDate().equals(projected.lines().get(k).dueDate())) {
                    failures.add("ORACLE_" + (k + 1));
                }
            }
            return failures;
        }
    }

    // ------------------------------------------------------------------ the residual (section 19)

    /** The rounding residual and value preservation, per purchase, from its rows alone: every failure named, no amount. */
    static final class Residuals {

        private Residuals() {}

        static List<String> of(BnplRecord record, Generated generated) {
            List<String> failures = new ArrayList<>();
            BigDecimal rate = record.termsVersions().values().iterator().next().orElseThrow().terms().nominalAnnualRate();
            for (BnplRecord.Schedule version : record.schedules()) {
                long principal = 0;
                for (BnplPurchaseStore.StoredInstalment row : version.row().instalments()) {
                    principal += row.principal().minorUnits();
                    if (row.amount().minorUnits() != row.principal().minorUnits() + row.interest().minorUnits()) {
                        failures.add("INSTALMENT_AMOUNT " + row.id());
                    }
                }
                if (principal != version.row().principal().minorUnits()) {
                    failures.add("SCHEDULE_CONSERVATION " + version.row().id());
                }
            }
            if (generated.product() == BnplProduct.BNPL_PAY_IN_4) {
                List<BnplPurchaseStore.StoredInstalment> one = record.schedules().get(0).row().instalments();
                long level = one.get(1).principal().minorUnits();
                if (one.get(2).principal().minorUnits() != level || one.get(3).principal().minorUnits() != level
                        || one.get(0).principal().minorUnits() - level < 0 || one.get(0).principal().minorUnits() - level > 3) {
                    failures.add("A4_REMAINDER_FIRST");
                }
            }
            // Per accrual period: its postings are its exact interest - Σ base·r/365, re-derived - rounded once.
            Map<String, long[]> posted = new HashMap<>();
            Map<String, BigDecimal> exact = new HashMap<>();
            Map<String, BnplInterestAccrual> lastOf = new TreeMap<>();
            for (BnplRecord.Stamped<BnplInterestAccrual> stamped : record.accruals()) {
                BnplInterestAccrual row = stamped.row();
                String period = row.accrual().period().start() + "/" + row.accrual().period().endExclusive();
                posted.computeIfAbsent(period, key -> new long[1])[0] += row.accrual().posted().minorUnits();
                exact.merge(period, BnplBatteryOracles.daily(row.accrual().base().minorUnits(), rate), BigDecimal::add);
                lastOf.put(period, row);
            }
            lastOf.forEach((period, last) -> {
                BigDecimal cumulative = last.accrual().cumulativeExact();
                if (cumulative.compareTo(exact.get(period)) != 0
                        || posted.get(period)[0] != BnplBatteryOracles.roundedOnce(cumulative)) {
                    failures.add("PERIOD_RESIDUAL " + period);
                }
            });
            // Every billing bills exactly its period's posted accruals (pay-in-N) - never the projection.
            for (BnplRecord.Billing billing : record.billings()) {
                long interest = 0;
                for (BnplRecord.Stamped<BnplInterestAccrual> accrual : record.accruals()) {
                    LocalDate date = accrual.row().accrual().date();
                    if (!date.isBefore(billing.row().periodStart()) && date.isBefore(billing.row().dueDate())
                            && accrual.at().isBefore(billing.at())) {
                        interest += accrual.row().accrual().posted().minorUnits();
                    }
                }
                if (billing.row().interest().minorUnits() != interest) {
                    failures.add("BILLING_INTEREST " + billing.row().id());
                }
            }
            long received = 0;
            for (BnplRecord.Stamped<BnplPurchaseStore.Repayment> repayment : record.repayments()) {
                long lines = repayment.row().allocations().stream().mapToLong(line -> line.amount().minorUnits()).sum();
                if (lines != repayment.row().amount().minorUnits()) {
                    failures.add("REPAYMENT_LINES " + repayment.row().id());
                }
                received += repayment.row().amount().minorUnits();
            }
            for (BnplRecord.Stamped<BnplCreditBalanceApplication> application : record.applications()) {
                long lines = application.row().lines().stream().mapToLong(line -> line.amount().minorUnits()).sum();
                if (lines != application.row().amount().minorUnits()) {
                    failures.add("APPLICATION_LINES " + application.row().id());
                }
            }
            // Value preservation per refund (INV-BNPL-06), its entry balanced, the fee cumulative (INV-BNPL-07).
            long gross = record.financing().split().gross().minorUnits();
            long fee = record.financing().split().fee().minorUnits();
            long refunded = 0;
            long feeReturned = 0;
            long cancelled = 0;
            long interestReleased = 0;
            long feesReleased = 0;
            long payableTaken = 0;
            for (BnplRecord.Stamped<BnplRefund> stamped : record.refunds()) {
                BnplRefund refund = stamped.row();
                long amount = refund.amount().minorUnits();
                if (amount != refund.cancelled().minorUnits() + refund.excess().minorUnits()
                        || amount != refund.feeReturned().minorUnits() + refund.payableDebit().minorUnits()) {
                    failures.add("VALUE_PRESERVATION " + refund.id());
                }
                if (refund.feeReturned().minorUnits() != BnplBatteryOracles.feeReturned(fee, refunded, amount, gross)) {
                    failures.add("FEE_CUMULATIVE " + refund.id());
                }
                if (!balanced(record.sharedLinesOf(refund.entry()))) {
                    failures.add("REFUND_ENTRY " + refund.id());
                }
                refunded += amount;
                feeReturned += refund.feeReturned().minorUnits();
                // B7's bound by arithmetic (INV-BNPL-07, INV-MER-07 as amended): what the purchase's refunds took back
                // from the payable never exceeds what its financing credited, G - f.
                payableTaken += refund.payableDebit().minorUnits();
                if (payableTaken > gross - fee) {
                    failures.add("PAYABLE_BOUND " + refund.id());
                }
                cancelled += refund.cancelled().minorUnits();
                interestReleased += refund.interestReleased().minorUnits();
                feesReleased += refund.lateFeeReleased().minorUnits();
            }
            if (refunded > gross || (refunded == gross && feeReturned != fee)) {
                failures.add("FEE_TELESCOPING");
            }
            // A full refund closes the obligation with nothing stranded (INV-BNPL-09) - in its own transaction, or at the
            // next servicing step when the wallet could not take the credit that day (OQ-13).
            if (refunded == gross && (record.purchase().status() != BnplPurchaseStatus.FULLY_REFUNDED
                    || record.obligation().status() != BnplObligationStatus.CLOSED)) {
                failures.add("FULL_REFUND_NOT_CLOSED");
            }
            if (!balanced(record.sharedLinesOf(record.financing().entry()))) {
                failures.add("FINANCING_ENTRY");
            }
            if (record.closure().isPresent()) {
                long billedPrincipal = 0;
                for (BnplRecord.Billing billing : record.billings()) {
                    billedPrincipal += billing.row().principal().minorUnits();
                }
                long unbilledCancelled = record.refunds().stream().mapToLong(refund -> refund.row().cancelledUnbilled()
                        .minorUnits()).sum();
                if (billedPrincipal + unbilledCancelled != gross) {
                    failures.add("CLOSED_PRINCIPAL_BILLED_OR_CANCELLED");
                }
                long accrued = record.accruals().stream().mapToLong(accrual -> accrual.row().accrual().posted()
                        .minorUnits()).sum();
                long assessed = record.fees().stream().mapToLong(found -> found.row().amount().minorUnits()).sum();
                long refundedToWallet = record.closure().get().row().refund().minorUnits();
                // The customer's money in, what a refund's excess held as credit added to it (OQ-13), less what the
                // closure refunded, is exactly what was owed and not cancelled or released.
                long excessHeld = record.refunds().stream().filter(refund -> refund.row().excessTo()
                        == BnplRefund.ExcessTo.CREDIT_BALANCE).mapToLong(refund -> refund.row().excess().minorUnits()).sum();
                if (received + excessHeld - refundedToWallet != (gross - cancelled) + (accrued - interestReleased)
                        + (assessed - feesReleased)) {
                    failures.add("CLOSED_MONEY_IN");
                }
            }
            return failures;
        }

        private static boolean balanced(List<JournalLine> lines) {
            long net = 0;
            for (JournalLine line : lines) {
                net += line.direction() == Direction.DEBIT ? line.amount().minorUnits() : -line.amount().minorUnits();
            }
            return !lines.isEmpty() && net == 0;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static void assertFlipped(Perturbation perturbation, Check named) {
        List<Outcome> perturbed = perturbedBy(perturbation);
        assertThat(perturbed).as("purchases perturbed by " + perturbation).isNotEmpty();
        assertThat(Perturbed.named(perturbation)).as("the check " + perturbation + " must name").isEqualTo(named);
        List<String> unflipped = perturbed.stream().filter(outcome -> outcome.perturbed().verdict() != Verdict.DIVERGED
                || !outcome.perturbed().checks().contains(named) || !outcome.perturbed().namesItsRow())
                .map(outcome -> outcome.index() + " " + outcome.perturbed()).toList();
        System.out.println("P12-TST-002 PERTURBED " + perturbation + ": " + perturbed.size() + " purchases, "
                + (perturbed.size() - unflipped.size()) + " DIVERGED naming " + named + " on the changed row");
        assertThat(unflipped).as("perturbed purchases not DIVERGED by " + named + " on the changed row").isEmpty();
    }

    private static List<Outcome> perturbedBy(Perturbation perturbation) {
        return outcomes.stream().filter(outcome -> outcome.perturbation() == perturbation).toList();
    }

    private static Map<Tag, Long> tags(List<Outcome> some) {
        Map<Tag, Long> sums = new EnumMap<>(Tag.class);
        for (Tag tag : Tag.values()) {
            sums.put(tag, some.stream().mapToLong(outcome -> outcome.tags()[tag.ordinal()]).sum());
        }
        return sums;
    }

    /** Counts only - never a purchase's figures. */
    private static String census() {
        StringBuilder text = new StringBuilder("seed=" + BnplBatteryWorld.SEED + " purchases=" + PURCHASES
                + " maxRate=" + BigDecimal.valueOf(BnplBatteryWorld.MAXIMUM_RATE_UNITS, 8).toPlainString());
        Map<String, Long> cohorts = new TreeMap<>();
        outcomes.forEach(outcome -> cohorts.merge(outcome.cohort().name(), 1L, Long::sum));
        text.append(" cohorts=").append(cohorts);
        tags(outcomes).forEach((tag, sum) -> text.append(' ').append(tag.name()).append('=').append(sum));
        Map<Perturbation, Long> perturbations = new EnumMap<>(Perturbation.class);
        outcomes.forEach(outcome -> perturbations.merge(outcome.perturbation(), 1L, Long::sum));
        text.append(" | perturbations=").append(perturbations);
        return text.toString();
    }

    private static String replaySummary() {
        long identical = outcomes.stream().filter(outcome -> outcome.verdict() == Verdict.IDENTICAL).count();
        Map<String, Long> byCheck = new TreeMap<>();
        Map<String, Long> byCohort = new TreeMap<>();
        for (Outcome outcome : outcomes) {
            if (outcome.verdict() == Verdict.DIVERGED) {
                outcome.checks().forEach(check -> byCheck.merge(check.name(), 1L, Long::sum));
                byCohort.merge(outcome.cohort().name(), 1L, Long::sum);
            }
        }
        long replayed = outcomes.stream().mapToLong(Outcome::replayed).sum();
        return "purchases=" + outcomes.size() + " identical=" + identical + " diverged=" + (outcomes.size() - identical)
                + " divergedByCheck=" + byCheck + " divergedByCohort=" + byCohort + " factsReplayed=" + replayed
                + " subledgerFailing=" + outcomes.stream().filter(outcome -> !outcome.ledgerFailures().isEmpty()).count()
                + " threeWayFailing=" + outcomes.stream().filter(outcome -> !outcome.threeWayFailures().isEmpty()).count()
                + " residualFailing=" + outcomes.stream().filter(outcome -> !outcome.residualFailures().isEmpty()).count();
    }

    private static Money money(long minor) {
        return BnplBatteryWorld.money(minor);
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }

    private static int size(String variable, int fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
    }
}
