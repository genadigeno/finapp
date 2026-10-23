package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One rule of a risk policy version (`P13-TSK-002`; ADR-0109 decisions 1, 2): a closed {@link RuleKind} with its typed
 * {@link RuleParameters}, a weight (its score contribution when it fires, 1 to the score's ceiling) and/or a hard
 * decision, at its ordinal in the version's declared order - the engine (`-003`) evaluates every rule in that order,
 * sums the weights of those that fire and lets a hard decision beat a score band's. Whether the kind is written for the
 * version's operation and the hard decision admitted by it is {@link RiskPolicy}'s judgement.
 */
public record RiskRule(
        int ordinal, RuleKind kind, RuleParameters parameters, OptionalInt weight, Optional<RiskDecision> hardDecision) {

    public RiskRule {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(weight, "weight");
        Objects.requireNonNull(hardDecision, "hardDecision");
        if (ordinal < 1) {
            throw new RiskPolicy.PolicyInvalid("a rule's ordinal is numbered from 1");
        }
        if (parameters.kind() != kind) {
            throw new RiskPolicy.PolicyInvalid("a rule's parameters are its own kind's");
        }
        if (weight.isPresent() && (weight.getAsInt() < 1 || weight.getAsInt() > ScoreBand.CEILING)) {
            throw new RiskPolicy.PolicyInvalid("a rule's weight is 1 to " + ScoreBand.CEILING);
        }
        if (weight.isEmpty() && hardDecision.isEmpty()) {
            throw new RiskPolicy.PolicyInvalid("a rule carries a weight and/or a hard decision");
        }
    }

    /** A weighted rule with no hard decision. */
    public static RiskRule weighted(int ordinal, RuleKind kind, RuleParameters parameters, int weight) {
        return new RiskRule(ordinal, kind, parameters, OptionalInt.of(weight), Optional.empty());
    }

    /** A rule deciding hard, with no weight. */
    public static RiskRule deciding(int ordinal, RuleKind kind, RuleParameters parameters, RiskDecision decision) {
        return new RiskRule(ordinal, kind, parameters, OptionalInt.empty(), Optional.of(decision));
    }

    @Override
    public String toString() {
        return "RiskRule[" + ordinal + " " + kind + "]";
    }
}
