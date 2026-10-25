package com.finapp.risk;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One evaluator per {@link RuleKind} (`P13-TSK-003`; {@code PHASE_13_PLAN.md} section 12.2; ADR-0109 decision 2) -
 * the code a kind is: how its parameters are read against the sealed {@link EvaluationInputs}, and which token names
 * what it judged. A member is named exactly as its kind and the two enums are held equal as this class loads, so a
 * kind without an evaluator is a class that does not load, never a rule silently unevaluated.
 *
 * <p>Every judgement is a pure function of the rule's parameters and the inputs - integer comparisons, no clock,
 * no lookup, no floating point - and an {@link Reading absent} reading is {@link RuleOutcome#ABSENT}, never a fire.
 * Thresholds are read, never written into the token: the token names the reading only.
 */
enum RuleEvaluation {
    AMOUNT_BAND((rule, in) -> amount(in).map(a -> judged(a >= rule.parameters().minorAmount("threshold_minor"),
            "amount=" + a)).orElse(Judgement.ABSENT)),
    VELOCITY_COUNT((rule, in) -> velocity(in, rule).map(v -> judged(
            v.reading().count() >= rule.parameters().count("threshold_count"),
            "velocity:" + v.key() + ":count=" + v.reading().count())).orElse(Judgement.ABSENT)),
    VELOCITY_SUM((rule, in) -> velocity(in, rule).map(v -> judged(
            v.reading().sumMinor() >= rule.parameters().minorAmount("threshold_minor"),
            "velocity:" + v.key() + ":sum=" + v.reading().sumMinor())).orElse(Judgement.ABSENT)),
    NEW_PAYEE((rule, in) -> in.payeeFirstSeen().value().map(seen -> judged(
            seen.within(rule.parameters().days("lookback_days")),
            "payee:daysSinceFirstSeen=" + seen.canonical())).orElse(Judgement.ABSENT)),
    NEW_DEVICE((rule, in) -> in.deviceFirstSeen().value().map(seen -> judged(
            seen.within(rule.parameters().days("lookback_days")),
            "device:daysSinceFirstSeen=" + seen.canonical())).orElse(Judgement.ABSENT)),
    SESSION_ASSURANCE_BELOW((rule, in) -> in.sessionAssurance().value().map(assurance -> judged(
            !assurance.satisfies(rule.parameters().assurance("assurance")),
            "assurance=" + assurance.name())).orElse(Judgement.ABSENT)),
    RECENT_AUTH_FAILURES((rule, in) -> {
        WindowKind window = rule.parameters().window("window");
        Long failures = in.authFailures().get(window);
        return failures == null ? Judgement.ABSENT : judged(
                failures >= rule.parameters().count("threshold_count"),
                "authFailures:" + window + ":count=" + failures);
    }),
    RECENT_CREDENTIAL_CHANGE((rule, in) -> in.credentialChange().value().map(seen -> judged(
            seen.within(rule.parameters().hours("lookback_hours")),
            "credentialChange:hoursAgo=" + seen.canonical())).orElse(Judgement.ABSENT)),
    RECENT_RECOVERY((rule, in) -> in.recovery().value().map(seen -> judged(
            seen.within(rule.parameters().hours("lookback_hours")),
            "recovery:hoursAgo=" + seen.canonical())).orElse(Judgement.ABSENT)),
    ACCOUNT_AGE_BELOW((rule, in) -> in.accountAgeDays().value().map(age -> judged(
            age < rule.parameters().days("days"), "accountAgeDays=" + age)).orElse(Judgement.ABSENT)),
    KYC_NOT_APPROVED((rule, in) -> in.kycApproved().value().map(approved -> judged(
            !approved, "kycApproved=" + approved)).orElse(Judgement.ABSENT)),
    SCREENING_HIT((rule, in) -> in.screeningHit().value().map(hit -> judged(
            hit, "screeningHit=" + hit)).orElse(Judgement.ABSENT)),
    PROFILE_RATING((rule, in) -> in.profileRating().value().map(rating -> judged(
            rating.atLeast(rule.parameters().rating("rating")), "rating=" + rating.name())).orElse(Judgement.ABSENT)),
    MERCHANT_SUSPENDED((rule, in) -> in.merchantSuspended().value().map(suspended -> judged(
            suspended, "merchantSuspended=" + suspended)).orElse(Judgement.ABSENT)),
    CORRIDOR_HIGH_RISK((rule, in) -> in.corridor().value().map(corridor -> judged(
            rule.parameters().corridors("corridors").contains(corridor), "corridor=" + corridor)).orElse(Judgement.ABSENT)),
    DORMANT_THEN_ACTIVE((rule, in) -> {
        WindowKind window = rule.parameters().window("window");
        Optional<Integer> dormant = in.monitoring().dormantDays().value();
        VelocityReading sum = in.velocity().get(new VelocityKey(VelocityDimension.PARTY, window));
        if (dormant.isEmpty() || sum == null) {
            return Judgement.ABSENT;
        }
        return judged(dormant.get() >= rule.parameters().days("dormant_days")
                        && sum.sumMinor() >= rule.parameters().minorAmount("threshold_minor"),
                "dormantDays=" + dormant.get() + ",velocity:PARTY:" + window + ":sum=" + sum.sumMinor());
    }),
    RAPID_IN_OUT((rule, in) -> {
        WindowKind window = rule.parameters().window("window");
        Flow flow = in.monitoring().flows().get(window);
        return flow == null ? Judgement.ABSENT : judged(flow.outAtLeast(rule.parameters().basisPoints("ratio_bps")),
                "flow:" + window + ":in=" + flow.inMinor() + ",out=" + flow.outMinor());
    }),
    STRUCTURING((rule, in) -> {
        WindowKind window = rule.parameters().window("window");
        Amounts amounts = in.monitoring().amounts().get(window);
        if (amounts == null) {
            return Judgement.ABSENT;
        }
        long below = rule.parameters().minorAmount("below_minor");
        int count = amounts.countBelow(below);
        long sum = amounts.sumBelow(below);
        return judged(count >= rule.parameters().count("threshold_count")
                        && sum > rule.parameters().minorAmount("sum_minor"),
                "amounts:" + window + ":n=" + amounts.minor().size() + ",below=" + count + ",belowSum=" + sum);
    }),
    ROUND_AMOUNT((rule, in) -> amount(in).map(a -> judged(
            a > 0 && a % rule.parameters().minorAmount("modulus_minor") == 0, "amount=" + a)).orElse(Judgement.ABSENT)),
    REFUND_RATIO((rule, in) -> ratio(in.monitoring().refunds(), rule, "refunds")),
    CHARGEBACK_RATIO((rule, in) -> ratio(in.monitoring().chargebacks(), rule, "chargebacks")),
    BNPL_MULTI_APPLICATION((rule, in) -> {
        WindowKind window = rule.parameters().window("window");
        Long count = in.monitoring().bnplApplications().get(window);
        return count == null ? Judgement.ABSENT : judged(count >= rule.parameters().count("threshold_count"),
                "bnpl:" + window + ":count=" + count);
    });

    /** The token of an absent reading. */
    static final String ABSENT_JUDGED = "ABSENT";

    /** What a rule's evaluation came to, before it becomes a {@link RuleHit}. */
    record Judgement(RuleOutcome outcome, String judged) {
        static final Judgement ABSENT = new Judgement(RuleOutcome.ABSENT, ABSENT_JUDGED);

        Judgement {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(judged, "judged");
        }
    }

    @FunctionalInterface
    private interface Judge {
        Judgement judge(RiskRule rule, EvaluationInputs inputs);
    }

    static {
        RuleKind[] kinds = RuleKind.values();
        RuleEvaluation[] evaluations = values();
        if (kinds.length != evaluations.length) {
            throw new IllegalStateException("every rule kind has exactly one evaluator");
        }
        for (int i = 0; i < kinds.length; i++) {
            if (!kinds[i].name().equals(evaluations[i].name())) {
                throw new IllegalStateException(kinds[i] + ": a kind's evaluator is named for it, in the kind's order");
            }
        }
    }

    private final Judge judge;

    RuleEvaluation(Judge judge) {
        this.judge = Objects.requireNonNull(judge, "judge");
    }

    /** The evaluator of {@code kind}. */
    static RuleEvaluation of(RuleKind kind) {
        return valueOf(Objects.requireNonNull(kind, "kind").name());
    }

    /** Judges {@code rule} - which must be of this kind - over {@code inputs}. */
    Judgement judge(RiskRule rule, EvaluationInputs inputs) {
        if (rule.kind() != RuleKind.valueOf(name())) {
            throw new IllegalArgumentException("rule " + rule.ordinal() + " is a " + rule.kind() + ", not a " + name());
        }
        return judge.judge(rule, inputs);
    }

    // ------------------------------------------------------------------ the readings

    private static Judgement judged(boolean fired, String token) {
        return new Judgement(fired ? RuleOutcome.FIRED : RuleOutcome.NOT_FIRED, token);
    }

    private static Optional<Long> amount(EvaluationInputs in) {
        return in.amount().value().map(money -> money.minorUnits());
    }

    private record KeyedVelocity(VelocityKey key, VelocityReading reading) {}

    private static Optional<KeyedVelocity> velocity(EvaluationInputs in, RiskRule rule) {
        VelocityKey key = new VelocityKey(rule.parameters().dimension("dimension"), rule.parameters().window("window"));
        VelocityReading reading = in.velocity().get(key);
        return reading == null ? Optional.empty() : Optional.of(new KeyedVelocity(key, reading));
    }

    /**
     * A share's judgement (`P13-TSK-014`): at least {@code threshold_count} in the numerator <em>and</em> the share at or
     * above {@code ratio_bps}, both integer comparisons ({@link Ratio#atLeast}: cross-multiplied in {@code long}, never a
     * quotient - {@code INV-MON-01}). A window the reader sealed no ratio for - a zero denominator - is {@code ABSENT}.
     */
    private static Judgement ratio(Map<WindowKind, Ratio> ratios, RiskRule rule, String name) {
        WindowKind window = rule.parameters().window("window");
        Ratio ratio = ratios.get(window);
        return ratio == null ? Judgement.ABSENT : judged(
                ratio.numerator() >= rule.parameters().count("threshold_count")
                        && ratio.atLeast(rule.parameters().basisPoints("ratio_bps")),
                name + ":" + window + ":num=" + ratio.numerator() + ",den=" + ratio.denominator());
    }
}
