package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What an engine concluded from one snapshot, its assessment and its pinned policy (`P10-TSK-013`; PHASE_10_PLAN.md
 * section 12.6) - pure, so equal inputs give an equal result ({@code INV-CRD-01}).
 *
 * <p>Refused at construction, so no engine can produce one: an adverse outcome without a reason ({@code INV-CRD-02}); an
 * approval with no amount, or one above the request; an approval below the request without the one code that explains
 * the cap, or a full approval with any; an amount on anything but an approval; a reason named twice; a rule list that is
 * not every rule, in ordinal order from 1.
 *
 * @param engineVersion the engine that produced it - recorded on every evaluation, and the one replay selects
 * @param requested the requested amount, in the product's currency
 * @param approved the approved amount - an approval's alone
 * @param reasons the ordered reason codes: an adverse outcome's triggered rules', first kept; an approval's binding cap
 * @param fallbackApplied whether the policy's unavailable fallback decided because an approval would have rested on a
 *     value the evaluation could not read ({@code INV-CRD-10})
 * @param rules every rule's result, in ordinal order
 */
public record EvaluationResult(
        int engineVersion,
        EvaluationOutcome outcome,
        Money requested,
        Optional<Money> approved,
        List<ReasonCode> reasons,
        boolean fallbackApplied,
        List<RuleResult> rules) {

    public EvaluationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(approved, "approved");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(rules, "rules");
        reasons = List.copyOf(reasons);
        rules = List.copyOf(rules);
        if (engineVersion < 1) {
            throw new IllegalArgumentException("an engine version counts from 1");
        }
        if (!requested.isPositive()) {
            throw new IllegalArgumentException("a requested amount is positive");
        }
        if (new HashSet<>(reasons).size() != reasons.size()) {
            throw new IllegalArgumentException("a reason is given once");
        }
        if (outcome.adverse()) {
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("an adverse outcome carries at least one reason (INV-CRD-02)");
            }
            if (reasons.stream().anyMatch(reason -> !reason.adverse())) {
                throw new IllegalArgumentException("an adverse outcome is explained by adverse reasons alone");
            }
            if (approved.isPresent()) {
                throw new IllegalArgumentException("only an approval approves an amount");
            }
        } else {
            Money amount = approved.orElseThrow(() -> new IllegalArgumentException("an approval approves an amount"));
            if (!amount.currency().equals(requested.currency()) || !amount.isPositive()
                    || amount.compareTo(requested) > 0) {
                throw new IllegalArgumentException("an approval approves a positive amount, at most the request");
            }
            boolean reduced = amount.compareTo(requested) < 0;
            if (reduced != (reasons.size() == 1)) {
                throw new IllegalArgumentException(
                        "an approval below the request carries its cap's one reason, and a full approval none");
            }
            if (fallbackApplied) {
                throw new IllegalArgumentException("the unavailable fallback never approves (INV-CRD-10)");
            }
        }
        if (fallbackApplied && !reasons.contains(ReasonCode.SOURCE_UNAVAILABLE)) {
            throw new IllegalArgumentException("the fallback decides with " + ReasonCode.SOURCE_UNAVAILABLE.code());
        }
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            RuleResult rule = rules.get(i);
            Objects.requireNonNull(rule, "rule");
            if (rule.ordinal() != i + 1 || !codes.add(rule.ruleCode())) {
                throw new IllegalArgumentException("every rule once, in ordinal order from 1");
            }
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("a policy has at least one rule");
        }
    }

    /** Whether a rule read a value the evaluation could not read. */
    public boolean anyUnassessed() {
        return rules.stream().anyMatch(rule -> rule.state() == RuleState.UNASSESSED);
    }

    /** No amount renders; the outcome and the codes do. */
    @Override
    public String toString() {
        return "EvaluationResult[engine " + engineVersion + ", " + outcome + ", " + reasons.stream().map(ReasonCode::code).toList()
                + "]";
    }

    /** What one rule did. */
    public enum RuleState {
        /** Its condition held: its effect counts. */
        TRIGGERED,
        /** Its condition did not hold. */
        NOT_TRIGGERED,
        /** It compared a value the snapshot holds as {@code ABSENT}, or a figure that could not be assessed. */
        UNASSESSED;

        public boolean triggered() {
            return this == TRIGGERED;
        }

        public boolean assessed() {
            return this != UNASSESSED;
        }

        /** The state the stored pair spells; a triggered rule that was not assessed is not one. */
        public static RuleState of(boolean triggered, boolean assessed) {
            if (triggered && !assessed) {
                throw new IllegalArgumentException("a rule that was not assessed did not trigger");
            }
            return triggered ? TRIGGERED : assessed ? NOT_TRIGGERED : UNASSESSED;
        }
    }

    /** One rule's result: its ordinal, its code, its effect, and what it did. */
    public record RuleResult(int ordinal, String ruleCode, PolicyEffect effect, RuleState state) {

        public RuleResult {
            Objects.requireNonNull(ruleCode, "ruleCode");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(state, "state");
            if (ordinal < 1) {
                throw new IllegalArgumentException("an ordinal counts from 1");
            }
        }
    }
}
