package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A risk policy version's content - immutable, judged whole at construction (`P13-TSK-002`; ADR-0109 decisions 1, 2;
 * {@code PHASE_13_PLAN.md} sections 12.2, 12.3 and owner decisions C1, C7; {@code INV-RSK-05}): the operation it is
 * written for and the engine version that evaluates it; its {@link RiskRule}s in declared order; the score bands
 * mapping [0, 1000] to the decisions the operation admits (a judged operation) or the rating bands mapping the party's
 * signal score to a rating (the {@code MONITORING} operation); the fail-safe bands mapping amount bands to {@code ALLOW}
 * or {@code DENY} when the evaluation is unavailable (C1; judged operations); the challenge assurance (the one a
 * {@code CHALLENGE} is satisfied by); the override threshold (C7); and the hold validity (exactly where {@code HOLD} is
 * admitted - the withdrawal - default 72 h). Every amount in one currency.
 *
 * <p>Everything here is refused before anything is locked or written: a rule kind the operation does not declare, a
 * parameter outside its kind's schema, a band mapping to a decision the operation does not admit, a hard decision it
 * does not admit, bands that do not cover their range, a knob the operation has no use for or lacks - each
 * {@link PolicyInvalid} ({@code 422 risk.PolicyInvalid}). {@code CREDIT_SIGNAL} carries no policy: no rule kind is
 * written for it and it admits no decision - it answers the profile's rating, which the monitoring version's rating
 * bands set.
 */
public record RiskPolicy(
        RiskOperation operation,
        RiskEngine engine,
        CurrencyCode currency,
        List<RiskRule> rules,
        List<ScoreBand> scoreBands,
        List<RatingBand> ratingBands,
        List<FailSafeBand> failSafeBands,
        Optional<RiskAssurance> challengeAssurance,
        Optional<Money> overrideThreshold,
        Optional<Duration> holdValidity) {

    /** The hold validity a withdrawal version takes when the proposal names none (`PHASE_13_PLAN.md` section 5). */
    public static final Duration DEFAULT_HOLD_VALIDITY = Duration.ofHours(72);

    /** A hold validity's bounds, in whole hours: one hour to thirty days. */
    public static final int MIN_HOLD_HOURS = 1;
    public static final int MAX_HOLD_HOURS = 720;

    /** The reference currency every version is written in (C1's bands are EUR). */
    public static final CurrencyCode REFERENCE_CURRENCY = CurrencyCode.of("EUR");

    /**
     * Whether a version of {@code operation} with these rules and score bands names a hold validity (`P13-TSK-010`):
     * a withdrawal's always (its native hold-then-dispatch, C2); another operation's exactly when it admits
     * {@code HOLD} and one of its score bands or rules' hard decisions can decide it (a fail-safe band never holds).
     * The one rule the version's own validation and the policy desk's default both read.
     */
    public static boolean namesHoldValidity(RiskOperation operation, List<RiskRule> rules, List<ScoreBand> scoreBands) {
        Objects.requireNonNull(operation, "operation");
        if (operation == RiskOperation.WITHDRAWAL) {
            return true;
        }
        if (!RiskDecision.HOLD.isAdmittedBy(operation)) {
            return false;
        }
        return scoreBands.stream().anyMatch(band -> band.decision() == RiskDecision.HOLD)
                || rules.stream().anyMatch(rule -> rule.hardDecision().filter(RiskDecision.HOLD::equals).isPresent());
    }

    /** The version is not complete, a parameter is outside its kind's schema, or a band maps to an unadmitted decision. */
    public static final class PolicyInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public PolicyInvalid(String detail) {
            super(detail);
        }
    }

    public RiskPolicy {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(scoreBands, "scoreBands");
        Objects.requireNonNull(ratingBands, "ratingBands");
        Objects.requireNonNull(failSafeBands, "failSafeBands");
        Objects.requireNonNull(challengeAssurance, "challengeAssurance");
        Objects.requireNonNull(overrideThreshold, "overrideThreshold");
        Objects.requireNonNull(holdValidity, "holdValidity");

        if (operation == RiskOperation.CREDIT_SIGNAL) {
            throw new PolicyInvalid("the credit signal carries no policy: it answers the profile's rating, which the"
                    + " MONITORING version's rating bands set");
        }
        if (engine.kind() != RiskEngine.Kind.RISK) {
            throw new PolicyInvalid("a policy version is written for a risk engine version");
        }
        if (!currency.equals(REFERENCE_CURRENCY)) {
            throw new PolicyInvalid("a policy version's amounts are in " + REFERENCE_CURRENCY.code());
        }

        Set<RiskDecision> admitted = RiskDecision.admittedBy(operation);
        rules = rulesInOrder(operation, admitted, rules);

        boolean monitoring = operation == RiskOperation.MONITORING;
        if (monitoring) {
            ratingBands = RatingBand.contiguous(ratingBands);
            if (!scoreBands.isEmpty() || !failSafeBands.isEmpty()) {
                throw new PolicyInvalid("the monitoring version carries rating bands, not score or fail-safe bands: the"
                        + " sweep decides no movement");
            }
            if (challengeAssurance.isPresent() || overrideThreshold.isPresent() || holdValidity.isPresent()) {
                throw new PolicyInvalid("the monitoring version carries no challenge assurance, override threshold or"
                        + " hold validity: the sweep decides no movement");
            }
            scoreBands = List.of();
            failSafeBands = List.of();
        } else {
            if (!ratingBands.isEmpty()) {
                throw new PolicyInvalid("rating bands belong to the monitoring version alone");
            }
            ratingBands = List.of();
            scoreBands = ScoreBand.contiguous(scoreBands);
            for (ScoreBand band : scoreBands) {
                if (!admitted.contains(band.decision())) {
                    throw new PolicyInvalid("a score band maps to " + band.decision() + ", which " + operation
                            + " does not admit");
                }
            }
            failSafeBands = FailSafeBand.contiguous(failSafeBands);
            if (challengeAssurance.isEmpty()) {
                throw new PolicyInvalid("a judged operation's version names its challenge assurance");
            }
            if (challengeAssurance.get() != RiskAssurance.MULTI_FACTOR) {
                throw new PolicyInvalid("a CHALLENGE is satisfied by MULTI_FACTOR alone: " + challengeAssurance.get()
                        + " would make it no challenge");
            }
            if (overrideThreshold.isEmpty()) {
                throw new PolicyInvalid("a judged operation's version names its override threshold (C7)");
            }
            Money threshold = overrideThreshold.get();
            if (!threshold.currency().equals(currency) || threshold.minorUnits() < 0) {
                throw new PolicyInvalid("the override threshold is a non-negative amount in the version's currency");
            }
            // The hold validity is named exactly where it can be used: a withdrawal's version always (its native
            // hold-then-dispatch, C2), and - since `-010`'s second slice - a card payment's version exactly when one of
            // its score bands or rules can decide HOLD (a fail-safe band never holds; a card version that never holds
            // names none, so every version written before HOLD was admitted stays valid as written).
            boolean admitsHold = admitted.contains(RiskDecision.HOLD);
            boolean namesHold = namesHoldValidity(operation, rules, scoreBands);
            if (namesHold != holdValidity.isPresent()) {
                throw new PolicyInvalid(namesHold
                        ? operation + " can decide HOLD: its version names the hold validity"
                        : operation + (admitsHold
                                ? " decides no HOLD in this version: a hold validity has no meaning for it"
                                : " admits no HOLD: a hold validity has no meaning for it"));
            }
            if (holdValidity.isPresent()) {
                Duration validity = holdValidity.get();
                if (validity.toHours() < MIN_HOLD_HOURS || validity.toHours() > MAX_HOLD_HOURS
                        || !validity.equals(Duration.ofHours(validity.toHours()))) {
                    throw new PolicyInvalid("the hold validity is a whole number of hours, " + MIN_HOLD_HOURS + " to "
                            + MAX_HOLD_HOURS);
                }
            }
        }
    }

    private static List<RiskRule> rulesInOrder(RiskOperation operation, Set<RiskDecision> admitted, List<RiskRule> rules) {
        if (rules.isEmpty()) {
            throw new PolicyInvalid("a policy version carries at least one rule");
        }
        Set<Integer> ordinals = new HashSet<>();
        int expected = 1;
        for (RiskRule rule : rules) {
            if (rule.ordinal() != expected) {
                throw new PolicyInvalid("the rules are numbered 1.." + rules.size() + " in their declared order: rule "
                        + rule.ordinal() + " where " + expected + " was expected");
            }
            if (!ordinals.add(rule.ordinal())) {
                throw new PolicyInvalid("a rule's ordinal is held once");
            }
            if (!rule.kind().appliesTo(operation)) {
                throw new PolicyInvalid(rule.kind() + " is not written for " + operation);
            }
            if (rule.hardDecision().isPresent() && !admitted.contains(rule.hardDecision().get())) {
                throw new PolicyInvalid("rule " + rule.ordinal() + " (" + rule.kind() + ") decides "
                        + rule.hardDecision().get() + ", which " + operation + " does not admit");
            }
            // P13-TSK-004 (owed by P13-TSK-003's gate): a ROUND_AMOUNT modulus of 0 is a non-negative amount, so the
            // parameter's type admits it - and the engine's "a multiple of 0" throws. Refused here, at the door and on
            // every read of a stored version, so no policy that cannot be evaluated is ever stored (ADR-0109 decision 2).
            if (rule.kind() == RuleKind.ROUND_AMOUNT && rule.parameters().minorAmount("modulus_minor") < 1) {
                throw new PolicyInvalid("rule " + rule.ordinal() + " (ROUND_AMOUNT) names a modulus of at least one"
                        + " minor unit");
            }
            expected++;
        }
        return List.copyOf(rules);
    }

    /** The decisions this version's operation admits. */
    public Set<RiskDecision> admittedDecisions() {
        return RiskDecision.admittedBy(operation);
    }

    /** True for the sweep's policy, which decides no movement. */
    public boolean isMonitoring() {
        return operation == RiskOperation.MONITORING;
    }

    /** The fail-safe decision for {@code amountMinor} (C1) - the band it falls in; the last band is open-ended. */
    public RiskDecision failSafeFor(long amountMinor) {
        for (FailSafeBand band : failSafeBands) {
            if (band.covers(amountMinor)) {
                return band.decision();
            }
        }
        throw new IllegalStateException("the fail-safe bands cover every non-negative amount");
    }

    @Override
    public String toString() {
        // Never a threshold or a weight: the content is CONFIDENTIAL and a toString reaches logs.
        return "RiskPolicy[" + operation + ", " + engine + ", " + rules.size() + " rules]";
    }
}
