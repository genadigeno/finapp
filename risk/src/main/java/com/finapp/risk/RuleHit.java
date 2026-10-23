package com.finapp.risk;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One rule's record in a verdict (`P13-TSK-003`; {@code INV-RSK-04}; `-004`'s {@code risk_rule_hit}): the rule's
 * ordinal and kind, its {@link RuleOutcome outcome}, its contribution to the score (the rule's weight when it fired,
 * else 0), its hard decision (the rule's when it fired and it carries one), and {@link #judged} - the canonical token
 * naming what the rule read ({@code amount=150000}, {@code velocity:ACCOUNT:D1:count=12}, {@code ABSENT}), a reading
 * only, never a threshold: the policy's content is {@code CONFIDENTIAL} and the token reaches the operator's read.
 * Every rule of the version has a hit, in declared order, whether or not it fired (ADR-0109 decision 3: no
 * first-match).
 */
public record RuleHit(
        int ordinal,
        RuleKind kind,
        RuleOutcome outcome,
        int contribution,
        Optional<RiskDecision> hardDecision,
        String judged) {

    /** The shape of a judged reading's text - characters needing no escape in the canonical form. */
    public static final Pattern JUDGED_SHAPE = Pattern.compile("[A-Za-z0-9_.:,=-]{1,128}");

    public RuleHit {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(hardDecision, "hardDecision");
        Objects.requireNonNull(judged, "judged");
        if (ordinal < 1) {
            throw new IllegalArgumentException("a hit's ordinal is numbered from 1");
        }
        if (contribution < 0 || contribution > ScoreBand.CEILING) {
            throw new IllegalArgumentException("a contribution is 0 to " + ScoreBand.CEILING);
        }
        if (outcome != RuleOutcome.FIRED && (contribution != 0 || hardDecision.isPresent())) {
            throw new IllegalArgumentException("a rule that did not fire contributes nothing and decides nothing");
        }
        if (!JUDGED_SHAPE.matcher(judged).matches()) {
            throw new IllegalArgumentException("a judged reading's text is 1 to 128 characters of [A-Za-z0-9_.:,=-]");
        }
        if ((outcome == RuleOutcome.ABSENT) != judged.equals(RuleEvaluation.ABSENT_JUDGED)) {
            throw new IllegalArgumentException("an absent reading's token is ABSENT, and only an absent reading's");
        }
    }

    public boolean fired() {
        return outcome == RuleOutcome.FIRED;
    }

    /** The reason code this hit gives when it fired. */
    public ReasonCode reason() {
        return ReasonCode.of(kind);
    }

    @Override
    public String toString() {
        return "RuleHit[" + ordinal + " " + kind + " " + outcome + "]";
    }
}
