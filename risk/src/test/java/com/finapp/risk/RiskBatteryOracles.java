package com.finapp.risk;

import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The policy battery's own derivation (`P13-TST-002`; {@code PHASE_13_PLAN.md} sections 12.1-12.3, owner decisions C1 and
 * C8; ADR-0108, ADR-0109 decision 3; {@code INV-RSK-04}, {@code INV-MON-01}) - <strong>what every v1 version says and what
 * every verdict under it must be</strong>, computed here and never by the engine. The Phase 11 battery's Q2: an oracle that
 * calls the code under test, or reads the object the engine reads, cannot see that code edited under it.
 *
 * <ul>
 *   <li><strong>Its own statement of each version</strong> ({@link Statement}): the rules as kind, typed parameter values,
 *       weight and hard decision; the score or rating bands; the fail-safe bands; the knobs - transcribed from the plan's
 *       text of C8 (section 12.2) and the fixtures' recorded as-built additions (`-013`, `-014`), never read from
 *       {@code RiskPolicyFixtures}. The battery translates a statement into the production policy model and requires it
 *       EQUAL to the fixture - the version is the data it says - in a check of its own.
 *   <li><strong>Its own statement of each rule kind</strong> over the sealed readings ({@link #outcome}), the precedence
 *       ({@link #PRECEDENCE}, strictest first), the clamp ({@link #CEILING}), the band lookup over its statement, the
 *       fail-safe by cause (ADR-0108 decision 4, the `-004` as-built), the rating and assurance orders and the admission
 *       table (section 12.1).
 * </ul>
 *
 * <p>Integer arithmetic only - every amount and threshold a {@code long} of minor units, every share compared by
 * cross-multiplication ({@code INV-MON-01}). {@code RiskBatteryOraclesTest} reads this class's bytecode and requires that it
 * names none of the engine's types or methods; the names of this class's own methods avoid theirs for that reason.
 */
final class RiskBatteryOracles {

    /** The precedence when rules disagree (ADR-0108; plan section 12.3) - strictest first, by the plan's text. */
    static final List<RiskDecision> PRECEDENCE = List.of(RiskDecision.DENY, RiskDecision.HOLD, RiskDecision.REVIEW,
            RiskDecision.CHALLENGE, RiskDecision.ALLOW);

    /** The rating's severity, least first (plan section 12.5) - "at or above" read by position here. */
    static final List<RiskRating> SEVERITY = List.of(RiskRating.LOW, RiskRating.MEDIUM, RiskRating.HIGH,
            RiskRating.PROHIBITED);

    /** The assurance's strength, weakest first. */
    static final List<RiskAssurance> STRENGTH = List.of(RiskAssurance.SINGLE_FACTOR, RiskAssurance.MULTI_FACTOR);

    /** The score's ceiling: the sum of the fired weights clamped to [0, 1000] (ADR-0109 decision 3). */
    static final int CEILING = 1000;

    /** Every version's currency (C1's bands are EUR). */
    static final String CURRENCY = "EUR";

    /** The corridors the C8 fixtures name high-risk - test content, shapes only. */
    static final SortedSet<String> HIGH_RISK = Collections.unmodifiableSortedSet(
            new TreeSet<>(List.of("EUR-AED-AE", "EUR-GBP-KY", "EUR-USD-PA")));

    private RiskBatteryOracles() {}

    // ------------------------------------------------------------------ the statement of a version

    /** One rule as the battery states it: kind, typed parameter values by name, weight and/or hard decision. */
    record Line(int ordinal, RuleKind kind, SortedMap<String, Object> parameters, OptionalInt weight,
            Optional<RiskDecision> hard) {
        Line {
            Objects.requireNonNull(kind, "kind");
            parameters = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(parameters, "parameters")));
            Objects.requireNonNull(weight, "weight");
            Objects.requireNonNull(hard, "hard");
        }

        long number(String name) {
            Object value = parameters.get(name);
            if (value instanceof Long held) {
                return held;
            }
            if (value instanceof Integer held) {
                return held;
            }
            throw new IllegalStateException(kind + " states no number '" + name + "'");
        }

        <T> T value(String name, Class<T> type) {
            return type.cast(Objects.requireNonNull(parameters.get(name), name));
        }

        Line with(String name, Object value) {
            if (!parameters.containsKey(name)) {
                throw new IllegalArgumentException(kind + " states no parameter '" + name + "'");
            }
            SortedMap<String, Object> changed = new TreeMap<>(parameters);
            changed.put(name, value);
            return new Line(ordinal, kind, changed, weight, hard);
        }

        Line weighing(int newWeight) {
            return new Line(ordinal, kind, parameters, OptionalInt.of(newWeight), hard);
        }
    }

    /** A closed score or rating band {@code [from, to]} of [0, 1000] and what it answers. */
    record Band<T>(int from, int to, T answer) {}

    /** A fail-safe band: the amounts {@code [fromMinor, toMinor)} - the last open-ended - and its decision (C1). */
    record Edge(long fromMinor, OptionalLong toMinor, RiskDecision decision) {}

    /**
     * A version as the battery states it. {@code name} is how every failure names it ("TRANSFER v1"); the knobs a judged
     * version names (the challenge assurance, the override threshold in minor units, the hold validity in hours) are part
     * of what the fixture must equal.
     */
    record Statement(String name, RiskOperation operation, List<Line> lines, List<Band<RiskDecision>> scoreBands,
            List<Band<RiskRating>> ratingBands, List<Edge> failSafe, Optional<RiskAssurance> challenge,
            OptionalLong overrideThresholdMinor, OptionalInt holdHours) {

        Statement {
            lines = List.copyOf(lines);
            scoreBands = List.copyOf(scoreBands);
            ratingBands = List.copyOf(ratingBands);
            failSafe = List.copyOf(failSafe);
        }

        boolean monitoring() {
            return operation == RiskOperation.MONITORING;
        }

        Line line(int ordinal) {
            return lines.get(ordinal - 1);
        }

        Statement named(String newName) {
            return new Statement(newName, operation, lines, scoreBands, ratingBands, failSafe, challenge,
                    overrideThresholdMinor, holdHours);
        }

        Statement withLine(Line line) {
            List<Line> changed = new ArrayList<>(lines);
            changed.set(line.ordinal() - 1, line);
            return new Statement(name, operation, changed, scoreBands, ratingBands, failSafe, challenge,
                    overrideThresholdMinor, holdHours);
        }

        Statement withScoreBands(List<Band<RiskDecision>> bands) {
            return new Statement(name, operation, lines, bands, ratingBands, failSafe, challenge, overrideThresholdMinor,
                    holdHours);
        }

        Statement withRatingBands(List<Band<RiskRating>> bands) {
            return new Statement(name, operation, lines, scoreBands, bands, failSafe, challenge, overrideThresholdMinor,
                    holdHours);
        }

        Statement withFailSafe(List<Edge> edges) {
            return new Statement(name, operation, lines, scoreBands, ratingBands, edges, challenge,
                    overrideThresholdMinor, holdHours);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** The judged operations, in the fixtures' order, then the sweep's. */
    static final List<RiskOperation> OPERATIONS = List.of(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL,
            RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT, RiskOperation.CROSS_BORDER, RiskOperation.MONITORING);

    private static final Map<RiskOperation, Statement> V1 = new EnumMap<>(RiskOperation.class);
    private static final Map<RiskOperation, Statement> V2 = new EnumMap<>(RiskOperation.class);

    static {
        for (RiskOperation operation : OPERATIONS) {
            V1.put(operation, stateV1(operation));
            V2.put(operation, stateV2(operation));
        }
    }

    /** {@code operation}'s v1 version as the plan's C8 states it. */
    static Statement v1(RiskOperation operation) {
        return Objects.requireNonNull(V1.get(operation), operation + " has no v1");
    }

    /**
     * {@code operation}'s successor as the battery writes it - one threshold, one weight or lookback, one band edge and one
     * fail-safe edge changed - for the version swap: a seal replayed under it diverges exactly where the two versions judge
     * differently ({@code INV-RSK-05}). Test content; never a fixture.
     */
    static Statement v2(RiskOperation operation) {
        return Objects.requireNonNull(V2.get(operation), operation + " has no v2");
    }

    private static Statement stateV1(RiskOperation operation) {
        return switch (operation) {
            case TRANSFER -> judged("TRANSFER v1", operation, List.of(
                    hard(1, RuleKind.AMOUNT_BAND, RiskDecision.CHALLENGE, "threshold_minor", 500_00L),
                    hard(2, RuleKind.AMOUNT_BAND, RiskDecision.DENY, "threshold_minor", 10_000_00L),
                    hard(3, RuleKind.VELOCITY_COUNT, RiskDecision.REVIEW, "dimension", VelocityDimension.SOURCE_ACCOUNT,
                            "window", WindowKind.D1, "threshold_count", 10L),
                    weighted(4, RuleKind.VELOCITY_SUM, 400, "dimension", VelocityDimension.PARTY, "window", WindowKind.D1,
                            "threshold_minor", 5_000_00L),
                    weighted(5, RuleKind.NEW_PAYEE, 250, "lookback_days", 30),
                    weighted(6, RuleKind.AMOUNT_BAND, 200, "threshold_minor", 1_000_00L),
                    weighted(7, RuleKind.NEW_DEVICE, 200, "lookback_days", 30),
                    weighted(8, RuleKind.SESSION_ASSURANCE_BELOW, 100, "assurance", RiskAssurance.MULTI_FACTOR),
                    hard(9, RuleKind.SCREENING_HIT, RiskDecision.DENY),
                    hard(10, RuleKind.PROFILE_RATING, RiskDecision.DENY, "rating", RiskRating.PROHIBITED),
                    weighted(11, RuleKind.PROFILE_RATING, 300, "rating", RiskRating.HIGH),
                    weighted(12, RuleKind.RECENT_RECOVERY, 200, "lookback_hours", 72)),
                    fourBands(RiskDecision.REVIEW), twoEdges(1_000_00L), OptionalInt.empty());
            case WITHDRAWAL -> judged("WITHDRAWAL v1", operation, List.of(
                    hard(1, RuleKind.AMOUNT_BAND, RiskDecision.HOLD, "threshold_minor", 2_500_00L),
                    hard(2, RuleKind.RECENT_CREDENTIAL_CHANGE, RiskDecision.HOLD, "lookback_hours", 24),
                    weighted(3, RuleKind.RECENT_RECOVERY, 300, "lookback_hours", 72),
                    hard(4, RuleKind.VELOCITY_COUNT, RiskDecision.REVIEW, "dimension", VelocityDimension.SOURCE_ACCOUNT,
                            "window", WindowKind.D1, "threshold_count", 10L),
                    weighted(5, RuleKind.VELOCITY_SUM, 400, "dimension", VelocityDimension.PARTY, "window", WindowKind.D1,
                            "threshold_minor", 5_000_00L),
                    weighted(6, RuleKind.NEW_DEVICE, 200, "lookback_days", 30),
                    weighted(7, RuleKind.ACCOUNT_AGE_BELOW, 200, "days", 7),
                    hard(8, RuleKind.KYC_NOT_APPROVED, RiskDecision.DENY),
                    hard(9, RuleKind.SCREENING_HIT, RiskDecision.DENY),
                    hard(10, RuleKind.PROFILE_RATING, RiskDecision.DENY, "rating", RiskRating.PROHIBITED)),
                    fourBands(RiskDecision.HOLD), List.of(new Edge(0, OptionalLong.empty(), RiskDecision.DENY)),
                    OptionalInt.of(72));
            case CARD_PAYMENT, WALLET_PAYMENT -> judged(operation.name() + " v1", operation, List.of(
                    hard(1, RuleKind.AMOUNT_BAND, RiskDecision.CHALLENGE, "threshold_minor", 500_00L),
                    hard(2, RuleKind.AMOUNT_BAND, RiskDecision.DENY, "threshold_minor", 10_000_00L),
                    hard(3, RuleKind.MERCHANT_SUSPENDED, RiskDecision.DENY),
                    weighted(4, RuleKind.VELOCITY_COUNT, 300, "dimension", VelocityDimension.SOURCE_ACCOUNT, "window",
                            WindowKind.D1, "threshold_count", 10L),
                    weighted(5, RuleKind.VELOCITY_SUM, 400, "dimension", VelocityDimension.PARTY, "window", WindowKind.D1,
                            "threshold_minor", 5_000_00L),
                    weighted(6, RuleKind.NEW_DEVICE, 200, "lookback_days", 30),
                    weighted(7, RuleKind.RECENT_AUTH_FAILURES, 300, "window", WindowKind.H1, "threshold_count", 3L),
                    hard(8, RuleKind.SCREENING_HIT, RiskDecision.DENY),
                    hard(9, RuleKind.PROFILE_RATING, RiskDecision.DENY, "rating", RiskRating.PROHIBITED),
                    weighted(10, RuleKind.VELOCITY_COUNT, 300, "dimension", VelocityDimension.SESSION, "window",
                            WindowKind.H1, "threshold_count", 5L),
                    weighted(11, RuleKind.CHARGEBACK_RATIO, 100, "window", WindowKind.D30, "ratio_bps", 100,
                            "threshold_count", 10L),
                    weighted(12, RuleKind.REFUND_RATIO, 100, "window", WindowKind.D30, "ratio_bps", 2_000,
                            "threshold_count", 20L)),
                    List.of(new Band<>(0, 299, RiskDecision.ALLOW), new Band<>(300, 699, RiskDecision.CHALLENGE),
                            new Band<>(700, 1000, RiskDecision.DENY)),
                    twoEdges(250_00L), OptionalInt.empty());
            case CROSS_BORDER -> judged("CROSS_BORDER v1", operation, List.of(
                    hard(1, RuleKind.AMOUNT_BAND, RiskDecision.CHALLENGE, "threshold_minor", 500_00L),
                    hard(2, RuleKind.AMOUNT_BAND, RiskDecision.DENY, "threshold_minor", 10_000_00L),
                    weighted(3, RuleKind.CORRIDOR_HIGH_RISK, 400, "corridors", HIGH_RISK),
                    hard(4, RuleKind.VELOCITY_COUNT, RiskDecision.REVIEW, "dimension", VelocityDimension.SOURCE_ACCOUNT,
                            "window", WindowKind.D1, "threshold_count", 10L),
                    weighted(5, RuleKind.VELOCITY_SUM, 400, "dimension", VelocityDimension.PARTY, "window", WindowKind.D1,
                            "threshold_minor", 5_000_00L),
                    weighted(6, RuleKind.NEW_PAYEE, 250, "lookback_days", 30),
                    weighted(7, RuleKind.AMOUNT_BAND, 200, "threshold_minor", 1_000_00L),
                    weighted(8, RuleKind.NEW_DEVICE, 200, "lookback_days", 30),
                    hard(9, RuleKind.SCREENING_HIT, RiskDecision.DENY),
                    hard(10, RuleKind.PROFILE_RATING, RiskDecision.DENY, "rating", RiskRating.PROHIBITED)),
                    fourBands(RiskDecision.REVIEW), List.of(new Edge(0, OptionalLong.empty(), RiskDecision.DENY)),
                    OptionalInt.empty());
            case MONITORING -> new Statement("MONITORING v1", operation, List.of(
                    weighted(1, RuleKind.STRUCTURING, 500, "window", WindowKind.D1, "below_minor", 1_000_00L,
                            "threshold_count", 3L, "sum_minor", 2_500_00L),
                    weighted(2, RuleKind.RAPID_IN_OUT, 400, "window", WindowKind.D1, "ratio_bps", 8_000),
                    weighted(3, RuleKind.DORMANT_THEN_ACTIVE, 300, "dormant_days", 90, "window", WindowKind.D1,
                            "threshold_minor", 1_000_00L),
                    weighted(4, RuleKind.ROUND_AMOUNT, 100, "modulus_minor", 1_000_00L),
                    weighted(5, RuleKind.CORRIDOR_HIGH_RISK, 300, "corridors", HIGH_RISK),
                    weighted(6, RuleKind.SCREENING_HIT, 600),
                    weighted(7, RuleKind.VELOCITY_SUM, 200, "dimension", VelocityDimension.PARTY, "window", WindowKind.D30,
                            "threshold_minor", 50_000_00L),
                    weighted(8, RuleKind.VELOCITY_COUNT, 200, "dimension", VelocityDimension.PARTY, "window",
                            WindowKind.D30, "threshold_count", 100L),
                    weighted(9, RuleKind.REFUND_RATIO, 200, "window", WindowKind.D30, "ratio_bps", 2_000,
                            "threshold_count", 3L),
                    weighted(10, RuleKind.CHARGEBACK_RATIO, 300, "window", WindowKind.D30, "ratio_bps", 100,
                            "threshold_count", 2L),
                    weighted(11, RuleKind.BNPL_MULTI_APPLICATION, 200, "window", WindowKind.D7, "threshold_count", 3L),
                    weighted(12, RuleKind.RECENT_RECOVERY, 100, "lookback_hours", 72)),
                    List.of(), List.of(new Band<>(0, 299, RiskRating.LOW), new Band<>(300, 699, RiskRating.MEDIUM),
                            new Band<>(700, 1000, RiskRating.HIGH)),
                    List.of(), Optional.empty(), OptionalLong.empty(), OptionalInt.empty());
            case CREDIT_SIGNAL -> throw new IllegalArgumentException("the credit signal carries no policy");
        };
    }

    private static Statement stateV2(RiskOperation operation) {
        Statement v1 = stateV1(operation);
        String name = operation.name() + " v2";
        return switch (operation) {
            case TRANSFER -> v1.named(name).withLine(v1.line(1).with("threshold_minor", 450_00L))
                    .withLine(v1.line(5).weighing(300))
                    .withScoreBands(List.of(new Band<>(0, 249, RiskDecision.ALLOW),
                            new Band<>(250, 599, RiskDecision.CHALLENGE), new Band<>(600, 799, RiskDecision.REVIEW),
                            new Band<>(800, 1000, RiskDecision.DENY)))
                    .withFailSafe(twoEdges(800_00L));
            case WITHDRAWAL -> v1.named(name).withLine(v1.line(1).with("threshold_minor", 2_000_00L))
                    .withLine(v1.line(7).with("days", 14))
                    .withFailSafe(twoEdges(100_00L));
            case CARD_PAYMENT, WALLET_PAYMENT -> v1.named(name).withLine(v1.line(6).with("lookback_days", 60))
                    .withScoreBands(List.of(new Band<>(0, 299, RiskDecision.ALLOW),
                            new Band<>(300, 649, RiskDecision.CHALLENGE), new Band<>(650, 1000, RiskDecision.DENY)))
                    .withFailSafe(twoEdges(200_00L));
            case CROSS_BORDER -> {
                SortedSet<String> widened = new TreeSet<>(HIGH_RISK);
                widened.add("EUR-CHF-CH");
                yield v1.named(name).withLine(v1.line(3).with("corridors", Collections.unmodifiableSortedSet(widened)))
                        .withLine(v1.line(7).with("threshold_minor", 900_00L));
            }
            case MONITORING -> v1.named(name).withLine(v1.line(1).with("below_minor", 900_00L))
                    .withRatingBands(List.of(new Band<>(0, 249, RiskRating.LOW), new Band<>(250, 699, RiskRating.MEDIUM),
                            new Band<>(700, 1000, RiskRating.HIGH)));
            case CREDIT_SIGNAL -> throw new IllegalArgumentException("the credit signal carries no policy");
        };
    }

    private static Statement judged(String name, RiskOperation operation, List<Line> lines,
            List<Band<RiskDecision>> bands, List<Edge> failSafe, OptionalInt holdHours) {
        // C7: the override threshold is EUR 1,000; a CHALLENGE is satisfied by MULTI_FACTOR.
        return new Statement(name, operation, lines, bands, List.of(), failSafe, Optional.of(RiskAssurance.MULTI_FACTOR),
                OptionalLong.of(1_000_00L), holdHours);
    }

    /** ALLOW to 299, CHALLENGE to 599, {@code third} to 799, DENY from 800. */
    private static List<Band<RiskDecision>> fourBands(RiskDecision third) {
        return List.of(new Band<>(0, 299, RiskDecision.ALLOW), new Band<>(300, 599, RiskDecision.CHALLENGE),
                new Band<>(600, 799, third), new Band<>(800, 1000, RiskDecision.DENY));
    }

    /** C1's two-band shape: ALLOW recorded below {@code thresholdMinor}, DENY from it. */
    private static List<Edge> twoEdges(long thresholdMinor) {
        return List.of(new Edge(0, OptionalLong.of(thresholdMinor), RiskDecision.ALLOW),
                new Edge(thresholdMinor, OptionalLong.empty(), RiskDecision.DENY));
    }

    private static Line weighted(int ordinal, RuleKind kind, int weight, Object... parameters) {
        return new Line(ordinal, kind, parameters(parameters), OptionalInt.of(weight), Optional.empty());
    }

    private static Line hard(int ordinal, RuleKind kind, RiskDecision decision, Object... parameters) {
        return new Line(ordinal, kind, parameters(parameters), OptionalInt.empty(), Optional.of(decision));
    }

    private static SortedMap<String, Object> parameters(Object... pairs) {
        SortedMap<String, Object> map = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------ what a verdict must be

    /** Which step of the decision model decided (`Explanation`'s three, and the sweep's rating). */
    enum Step {
        HARD_RULE,
        SCORE_BAND,
        FAIL_SAFE,
        RATING
    }

    /**
     * One rule as it must be judged. {@code evidence} is what the hit records beside its outcome that depends on the
     * version and not on the reading alone - empty for every kind but {@code STRUCTURING}, whose judged token names the
     * count and the sum of the window's amounts <em>below the version's own {@code below_minor}</em> (the as-built token
     * {@code amounts:<window>:n=..,below=..,belowSum=..}): so a version changing only {@code below_minor} changes the hit,
     * and the replay, even where no outcome changes - this battery's finding, stated here so the swap's prediction is the
     * engine's truth and not a guess.
     */
    record Judged(int ordinal, RuleKind kind, RuleOutcome outcome, int contribution, Optional<RiskDecision> hard,
            List<Long> evidence) {
        Judged {
            evidence = List.copyOf(evidence);
        }
    }

    /**
     * What one evaluation must come to. {@code refusal} is non-empty when the engine must refuse ({@code CURRENCY}: an
     * amount the version's currency cannot place; {@code ADMISSION}: a composed decision the operation does not admit) -
     * then nothing else is meaningful. {@code bandPosition} is the 1-based position of the score band, rating band or
     * fail-safe band reached among its version's (0: none - the compiled answer).
     */
    record Expected(RiskOperation operation, Optional<String> refusal, List<Judged> rules, int score,
            Optional<RiskDecision> decision, Optional<RiskRating> rating, List<RuleKind> reasons, Step step,
            List<Integer> deciding, int bandPosition, Optional<String> failSafeReason, Optional<Boolean> challengeSatisfied,
            boolean permits) {

        Expected {
            rules = List.copyOf(rules);
            reasons = List.copyOf(reasons);
            deciding = List.copyOf(deciding);
        }

        static Expected refused(RiskOperation operation, String why) {
            return new Expected(operation, Optional.of(why), List.of(), 0, Optional.empty(), Optional.empty(), List.of(),
                    Step.FAIL_SAFE, List.of(), 0, Optional.empty(), Optional.empty(), false);
        }

        boolean isRefused() {
            return refusal.isPresent();
        }

        /** The first rule judged differently from {@code other}'s (outcome, contribution, hard decision), or 0. */
        int firstDifference(Expected other) {
            int shorter = Math.min(rules.size(), other.rules.size());
            for (int i = 0; i < shorter; i++) {
                if (!rules.get(i).equals(other.rules.get(i))) {
                    return i + 1;
                }
            }
            return rules.size() == other.rules.size() ? 0 : shorter + 1;
        }

        /** True when {@code other} is the same verdict - every rule, the score, the decision or rating. */
        boolean sameVerdict(Expected other) {
            return rules.equals(other.rules) && score == other.score && decision.equals(other.decision)
                    && rating.equals(other.rating) && failSafeReason.equals(other.failSafeReason);
        }
    }

    /**
     * The verdict {@code statement} gives {@code in} - every rule in declared order, the clamp, the bands, the precedence,
     * the admission; or the refusal the engine must answer.
     */
    static Expected judge(Statement statement, EvaluationInputs in) {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(in, "in");
        RiskOperation operation = statement.operation();
        if (in.operation() != operation) {
            throw new IllegalArgumentException("inputs for " + in.operation() + " judged under " + statement);
        }
        Optional<Money> amount = in.amount().value();
        if (amount.isPresent() && !amount.get().currency().code().equals(CURRENCY)) {
            return Expected.refused(operation, "CURRENCY");
        }
        List<Judged> rules = new ArrayList<>(statement.lines().size());
        List<RuleKind> reasons = new ArrayList<>();
        long sum = 0;
        for (Line line : statement.lines()) {
            RuleOutcome outcome = outcome(line, in);
            boolean fired = outcome == RuleOutcome.FIRED;
            int contribution = fired && line.weight().isPresent() ? line.weight().getAsInt() : 0;
            List<Long> evidence = line.kind() == RuleKind.STRUCTURING && outcome != RuleOutcome.ABSENT
                    ? below(line, in) : List.of();
            rules.add(new Judged(line.ordinal(), line.kind(), outcome, contribution,
                    fired ? line.hard() : Optional.empty(), evidence));
            if (fired) {
                reasons.add(line.kind());
            }
            sum += contribution;
        }
        int score = (int) Math.min(CEILING, Math.max(0, sum));
        if (statement.monitoring()) {
            int position = position(statement.ratingBands(), score);
            RiskRating rating = statement.ratingBands().get(position - 1).answer();
            return new Expected(operation, Optional.empty(), rules, score, Optional.empty(), Optional.of(rating),
                    reasons, Step.RATING, List.of(), position, Optional.empty(), Optional.empty(), false);
        }
        int position = position(statement.scoreBands(), score);
        RiskDecision band = statement.scoreBands().get(position - 1).answer();
        RiskDecision decision = band;
        for (Judged rule : rules) {
            if (rule.hard().isPresent() && rank(rule.hard().get()) < rank(decision)) {
                decision = rule.hard().get();
            }
        }
        if (!admits(operation).contains(decision)) {
            return Expected.refused(operation, "ADMISSION");
        }
        List<Integer> deciding = new ArrayList<>();
        Step step = Step.SCORE_BAND;
        if (decision != band) {
            // Only a fired hard decision can be stricter than the band's: it decided, with every rule that carried it.
            step = Step.HARD_RULE;
            for (Judged rule : rules) {
                if (rule.hard().equals(Optional.of(decision))) {
                    deciding.add(rule.ordinal());
                }
            }
        }
        Optional<Boolean> satisfied = challengeAnswer(statement, decision, in);
        boolean permits = decision == RiskDecision.ALLOW || satisfied.orElse(false);
        return new Expected(operation, Optional.empty(), rules, score, Optional.of(decision), Optional.empty(), reasons,
                step, deciding, position, Optional.empty(), satisfied, permits);
    }

    /**
     * The fail-safe the evaluation must record when it was unavailable (ADR-0108 decision 4, C1, the `-004` as-built):
     * with no policy the compiled {@code ALLOW} under {@code NO_POLICY_ACTIVE}; with the pin unread the compiled
     * {@code ALLOW} under {@code RISK_UNAVAILABLE}; otherwise the pinned statement's fail-safe band for the amount under
     * {@code RISK_UNAVAILABLE} - the open-ended last band for an amount in another currency, which no band can place.
     */
    static Expected failSafe(Optional<Statement> pinned, RiskOperation operation, UnavailableCause cause, Money amount) {
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(amount, "amount");
        RiskDecision decision;
        String reason;
        int position = 0;
        switch (cause) {
            case NO_POLICY -> {
                decision = RiskDecision.ALLOW;
                reason = "NO_POLICY_ACTIVE";
            }
            case PIN_UNREADABLE -> {
                decision = RiskDecision.ALLOW;
                reason = "RISK_UNAVAILABLE";
            }
            default -> {
                List<Edge> edges = pinned.orElseThrow(() -> new IllegalArgumentException(cause + " pins a version"))
                        .failSafe();
                position = edges.size();
                if (amount.currency().code().equals(CURRENCY)) {
                    long minor = amount.minorUnits();
                    for (int i = 0; i < edges.size(); i++) {
                        Edge edge = edges.get(i);
                        if (minor >= edge.fromMinor() && (edge.toMinor().isEmpty() || minor < edge.toMinor().getAsLong())) {
                            position = i + 1;
                            break;
                        }
                    }
                }
                decision = edges.get(position - 1).decision();
                reason = "RISK_UNAVAILABLE";
            }
        }
        return new Expected(operation, Optional.empty(), List.of(), 0, Optional.of(decision), Optional.empty(), List.of(),
                Step.FAIL_SAFE, List.of(), position, Optional.of(reason), Optional.empty(), decision == RiskDecision.ALLOW);
    }

    /**
     * A {@code CHALLENGE}'s answer (plan section 12.3): satisfied when the sealed session's assurance is at least the
     * version's challenge assurance - an absent session unsatisfied; no answer for any other decision.
     */
    static Optional<Boolean> challengeAnswer(Statement statement, RiskDecision decision, EvaluationInputs in) {
        if (decision != RiskDecision.CHALLENGE) {
            return Optional.empty();
        }
        RiskAssurance required = statement.challenge().orElse(RiskAssurance.MULTI_FACTOR);
        return Optional.of(in.sessionAssurance().value()
                .map(held -> STRENGTH.indexOf(held) >= STRENGTH.indexOf(required)).orElse(false));
    }

    /** The decisions {@code operation}'s consumer can honour (plan section 12.1, `-010`'s card {@code HOLD}). */
    static List<RiskDecision> admits(RiskOperation operation) {
        return switch (operation) {
            case TRANSFER, CROSS_BORDER -> List.of(RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY,
                    RiskDecision.REVIEW);
            case WITHDRAWAL -> List.of(RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY, RiskDecision.HOLD,
                    RiskDecision.REVIEW);
            case CARD_PAYMENT -> List.of(RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY, RiskDecision.HOLD);
            case WALLET_PAYMENT -> List.of(RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY);
            case CREDIT_SIGNAL, MONITORING -> List.of();
        };
    }

    /** {@code decision}'s rank in the precedence - 0 the strictest. */
    static int rank(RiskDecision decision) {
        int rank = PRECEDENCE.indexOf(decision);
        if (rank < 0) {
            throw new IllegalArgumentException("no precedence for " + decision);
        }
        return rank;
    }

    /** The 1-based position of the band holding {@code score}. */
    static <T> int position(List<Band<T>> bands, int score) {
        for (int i = 0; i < bands.size(); i++) {
            if (score >= bands.get(i).from() && score <= bands.get(i).to()) {
                return i + 1;
            }
        }
        throw new IllegalStateException("no band holds " + score);
    }

    // ------------------------------------------------------------------ each kind, stated

    /**
     * How {@code line} judges {@code in} - each kind as section 12.2 and the kind's own documentation state it: at or
     * above a threshold ({@code >=}); a lookback "within" meaning fewer than the named units ago, a never-seen payee or
     * device new under any lookback, a never-happened change never recent; an account "below" an age by {@code <}; a
     * share at or above its basis points by cross-multiplication with a positive denominator, beside its count floor;
     * structuring's sum strictly above; a round amount a positive multiple (zero is not round - the plan's section 12.2,
     * as this battery's gate stated it when it found the kind's own statement silent). A reading the inputs do not hold
     * is {@code ABSENT}, never a fire.
     */
    static RuleOutcome outcome(Line line, EvaluationInputs in) {
        return switch (line.kind()) {
            case AMOUNT_BAND -> in.amount().value()
                    .map(money -> fired(money.minorUnits() >= line.number("threshold_minor"))).orElse(RuleOutcome.ABSENT);
            case VELOCITY_COUNT -> velocity(line, in).map(reading -> fired(reading.count() >= line.number("threshold_count")))
                    .orElse(RuleOutcome.ABSENT);
            case VELOCITY_SUM -> velocity(line, in).map(reading -> fired(reading.sumMinor() >= line.number("threshold_minor")))
                    .orElse(RuleOutcome.ABSENT);
            case NEW_PAYEE -> in.payeeFirstSeen().value().map(seen -> fired(isNew(seen.daysAgo(), line.number("lookback_days"))))
                    .orElse(RuleOutcome.ABSENT);
            case NEW_DEVICE -> in.deviceFirstSeen().value().map(seen -> fired(isNew(seen.daysAgo(), line.number("lookback_days"))))
                    .orElse(RuleOutcome.ABSENT);
            case SESSION_ASSURANCE_BELOW -> in.sessionAssurance().value().map(held -> fired(
                    STRENGTH.indexOf(held) < STRENGTH.indexOf(line.value("assurance", RiskAssurance.class))))
                    .orElse(RuleOutcome.ABSENT);
            case RECENT_AUTH_FAILURES -> {
                Long failures = in.authFailures().get(line.value("window", WindowKind.class));
                yield failures == null ? RuleOutcome.ABSENT : fired(failures >= line.number("threshold_count"));
            }
            case RECENT_CREDENTIAL_CHANGE -> in.credentialChange().value()
                    .map(last -> fired(isRecent(last.hoursAgo(), line.number("lookback_hours")))).orElse(RuleOutcome.ABSENT);
            case RECENT_RECOVERY -> in.recovery().value()
                    .map(last -> fired(isRecent(last.hoursAgo(), line.number("lookback_hours")))).orElse(RuleOutcome.ABSENT);
            case ACCOUNT_AGE_BELOW -> in.accountAgeDays().value().map(age -> fired(age < line.number("days")))
                    .orElse(RuleOutcome.ABSENT);
            case KYC_NOT_APPROVED -> in.kycApproved().value().map(approved -> fired(!approved)).orElse(RuleOutcome.ABSENT);
            case SCREENING_HIT -> in.screeningHit().value().map(RiskBatteryOracles::fired).orElse(RuleOutcome.ABSENT);
            case PROFILE_RATING -> in.profileRating().value().map(rating -> fired(
                    SEVERITY.indexOf(rating) >= SEVERITY.indexOf(line.value("rating", RiskRating.class))))
                    .orElse(RuleOutcome.ABSENT);
            case MERCHANT_SUSPENDED -> in.merchantSuspended().value().map(RiskBatteryOracles::fired)
                    .orElse(RuleOutcome.ABSENT);
            case CORRIDOR_HIGH_RISK -> in.corridor().value()
                    .map(corridor -> fired(line.parameters().get("corridors") instanceof SortedSet<?> listed
                            && listed.contains(corridor)))
                    .orElse(RuleOutcome.ABSENT);
            case DORMANT_THEN_ACTIVE -> {
                WindowKind window = line.value("window", WindowKind.class);
                Optional<Integer> dormant = in.monitoring().dormantDays().value();
                VelocityReading party = in.velocity().get(new VelocityKey(VelocityDimension.PARTY, window));
                yield dormant.isEmpty() || party == null ? RuleOutcome.ABSENT
                        : fired(dormant.get() >= line.number("dormant_days")
                                && party.sumMinor() >= line.number("threshold_minor"));
            }
            case RAPID_IN_OUT -> {
                Flow flow = in.monitoring().flows().get(line.value("window", WindowKind.class));
                yield flow == null ? RuleOutcome.ABSENT : fired(flow.inMinor() > 0
                        && flow.outMinor() * 10_000L >= line.number("ratio_bps") * flow.inMinor());
            }
            case STRUCTURING -> {
                if (in.monitoring().amounts().get(line.value("window", WindowKind.class)) == null) {
                    yield RuleOutcome.ABSENT;
                }
                List<Long> below = below(line, in);
                yield fired(below.get(0) >= line.number("threshold_count") && below.get(1) > line.number("sum_minor"));
            }
            case ROUND_AMOUNT -> in.amount().value().map(money -> fired(money.minorUnits() > 0
                    && money.minorUnits() % line.number("modulus_minor") == 0)).orElse(RuleOutcome.ABSENT);
            case REFUND_RATIO -> share(in.monitoring().refunds(), line);
            case CHARGEBACK_RATIO -> share(in.monitoring().chargebacks(), line);
            case BNPL_MULTI_APPLICATION -> {
                Long count = in.monitoring().bnplApplications().get(line.value("window", WindowKind.class));
                yield count == null ? RuleOutcome.ABSENT : fired(count >= line.number("threshold_count"));
            }
        };
    }

    /** The count and the sum of the window's amounts strictly below {@code below_minor}. */
    static List<Long> below(Line line, EvaluationInputs in) {
        long below = line.number("below_minor");
        long count = 0;
        long sum = 0;
        for (long minor : in.monitoring().amounts().get(line.value("window", WindowKind.class)).minor()) {
            if (minor < below) {
                count++;
                sum += minor;
            }
        }
        return List.of(count, sum);
    }

    private static RuleOutcome fired(boolean fired) {
        return fired ? RuleOutcome.FIRED : RuleOutcome.NOT_FIRED;
    }

    private static Optional<VelocityReading> velocity(Line line, EvaluationInputs in) {
        return Optional.ofNullable(in.velocity().get(new VelocityKey(line.value("dimension", VelocityDimension.class),
                line.value("window", WindowKind.class))));
    }

    /** Never seen is new; seen {@code d} days ago is new when {@code d} is fewer than the lookback. */
    private static boolean isNew(OptionalInt daysAgo, long lookbackDays) {
        return daysAgo.isEmpty() || daysAgo.getAsInt() < lookbackDays;
    }

    /** Never happened is never recent; {@code h} hours ago is recent when fewer than the lookback. */
    private static boolean isRecent(OptionalLong hoursAgo, long lookbackHours) {
        return hoursAgo.isPresent() && hoursAgo.getAsLong() < lookbackHours;
    }

    /** A share over its count floor: at least the floor, a positive denominator, {@code num * 10000 >= bps * den}. */
    private static RuleOutcome share(Map<WindowKind, Ratio> shares, Line line) {
        Ratio ratio = shares.get(line.value("window", WindowKind.class));
        if (ratio == null) {
            return RuleOutcome.ABSENT;
        }
        return fired(ratio.numerator() >= line.number("threshold_count") && ratio.denominator() > 0
                && ratio.numerator() * 10_000L >= line.number("ratio_bps") * ratio.denominator());
    }
}
