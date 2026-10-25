package com.finapp.risk.testing;

import com.finapp.risk.FailSafeBand;
import com.finapp.risk.ParameterValue;
import com.finapp.risk.RatingBand;
import com.finapp.risk.RiskAssurance;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEngine;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskRating;
import com.finapp.risk.RiskRule;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleParameters;
import com.finapp.risk.ScoreBand;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.WindowKind;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The policy v1 fixtures - test only (`P13-TSK-002`; owner decision C8, {@code PHASE_13_PLAN.md} section 12.2,
 * ADR-0109 decision 7; the credit policy fixture precedent, {@code CreditPolicyV1}): per judged operation a version
 * with {@code AMOUNT_BAND} (challenge at EUR 500, deny at EUR 10,000 for transfers; withdrawals hold at EUR 2,500),
 * {@code VELOCITY_COUNT} (10 per 24 h per source account), {@code VELOCITY_SUM} (EUR 5,000 per 24 h per party), the
 * {@code NEW_PAYEE} + {@code AMOUNT_BAND} composite weight (each alone below the challenge band, together inside it),
 * the {@code NEW_DEVICE} weight, {@code RECENT_CREDENTIAL_CHANGE} + withdrawal → {@code HOLD}, {@code SCREENING_HIT} →
 * {@code DENY}, {@code PROFILE_RATING = PROHIBITED} → {@code DENY}, and C1's fail-safe bands; the monitoring version
 * with {@code STRUCTURING} (≥ 3 movements each below EUR 1,000 summing above EUR 2,500 in 24 h), {@code RAPID_IN_OUT}
 * (≥ 80 % out within 24 h of in), {@code DORMANT_THEN_ACTIVE} (90 days dormant, then ≥ EUR 1,000 in 24 h), the
 * {@code ROUND_AMOUNT} weight, {@code CORRIDOR_HIGH_RISK} and its rating bands. Every one of the twenty-two kinds is
 * used by some fixture, so the engine (`-003`) and the battery have a parameterised row of each. The account-takeover
 * rules (`P13-TSK-013`): the withdrawal's {@code RECENT_CREDENTIAL_CHANGE} (24 h) {@code HOLD} and
 * {@code RECENT_RECOVERY} (72 h) weight, confirmed; the transfer's {@code NEW_PAYEE} + {@code NEW_DEVICE} +
 * {@code RECENT_RECOVERY} composite reaching {@code REVIEW}; the monitoring version's {@code RECENT_RECOVERY}, the
 * retrospective anchor.
 *
 * <p><strong>Production activates none</strong>: no migration seeds a version; these are the suites', the engine's,
 * the battery's and the storm's input, proposed through the domain and activated by two test persons. This class
 * ships in no jar.
 */
public final class RiskPolicyFixtures {

    public static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** C7: the override threshold, EUR 1,000. */
    public static final long OVERRIDE_THRESHOLD_MINOR = 1_000_00L;

    /** The corridors the fixtures treat as high-risk - shapes only ({@code SRC-DST-CC}), test content. */
    public static final Set<String> HIGH_RISK_CORRIDORS = Set.of("EUR-USD-PA", "EUR-GBP-KY", "EUR-AED-AE");

    /**
     * "10 per 24 h per source account" counts the account the money leaves (`P13-TSK-006`: {@code ACCOUNT} until the
     * velocity dimension became a {@link VelocityDimension}, the coordinator's decision at wave C).
     */
    private static final VelocityDimension SOURCE_ACCOUNT = VelocityDimension.SOURCE_ACCOUNT;

    private RiskPolicyFixtures() {}

    /** The operations a v1 fixture exists for: the five judged and the sweep's. */
    public static List<RiskOperation> operations() {
        return List.of(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL, RiskOperation.CARD_PAYMENT,
                RiskOperation.WALLET_PAYMENT, RiskOperation.CROSS_BORDER, RiskOperation.MONITORING);
    }

    /** Every fixture, keyed by its operation. */
    public static Map<RiskOperation, RiskPolicy> all() {
        Map<RiskOperation, RiskPolicy> all = new EnumMap<>(RiskOperation.class);
        for (RiskOperation operation : operations()) {
            all.put(operation, v1(operation));
        }
        return all;
    }

    /** {@code operation}'s v1 fixture. */
    public static RiskPolicy v1(RiskOperation operation) {
        return switch (operation) {
            case TRANSFER -> transfer();
            case WITHDRAWAL -> withdrawal();
            case CARD_PAYMENT, WALLET_PAYMENT -> payment(operation);
            case CROSS_BORDER -> crossBorder();
            case MONITORING -> monitoring();
            case CREDIT_SIGNAL -> throw new IllegalArgumentException("the credit signal carries no policy");
        };
    }

    // ------------------------------------------------------------------ the judged operations

    static RiskPolicy transfer() {
        Rules rules = new Rules();
        rules.deciding(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", minor(500_00)), RiskDecision.CHALLENGE);
        rules.deciding(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", minor(10_000_00)), RiskDecision.DENY);
        rules.deciding(RuleKind.VELOCITY_COUNT, velocity(SOURCE_ACCOUNT, WindowKind.D1, "threshold_count",
                count(10)), RiskDecision.REVIEW);
        rules.weighted(RuleKind.VELOCITY_SUM, velocity(VelocityDimension.PARTY, WindowKind.D1, "threshold_minor",
                minor(5_000_00)), 400);
        // The composite: a new payee alone (250) and a mid amount alone (200) each stay in ALLOW; together (450) they
        // reach CHALLENGE.
        rules.weighted(RuleKind.NEW_PAYEE, Map.of("lookback_days", days(30)), 250);
        rules.weighted(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", minor(1_000_00)), 200);
        rules.weighted(RuleKind.NEW_DEVICE, Map.of("lookback_days", days(30)), 200);
        rules.weighted(RuleKind.SESSION_ASSURANCE_BELOW, Map.of("assurance", assurance(RiskAssurance.MULTI_FACTOR)), 100);
        rules.deciding(RuleKind.SCREENING_HIT, Map.of(), RiskDecision.DENY);
        rules.deciding(RuleKind.PROFILE_RATING, Map.of("rating", rating(RiskRating.PROHIBITED)), RiskDecision.DENY);
        rules.weighted(RuleKind.PROFILE_RATING, Map.of("rating", rating(RiskRating.HIGH)), 300);
        // P13-TSK-013, the account-takeover composite (appended - the ordinals above unchanged): a new payee (250), a new
        // device (200) and a recovery within 72 h (200) together reach REVIEW (650); any two stay below it. With the
        // device ABSENT on every movement today, payee + recovery is 450 (CHALLENGE), and with the EUR 1,000 weight 650.
        rules.weighted(RuleKind.RECENT_RECOVERY, Map.of("lookback_hours", hours(72)), 200);
        return judged(RiskOperation.TRANSFER, rules, fourBands(RiskDecision.REVIEW),
                failSafe(1_000_00, RiskDecision.ALLOW, RiskDecision.DENY), Optional.empty());
    }

    static RiskPolicy withdrawal() {
        Rules rules = new Rules();
        rules.deciding(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", minor(2_500_00)), RiskDecision.HOLD);
        rules.deciding(RuleKind.RECENT_CREDENTIAL_CHANGE, Map.of("lookback_hours", hours(24)), RiskDecision.HOLD);
        rules.weighted(RuleKind.RECENT_RECOVERY, Map.of("lookback_hours", hours(72)), 300);
        rules.deciding(RuleKind.VELOCITY_COUNT, velocity(SOURCE_ACCOUNT, WindowKind.D1, "threshold_count",
                count(10)), RiskDecision.REVIEW);
        rules.weighted(RuleKind.VELOCITY_SUM, velocity(VelocityDimension.PARTY, WindowKind.D1, "threshold_minor",
                minor(5_000_00)), 400);
        rules.weighted(RuleKind.NEW_DEVICE, Map.of("lookback_days", days(30)), 200);
        rules.weighted(RuleKind.ACCOUNT_AGE_BELOW, Map.of("days", days(7)), 200);
        rules.deciding(RuleKind.KYC_NOT_APPROVED, Map.of(), RiskDecision.DENY);
        rules.deciding(RuleKind.SCREENING_HIT, Map.of(), RiskDecision.DENY);
        rules.deciding(RuleKind.PROFILE_RATING, Map.of("rating", rating(RiskRating.PROHIBITED)), RiskDecision.DENY);
        return judged(RiskOperation.WITHDRAWAL, rules, fourBands(RiskDecision.HOLD),
                failSafe(RiskDecision.DENY), Optional.of(RiskPolicy.DEFAULT_HOLD_VALIDITY));
    }

    static RiskPolicy payment(RiskOperation operation) {
        Rules rules = new Rules();
        rules.deciding(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", minor(500_00)), RiskDecision.CHALLENGE);
        rules.deciding(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", minor(10_000_00)), RiskDecision.DENY);
        rules.deciding(RuleKind.MERCHANT_SUSPENDED, Map.of(), RiskDecision.DENY);
        rules.weighted(RuleKind.VELOCITY_COUNT, velocity(SOURCE_ACCOUNT, WindowKind.D1, "threshold_count",
                count(10)), 300);
        rules.weighted(RuleKind.VELOCITY_SUM, velocity(VelocityDimension.PARTY, WindowKind.D1, "threshold_minor",
                minor(5_000_00)), 400);
        rules.weighted(RuleKind.NEW_DEVICE, Map.of("lookback_days", days(30)), 200);
        rules.weighted(RuleKind.RECENT_AUTH_FAILURES, Map.of("window", window(WindowKind.H1), "threshold_count",
                count(3)), 300);
        rules.deciding(RuleKind.SCREENING_HIT, Map.of(), RiskDecision.DENY);
        rules.deciding(RuleKind.PROFILE_RATING, Map.of("rating", rating(RiskRating.PROHIBITED)), RiskDecision.DENY);
        return judged(operation, rules,
                List.of(new ScoreBand(0, 299, RiskDecision.ALLOW), new ScoreBand(300, 699, RiskDecision.CHALLENGE),
                        new ScoreBand(700, 1000, RiskDecision.DENY)),
                failSafe(250_00, RiskDecision.ALLOW, RiskDecision.DENY), Optional.empty());
    }

    static RiskPolicy crossBorder() {
        Rules rules = new Rules();
        rules.deciding(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", minor(500_00)), RiskDecision.CHALLENGE);
        rules.deciding(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", minor(10_000_00)), RiskDecision.DENY);
        rules.weighted(RuleKind.CORRIDOR_HIGH_RISK, Map.of("corridors", corridors()), 400);
        rules.deciding(RuleKind.VELOCITY_COUNT, velocity(SOURCE_ACCOUNT, WindowKind.D1, "threshold_count",
                count(10)), RiskDecision.REVIEW);
        rules.weighted(RuleKind.VELOCITY_SUM, velocity(VelocityDimension.PARTY, WindowKind.D1, "threshold_minor",
                minor(5_000_00)), 400);
        rules.weighted(RuleKind.NEW_PAYEE, Map.of("lookback_days", days(30)), 250);
        rules.weighted(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", minor(1_000_00)), 200);
        rules.weighted(RuleKind.NEW_DEVICE, Map.of("lookback_days", days(30)), 200);
        rules.deciding(RuleKind.SCREENING_HIT, Map.of(), RiskDecision.DENY);
        rules.deciding(RuleKind.PROFILE_RATING, Map.of("rating", rating(RiskRating.PROHIBITED)), RiskDecision.DENY);
        return judged(RiskOperation.CROSS_BORDER, rules, fourBands(RiskDecision.REVIEW),
                failSafe(RiskDecision.DENY), Optional.empty());
    }

    // ------------------------------------------------------------------ the sweep's

    static RiskPolicy monitoring() {
        Rules rules = new Rules();
        rules.weighted(RuleKind.STRUCTURING, Map.of("window", window(WindowKind.D1), "below_minor", minor(1_000_00),
                "threshold_count", count(3), "sum_minor", minor(2_500_00)), 500);
        rules.weighted(RuleKind.RAPID_IN_OUT, Map.of("window", window(WindowKind.D1), "ratio_bps", bps(8_000)), 400);
        rules.weighted(RuleKind.DORMANT_THEN_ACTIVE, Map.of("dormant_days", days(90), "window", window(WindowKind.D1),
                "threshold_minor", minor(1_000_00)), 300);
        rules.weighted(RuleKind.ROUND_AMOUNT, Map.of("modulus_minor", minor(1_000_00)), 100);
        rules.weighted(RuleKind.CORRIDOR_HIGH_RISK, Map.of("corridors", corridors()), 300);
        rules.weighted(RuleKind.SCREENING_HIT, Map.of(), 600);
        rules.weighted(RuleKind.VELOCITY_SUM, velocity(VelocityDimension.PARTY, WindowKind.D30, "threshold_minor",
                minor(50_000_00)), 200);
        rules.weighted(RuleKind.VELOCITY_COUNT, velocity(VelocityDimension.PARTY, WindowKind.D30, "threshold_count",
                count(100)), 200);
        rules.weighted(RuleKind.REFUND_RATIO, Map.of("window", window(WindowKind.D30), "ratio_bps", bps(2_000)), 200);
        rules.weighted(RuleKind.CHARGEBACK_RATIO, Map.of("window", window(WindowKind.D30), "ratio_bps", bps(100)), 300);
        rules.weighted(RuleKind.BNPL_MULTI_APPLICATION, Map.of("window", window(WindowKind.D7), "threshold_count",
                count(3)), 200);
        // P13-TSK-013, the retrospective account-takeover anchor (appended): a recovery completed within 72 h fires the
        // profile leg's ACCOUNT_TAKEOVER alert; its weight (100) alone keeps a party in LOW. Not RECENT_CREDENTIAL_CHANGE:
        // a password change alone would alert every customer who changes one.
        rules.weighted(RuleKind.RECENT_RECOVERY, Map.of("lookback_hours", hours(72)), 100);
        return new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, EUR, rules.list(), List.of(),
                List.of(new RatingBand(0, 299, RiskRating.LOW), new RatingBand(300, 699, RiskRating.MEDIUM),
                        new RatingBand(700, 1000, RiskRating.HIGH)),
                List.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ------------------------------------------------------------------ the shapes

    private static RiskPolicy judged(RiskOperation operation, Rules rules, List<ScoreBand> scoreBands,
            List<FailSafeBand> failSafe, Optional<Duration> holdValidity) {
        return new RiskPolicy(operation, RiskEngine.RISK_ENGINE_V1, EUR, rules.list(), scoreBands, List.of(), failSafe,
                Optional.of(RiskAssurance.MULTI_FACTOR), Optional.of(Money.ofMinorUnits(OVERRIDE_THRESHOLD_MINOR, EUR)),
                holdValidity);
    }

    /** ALLOW to 299, CHALLENGE to 599, {@code third} to 799, DENY from 800. */
    private static List<ScoreBand> fourBands(RiskDecision third) {
        return List.of(new ScoreBand(0, 299, RiskDecision.ALLOW), new ScoreBand(300, 599, RiskDecision.CHALLENGE),
                new ScoreBand(600, 799, third), new ScoreBand(800, 1000, RiskDecision.DENY));
    }

    /** C1: {@code below} under {@code thresholdMinor}, {@code atOrAbove} from it. */
    public static List<FailSafeBand> failSafe(long thresholdMinor, RiskDecision below, RiskDecision atOrAbove) {
        return List.of(new FailSafeBand(0, OptionalLong.of(thresholdMinor), below),
                new FailSafeBand(thresholdMinor, OptionalLong.empty(), atOrAbove));
    }

    /** C1: one decision at every band. */
    public static List<FailSafeBand> failSafe(RiskDecision everywhere) {
        return List.of(new FailSafeBand(0, OptionalLong.empty(), everywhere));
    }

    private static Map<String, ParameterValue> velocity(VelocityDimension dimension, WindowKind window,
            String threshold, ParameterValue value) {
        return Map.of("dimension", new ParameterValue.Dimension(dimension), "window", window(window), threshold, value);
    }

    public static ParameterValue minor(long value) {
        return new ParameterValue.MinorAmount(value);
    }

    public static ParameterValue count(long value) {
        return new ParameterValue.Count(value);
    }

    public static ParameterValue days(int value) {
        return new ParameterValue.Days(value);
    }

    public static ParameterValue hours(int value) {
        return new ParameterValue.Hours(value);
    }

    public static ParameterValue bps(int value) {
        return new ParameterValue.BasisPoints(value);
    }

    public static ParameterValue window(WindowKind value) {
        return new ParameterValue.Window(value);
    }

    public static ParameterValue assurance(RiskAssurance value) {
        return new ParameterValue.Assurance(value);
    }

    public static ParameterValue rating(RiskRating value) {
        return new ParameterValue.Rating(value);
    }

    public static ParameterValue corridors() {
        return new ParameterValue.Corridors(HIGH_RISK_CORRIDORS);
    }

    /** Rules in declared order, numbered as they are added. */
    public static final class Rules {
        private final List<RiskRule> rules = new ArrayList<>();

        public Rules weighted(RuleKind kind, Map<String, ParameterValue> parameters, int weight) {
            rules.add(RiskRule.weighted(rules.size() + 1, kind, RuleParameters.of(kind, parameters), weight));
            return this;
        }

        public Rules deciding(RuleKind kind, Map<String, ParameterValue> parameters, RiskDecision decision) {
            rules.add(RiskRule.deciding(rules.size() + 1, kind, RuleParameters.of(kind, parameters), decision));
            return this;
        }

        public List<RiskRule> list() {
            return List.copyOf(rules);
        }
    }
}
