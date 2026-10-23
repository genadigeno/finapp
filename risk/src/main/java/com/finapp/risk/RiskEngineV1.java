package com.finapp.risk;

import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The rule engine, version 1 (`P13-TSK-003`; {@code PHASE_13_PLAN.md} sections 12.2-12.3; ADR-0108 decisions 3-4,
 * ADR-0109 decision 3; {@code INV-RSK-01}, {@code INV-RSK-04}, {@code INV-CRD-04}, {@code INV-MON-01}) - a pure
 * function from a policy version's content and the sealed inputs to a {@link Verdict}, and nothing else: no clock,
 * no row, no configuration, no randomness, no floating point. The same inputs give the same bytes in any JVM
 * ({@code RiskEngineDigestTest}), which is what replay rests on. Kept, unedited, for as long as an assessment pins
 * {@link RiskEngine#RISK_ENGINE_V1}; a semantic change is a new member and a new class (the {@code INV-LND-09}
 * discipline), never an edit here.
 *
 * <p><strong>The evaluation.</strong> Every rule of the version, in its declared order, is judged by its kind's
 * {@link RuleEvaluation} - no first-match: every hit is recorded, fired or not, an absent reading saying so. The
 * score is the sum of the fired rules' weights clamped to [0, 1000]. For a judged operation the decision is the
 * <em>strictest</em> ({@link RiskDecision#strictest}: {@code DENY > HOLD > REVIEW > CHALLENGE > ALLOW}) of the score
 * band's decision and every fired rule's hard decision - so a band can raise a hard decision and never soften it
 * ("a hard rule's decision beats a score band's"), and with nothing fired the band covering 0 decides. For the
 * {@code MONITORING} version the rating bands map the score to the party's rating and there is no decision. The
 * reasons are the fired rules' codes in evaluation order.
 *
 * <p><strong>Refusals, never guesses.</strong> Inputs for another operation, a policy for another engine, an amount
 * in another currency than the policy's, a rule that throws, a composed decision the operation does not admit - each
 * is {@link EvaluationFailed}, never a partial verdict; the caller records {@link #failSafe the fail-safe}.
 */
public final class RiskEngineV1 {

    /** The version this class implements - what every verdict and every assessment pins. */
    public static final RiskEngine VERSION = RiskEngine.RISK_ENGINE_V1;

    private RiskEngineV1() {}

    /**
     * Evaluates {@code policy}'s rules over {@code inputs}.
     *
     * @throws EvaluationFailed when no verdict can be produced (its {@link EvaluationFailed#failure cause} says why)
     */
    public static Verdict evaluate(RiskPolicy policy, EvaluationInputs inputs) {
        return evaluate(policy, inputs, (rule, read) -> RuleEvaluation.of(rule.kind()).judge(rule, read));
    }

    /**
     * How one rule is judged - {@link RuleEvaluation}'s, always, in production. A seam for the engine's own suite
     * (`P13-TSK-004`): once the policy door refused a zero {@code ROUND_AMOUNT} modulus, no rule a valid policy carries
     * can throw on inputs {@link EvaluationInputs} admits, and the wrapping of a throwing rule into
     * {@link EvaluationFailed} - the engine's defence in depth - would otherwise go untested.
     */
    @FunctionalInterface
    interface RuleJudge {
        RuleEvaluation.Judgement judge(RiskRule rule, EvaluationInputs inputs);
    }

    /** {@link #evaluate(RiskPolicy, EvaluationInputs)} with every rule judged by {@code judge}. */
    static Verdict evaluate(RiskPolicy policy, EvaluationInputs inputs, RuleJudge judge) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(judge, "judge");
        if (policy.engine() != VERSION) {
            throw new EvaluationFailed(EvaluationFailed.Cause.WRONG_ENGINE,
                    "the policy is written for " + policy.engine() + ", this is " + VERSION);
        }
        if (inputs.operation() != policy.operation()) {
            throw new EvaluationFailed(EvaluationFailed.Cause.WRONG_OPERATION,
                    "inputs for " + inputs.operation() + " against a " + policy.operation() + " policy");
        }
        Optional<Money> amount = inputs.amount().value();
        if (amount.isPresent() && !amount.get().currency().equals(policy.currency())) {
            throw new EvaluationFailed(EvaluationFailed.Cause.CURRENCY_MISMATCH,
                    "the judged amount's currency is not the policy's " + policy.currency().code());
        }
        List<RuleHit> hits = new ArrayList<>(policy.rules().size());
        for (RiskRule rule : policy.rules()) {
            RuleEvaluation.Judgement judgement;
            try {
                judgement = judge.judge(rule, inputs);
            } catch (RuntimeException thrown) {
                throw new EvaluationFailed(rule, thrown);
            }
            boolean fired = judgement.outcome() == RuleOutcome.FIRED;
            hits.add(new RuleHit(rule.ordinal(), rule.kind(), judgement.outcome(),
                    fired && rule.weight().isPresent() ? rule.weight().getAsInt() : 0,
                    fired ? rule.hardDecision() : Optional.empty(), judgement.judged()));
        }
        Score score = Score.of(hits);
        List<ReasonCode> reasons = new ArrayList<>();
        for (RuleHit hit : hits) {
            if (hit.fired()) {
                reasons.add(hit.reason());
            }
        }
        if (policy.isMonitoring()) {
            return new Verdict(policy.operation(), VERSION, hits, score, Optional.empty(),
                    Optional.of(rating(policy, score)), reasons, false);
        }
        RiskDecision decision = band(policy, score);
        for (RuleHit hit : hits) {
            if (hit.hardDecision().isPresent()) {
                decision = RiskDecision.strictest(decision, hit.hardDecision().get());
            }
        }
        if (!decision.isAdmittedBy(policy.operation())) {
            throw new EvaluationFailed(EvaluationFailed.Cause.DECISION_NOT_ADMITTED,
                    policy.operation() + " does not admit " + decision);
        }
        return new Verdict(policy.operation(), VERSION, hits, score, Optional.of(decision), Optional.empty(),
                reasons, false);
    }

    /**
     * The recorded fail-safe with a policy: its fail-safe band's decision for {@code judged} under
     * {@link ReasonCode#RISK_UNAVAILABLE} (ADR-0108 decision 4, owner decision C1). The caller's answer when the
     * evaluation is unavailable - the pin's read failing, {@link EvaluationFailed}, the statement timeout.
     *
     * @throws IllegalArgumentException for the sweep's version, which decides no movement
     * @throws EvaluationFailed when the amount is not in the policy's currency - the bands cannot place it
     */
    public static Verdict failSafe(RiskPolicy policy, Money judged) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(judged, "judged");
        if (policy.isMonitoring()) {
            throw new IllegalArgumentException("the sweep has no fail-safe: it decides no movement");
        }
        if (!judged.currency().equals(policy.currency())) {
            throw new EvaluationFailed(EvaluationFailed.Cause.CURRENCY_MISMATCH,
                    "the judged amount's currency is not the policy's " + policy.currency().code());
        }
        return failSafe(policy.operation(), policy.failSafeFor(judged.minorUnits()), ReasonCode.RISK_UNAVAILABLE);
    }

    /**
     * The compiled fail-safe with no policy: {@code ALLOW} under {@link ReasonCode#NO_POLICY_ACTIVE} for every judged
     * operation (ADR-0108 decision 4: until the first activation the platform has not chosen to judge).
     */
    public static Verdict failSafe(RiskOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (!operation.judgesAMovement()) {
            throw new IllegalArgumentException(operation + " decides no movement: it has no fail-safe");
        }
        return failSafe(operation, RiskDecision.ALLOW, ReasonCode.NO_POLICY_ACTIVE);
    }

    /**
     * The recorded fail-safe when the pin's own read failed (`P13-TSK-004`; ADR-0108 decision 4: "the pin's read failing"
     * is unavailable): no policy is in hand, so no band can be read, and the compiled {@code ALLOW} answers - under
     * {@link ReasonCode#RISK_UNAVAILABLE}, never {@code NO_POLICY_ACTIVE}, because whether a version is active is exactly
     * what could not be read. A compiled {@code DENY} here would be policy in code (ADR-0108's refused alternative).
     */
    public static Verdict failSafeUnpinned(RiskOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (!operation.judgesAMovement()) {
            throw new IllegalArgumentException(operation + " decides no movement: it has no fail-safe");
        }
        return failSafe(operation, RiskDecision.ALLOW, ReasonCode.RISK_UNAVAILABLE);
    }

    /**
     * The recorded fail-safe for an amount the policy's bands cannot place - another currency than the version's
     * (`P13-TSK-004`): the open-ended last band answers, under {@link ReasonCode#RISK_UNAVAILABLE}. An amount that cannot
     * be compared is not known to be below any threshold, so it is judged as the largest; in every C1 band set that is
     * {@code DENY}. Multi-currency judging needs a policy per currency or a pinned conversion - the owner's, recorded.
     */
    public static Verdict failSafeUnplaced(RiskPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy.isMonitoring()) {
            throw new IllegalArgumentException("the sweep has no fail-safe: it decides no movement");
        }
        List<FailSafeBand> bands = policy.failSafeBands();
        return failSafe(policy.operation(), bands.get(bands.size() - 1).decision(), ReasonCode.RISK_UNAVAILABLE);
    }

    private static Verdict failSafe(RiskOperation operation, RiskDecision decision, ReasonCode reason) {
        return new Verdict(operation, VERSION, List.of(), Score.ZERO, Optional.of(decision), Optional.empty(),
                List.of(reason), true);
    }

    private static RiskDecision band(RiskPolicy policy, Score score) {
        for (ScoreBand band : policy.scoreBands()) {
            if (band.covers(score.value())) {
                return band.decision();
            }
        }
        throw new IllegalStateException("the score bands cover 0 to " + ScoreBand.CEILING);
    }

    private static RiskRating rating(RiskPolicy policy, Score score) {
        for (RatingBand band : policy.ratingBands()) {
            if (band.covers(score.value())) {
                return band.rating();
            }
        }
        throw new IllegalStateException("the rating bands cover 0 to " + ScoreBand.CEILING);
    }
}
