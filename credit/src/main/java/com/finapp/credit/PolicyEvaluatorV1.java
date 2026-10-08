package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Engine version 1 (`P10-TSK-013`; PHASE_10_PLAN.md section 12.6, ADR-0086 sections 1-2) - kept in the code for as long
 * as a decision made under it can be replayed, which is forever ({@code INV-CRD-01}).
 *
 * <p><strong>Each rule, in ordinal order,</strong> reads its subject - an attribute of the snapshot, or a figure of the
 * assessment ({@code SCORE}, {@code DISPOSABLE_INCOME}, {@code AFFORDABLE}, {@code EXPOSURE},
 * {@code EXPOSURE_HEADROOM}) - and is in one of three states:
 *
 * <ul>
 *   <li><em>presence</em> ({@code IS_ABSENT}, {@code IS_PRESENT}) is always decided: {@code ABSENT} is a value the
 *       policy reasons about (§14 row 2);
 *   <li><em>a comparison</em> of a present value triggers or not - integers and money ordered, booleans and single
 *       codes equal or not, codes in or out of a set;
 *   <li><em>a comparison</em> of an {@code ABSENT} attribute or of a figure the assessment could not compute is
 *       {@code UNASSESSED}: it does not trigger, and it is recorded as not having been read.
 * </ul>
 *
 * <p>An attribute the snapshot was never given is an evaluation error ({@link MissingAttributeException},
 * {@code INV-CRD-07}) - never a default.
 *
 * <p><strong>The outcome</strong> is the most severe effect triggered ({@code HARD_DECLINE > DECLINE > REFER >
 * APPROVE}). An adverse outcome gives the triggered rules' reason codes in ordinal order, deduplicated keeping the first.
 * An approval approves {@code min(requested, every triggered cap, the auto-approval ceiling)}; below the request it gives
 * the one code that binds - the first triggered cap rule's at that amount, else {@code CRD-AUTO-APPROVAL-CEILING}.
 *
 * <p><strong>Missing data never approves</strong> ({@code INV-CRD-10}). When every rule's verdict would approve but a
 * rule was {@code UNASSESSED} - a partial answer the policy did not decide explicitly - the approval would rest on data
 * nobody read, so the policy's declared unavailable fallback decides instead ({@code REFER} or {@code DECLINE}, with
 * {@code CRD-SOURCE-UNAVAILABLE}), recorded as such. An adverse outcome is never softened by it.
 */
public final class PolicyEvaluatorV1 implements PolicyEvaluator {

    public static final int VERSION = 1;

    @Override
    public int engineVersion() {
        return VERSION;
    }

    @Override
    public EvaluationResult evaluate(SnapshotContent snapshot, CreditAssessment assessment, CreditPolicy policy) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(policy, "policy");
        belongTogether(snapshot, assessment, policy);
        List<EvaluationResult.RuleResult> results = new ArrayList<>();
        EvaluationOutcome outcome = EvaluationOutcome.APPROVE;
        List<CreditPolicy.PolicyRule> triggered = new ArrayList<>();
        int ordinal = 1;
        for (CreditPolicy.PolicyRule rule : policy.rules()) {
            EvaluationResult.RuleState state = state(rule, read(rule.subject(), snapshot, assessment));
            results.add(new EvaluationResult.RuleResult(ordinal++, rule.ruleCode(), rule.effect(), state));
            if (state.triggered()) {
                triggered.add(rule);
                outcome = outcome.severest(EvaluationOutcome.of(rule.effect()));
            }
        }
        Money requested = snapshot.requestedAmount();
        boolean unassessed = results.stream().anyMatch(result -> !result.state().assessed());
        if (outcome == EvaluationOutcome.APPROVE && unassessed) {
            List<ReasonCode> reasons = firstOfEach(triggered);
            if (!reasons.contains(ReasonCode.SOURCE_UNAVAILABLE)) {
                reasons.add(ReasonCode.SOURCE_UNAVAILABLE);
            }
            return new EvaluationResult(VERSION, EvaluationOutcome.of(policy.unavailableFallback().effect()), requested,
                    Optional.empty(), reasons, true, results);
        }
        if (outcome.adverse()) {
            return new EvaluationResult(VERSION, outcome, requested, Optional.empty(), firstOfEach(triggered), false,
                    results);
        }
        Money approved = requested;
        for (CreditPolicy.PolicyRule rule : triggered) {
            approved = lesser(approved, rule.cap().orElseThrow());
        }
        approved = lesser(approved, policy.autoApprovalCeiling());
        List<ReasonCode> reasons = new ArrayList<>();
        if (approved.compareTo(requested) < 0) {
            Money binding = approved;
            reasons.add(triggered.stream()
                    .filter(rule -> rule.cap().orElseThrow().compareTo(binding) == 0)
                    .map(CreditPolicy.PolicyRule::reason)
                    .findFirst()
                    .orElse(ReasonCode.AUTO_APPROVAL_CEILING));
        }
        return new EvaluationResult(VERSION, EvaluationOutcome.APPROVE, requested, Optional.of(approved), reasons, false,
                results);
    }

    private static void belongTogether(SnapshotContent snapshot, CreditAssessment assessment, CreditPolicy policy) {
        if (policy.product() != snapshot.product()) {
            throw new IllegalArgumentException("the policy is " + policy.product() + "'s, the request " + snapshot.product()
                    + "'s");
        }
        if (!assessment.versions().equals(snapshot.versions())
                || !assessment.decisionRequest().equals(snapshot.decisionRequest())
                || !assessment.currency().equals(snapshot.product().currency())) {
            throw new IllegalArgumentException("the assessment is not this snapshot's");
        }
        if (snapshot.versions().engineVersion() != VERSION) {
            throw new IllegalArgumentException("the snapshot pins engine version " + snapshot.versions().engineVersion()
                    + ", not " + VERSION);
        }
    }

    /** The subject's value - {@code ABSENT} for a figure the assessment could not compute. */
    private static AttributeValue read(CreditPolicy.Subject subject, SnapshotContent snapshot, CreditAssessment assessment) {
        return switch (subject) {
            case CreditPolicy.Subject.Attribute attribute -> snapshot.attribute(attribute.code()).value();
            case CreditPolicy.Subject.Figure figure -> switch (figure.figure()) {
                case SCORE -> new AttributeValue.IntegerValue(assessment.score());
                case DISPOSABLE_INCOME -> assessment.affordability()
                        instanceof AffordabilityAssessment.Assessment.Assessed assessed
                        ? new AttributeValue.MoneyValue(assessed.disposable())
                        : new AttributeValue.Absent();
                case AFFORDABLE -> assessment.affordability()
                        instanceof AffordabilityAssessment.Assessment.Assessed assessed
                        ? new AttributeValue.BooleanValue(assessed.affordable())
                        : new AttributeValue.Absent();
                case EXPOSURE -> assessment.exposure() instanceof ExposureAssessment.Assessment.Assessed assessed
                        ? new AttributeValue.MoneyValue(assessed.exposure())
                        : new AttributeValue.Absent();
                case EXPOSURE_HEADROOM -> assessment.exposure() instanceof ExposureAssessment.Assessment.Assessed assessed
                        ? new AttributeValue.MoneyValue(assessed.headroom())
                        : new AttributeValue.Absent();
            };
        };
    }

    private static EvaluationResult.RuleState state(CreditPolicy.PolicyRule rule, AttributeValue value) {
        boolean absent = value instanceof AttributeValue.Absent;
        if (rule.operator().presence()) {
            boolean holds = rule.operator() == PolicyOperator.IS_ABSENT ? absent : !absent;
            return holds ? EvaluationResult.RuleState.TRIGGERED : EvaluationResult.RuleState.NOT_TRIGGERED;
        }
        if (absent) {
            return EvaluationResult.RuleState.UNASSESSED;
        }
        return holds(rule.operator(), value, rule.operand())
                ? EvaluationResult.RuleState.TRIGGERED
                : EvaluationResult.RuleState.NOT_TRIGGERED;
    }

    /** A comparison of a present value; the policy's construction has already judged the types to agree. */
    private static boolean holds(PolicyOperator operator, AttributeValue value, CreditPolicy.Operand operand) {
        return switch (value) {
            case AttributeValue.IntegerValue integer ->
                    ordered(operator, Long.compare(integer.value(), ((CreditPolicy.Operand.IntegerOperand) operand).value()));
            case AttributeValue.MoneyValue money ->
                    ordered(operator, money.value().compareTo(((CreditPolicy.Operand.MoneyOperand) operand).value()));
            case AttributeValue.BooleanValue bool -> {
                boolean equal = bool.value() == ((CreditPolicy.Operand.BooleanOperand) operand).value();
                yield switch (operator) {
                    case EQ -> equal;
                    case NE -> !equal;
                    default -> throw new IllegalStateException(operator + " does not compare booleans");
                };
            }
            case AttributeValue.CodeValue code -> {
                boolean member = ((CreditPolicy.Operand.CodesOperand) operand).codes().contains(code.value());
                yield switch (operator) {
                    case EQ, IN -> member;
                    case NE, NOT_IN -> !member;
                    default -> throw new IllegalStateException(operator + " does not compare codes");
                };
            }
            case AttributeValue.Absent absent -> throw new IllegalStateException("an absent value is not compared");
        };
    }

    private static boolean ordered(PolicyOperator operator, int comparison) {
        return switch (operator) {
            case LT -> comparison < 0;
            case LE -> comparison <= 0;
            case GT -> comparison > 0;
            case GE -> comparison >= 0;
            case EQ -> comparison == 0;
            case NE -> comparison != 0;
            default -> throw new IllegalStateException(operator + " does not compare numbers");
        };
    }

    /** The triggered rules' reason codes in ordinal order, each kept where it first appears. */
    private static List<ReasonCode> firstOfEach(List<CreditPolicy.PolicyRule> triggered) {
        List<ReasonCode> reasons = new ArrayList<>();
        for (CreditPolicy.PolicyRule rule : triggered) {
            if (!reasons.contains(rule.reason())) {
                reasons.add(rule.reason());
            }
        }
        return reasons;
    }

    private static Money lesser(Money a, Money b) {
        return b.compareTo(a) < 0 ? b : a;
    }
}
