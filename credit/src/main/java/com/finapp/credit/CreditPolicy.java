package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One product's credit policy as data (`P10-TSK-012`; ADR-0086 sections 1 and 6, PHASE_10_PLAN.md section 12.6;
 * {@code INV-CRD-05}, {@code INV-CRD-10}, {@code INV-CRD-02}): its parameters and its ordered rules over a closed
 * vocabulary.
 *
 * <p><strong>Judged whole at construction</strong> ({@link PolicyIncomplete}), so a policy that exists is one an
 * evaluator can run and an approver can trust - every refusal is at proposal, never at decision time:
 * <ul>
 *   <li>every amount in the product's currency, every rate in basis points - no floating point;</li>
 *   <li>every rule well typed ({@link PolicyRule}): its operator and operand agree with the type of the attribute or
 *       figure it reads, and its reason code is a catalogued ADVERSE one - every effect judges against the
 *       applicant ({@link PolicyEffect});</li>
 *   <li><strong>a fallback for every source kind the policy reads</strong> ({@code INV-CRD-10}): the kinds it reads
 *       are exactly those it declares a maximum data age for, and for each a rule carrying the declared
 *       {@link #unavailableFallback} effect and {@code CRD-SOURCE-UNAVAILABLE} must be GUARANTEED to trigger whenever
 *       that kind is unavailable ({@link PolicyRule#triggersWhenUnavailable}) - so every approving path requires the
 *       source's attributes present, and an approval can never arise from missing data.</li>
 * </ul>
 */
public record CreditPolicy(
        CreditProduct product,
        int assessmentRateBps,
        Money minimumDisposable,
        int minimumPaymentRatioBps,
        Money maximumExposure,
        Map<CreditSourceKind, Duration> maximumDataAge,
        UnavailableFallback unavailableFallback,
        Money autoApprovalCeiling,
        List<PolicyRule> rules) {

    /** At most this many rules per version. */
    public static final int MAXIMUM_RULES = 100;

    /** A source's maximum data age is at most a year, in whole seconds. */
    public static final Duration MAXIMUM_DATA_AGE = Duration.ofDays(365);

    /** Basis points: 10,000 is 100%. */
    public static final int BASIS_POINTS = 10_000;

    public CreditPolicy {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(minimumDisposable, "minimumDisposable");
        Objects.requireNonNull(maximumExposure, "maximumExposure");
        Objects.requireNonNull(maximumDataAge, "maximumDataAge");
        Objects.requireNonNull(unavailableFallback, "unavailableFallback");
        Objects.requireNonNull(autoApprovalCeiling, "autoApprovalCeiling");
        Objects.requireNonNull(rules, "rules");
        if (assessmentRateBps < 0 || assessmentRateBps > BASIS_POINTS) {
            throw new PolicyIncomplete("the assessment rate is 0 to " + BASIS_POINTS + " basis points");
        }
        if (minimumPaymentRatioBps < 1 || minimumPaymentRatioBps > BASIS_POINTS) {
            throw new PolicyIncomplete("the minimum payment ratio is 1 to " + BASIS_POINTS + " basis points");
        }
        inProductCurrency(product, "the minimum disposable income", minimumDisposable);
        inProductCurrency(product, "the maximum exposure", maximumExposure);
        inProductCurrency(product, "the auto-approval ceiling", autoApprovalCeiling);
        if (minimumDisposable.isNegative()) {
            throw new PolicyIncomplete("the minimum disposable income is not negative");
        }
        if (!maximumExposure.isPositive() || !autoApprovalCeiling.isPositive()) {
            throw new PolicyIncomplete("the maximum exposure and the auto-approval ceiling are positive");
        }
        // The Phase 10 to 11 transition: an approval ceiling below the product's published minimum (ADR-0084 section 6)
        // would cap an approval to an amount the product cannot offer - refused at construction, so no such version is
        // ever proposed (none was: the seeds and every version in force sit at or above their product's minimum).
        if (autoApprovalCeiling.compareTo(product.minimumAmount()) < 0) {
            throw new PolicyIncomplete("the auto-approval ceiling is at least the product's minimum amount");
        }
        Map<CreditSourceKind, Duration> ages = new EnumMap<>(CreditSourceKind.class);
        maximumDataAge.forEach((kind, age) -> {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(age, "age");
            if (age.isNegative() || age.isZero() || age.compareTo(MAXIMUM_DATA_AGE) > 0 || age.getNano() != 0) {
                throw new PolicyIncomplete(kind + "'s maximum data age is 1 second to a year, in whole seconds");
            }
            ages.put(kind, age);
        });
        maximumDataAge = Map.copyOf(ages);
        rules = List.copyOf(rules);
        if (rules.isEmpty() || rules.size() > MAXIMUM_RULES) {
            throw new PolicyIncomplete("a policy has 1 to " + MAXIMUM_RULES + " rules");
        }
        Set<String> codes = new HashSet<>();
        for (PolicyRule rule : rules) {
            Objects.requireNonNull(rule, "rule");
            if (!codes.add(rule.ruleCode())) {
                throw new PolicyIncomplete("rule " + rule.ruleCode() + " is named twice");
            }
            if (rule.operand() instanceof Operand.MoneyOperand money) {
                inProductCurrency(product, "rule " + rule.ruleCode() + "'s operand", money.value());
            }
            rule.cap().ifPresent(cap -> {
                inProductCurrency(product, "rule " + rule.ruleCode() + "'s cap", cap);
                if (cap.compareTo(product.minimumAmount()) < 0) {
                    throw new PolicyIncomplete("rule " + rule.ruleCode() + "'s cap is at least the product's minimum amount");
                }
            });
        }
        for (CreditSourceKind kind : ages.keySet()) {
            boolean covered = rules.stream().anyMatch(rule -> rule.effect() == unavailableFallback.effect()
                    && rule.reason() == ReasonCode.SOURCE_UNAVAILABLE
                    && rule.triggersWhenUnavailable(kind));
            if (!covered) {
                throw new PolicyIncomplete("the policy reads " + kind + " but no rule is guaranteed to "
                        + unavailableFallback.name() + " with " + ReasonCode.SOURCE_UNAVAILABLE.code()
                        + " when it is unavailable");
            }
        }
    }

    /**
     * Whether some rule keeps every system approval within {@link #maximumExposure} ({@link
     * PolicyRule#refusesExposurePast}). The evaluator judges exposure only through rules, so a policy without one would
     * approve past its own limit; the proposal door refuses it {@code credit.PolicyIncomplete}. Judged at proposal, not
     * here at construction, so a stored version always reads back.
     */
    public boolean boundsExposure() {
        return rules.stream().anyMatch(rule -> rule.refusesExposurePast(maximumExposure));
    }

    /** The source kinds this policy reads - exactly those it declares a maximum data age for. */
    public Set<CreditSourceKind> sourceKinds() {
        return maximumDataAge.isEmpty() ? EnumSet.noneOf(CreditSourceKind.class) : EnumSet.copyOf(maximumDataAge.keySet());
    }

    /** The catalogued reason code spelled {@code code}; any other leaves the policy incomplete ({@code INV-CRD-02}). */
    public static ReasonCode reasonCode(String code) {
        for (ReasonCode candidate : ReasonCode.values()) {
            if (candidate.code().equals(code)) {
                return candidate;
            }
        }
        throw new PolicyIncomplete("a rule's reason code is not in the catalogue");
    }

    /** The attribute or derived figure {@code name}, read as {@code kind}; anything else is outside the vocabulary. */
    public static Subject subject(String kind, String name) {
        try {
            return switch (kind) {
                case "ATTRIBUTE" -> new Subject.Attribute(CreditAttributeCode.valueOf(name));
                case "FIGURE" -> new Subject.Figure(PolicyFigure.valueOf(name));
                default -> throw new PolicyIncomplete("a rule reads an ATTRIBUTE or a FIGURE");
            };
        } catch (IllegalArgumentException | NullPointerException unknown) {
            if (unknown instanceof PolicyIncomplete incomplete) {
                throw incomplete;
            }
            throw new PolicyIncomplete("a rule reads an attribute or figure outside the vocabulary");
        }
    }

    private static void inProductCurrency(CreditProduct product, String what, Money amount) {
        if (!amount.currency().equals(product.currency()) || amount.scale() != product.currency().minorUnits()) {
            throw new PolicyIncomplete(what + " is in " + product.name() + "'s currency, " + product.currency()
                    + ", at its scale");
        }
    }

    // ------------------------------------------------------------------ a rule

    /**
     * One rule: (rule code, attribute or figure, operator, operand, effect, cap, reason). Its ordinal is its place in
     * the policy's list, from 1. Well typed at construction.
     */
    public record PolicyRule(
            String ruleCode,
            Subject subject,
            PolicyOperator operator,
            Operand operand,
            PolicyEffect effect,
            Optional<Money> cap,
            ReasonCode reason) {

        private static final Pattern CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

        public PolicyRule {
            Objects.requireNonNull(ruleCode, "ruleCode");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(cap, "cap");
            Objects.requireNonNull(reason, "reason");
            if (!CODE_SHAPE.matcher(ruleCode).matches()) {
                throw new PolicyIncomplete("a rule code is upper-case letters, digits and underscores");
            }
            String named = "rule " + ruleCode;
            AttributeValueType type = subject.valueType();
            if (operator.presence()) {
                if (!(subject instanceof Subject.Attribute)) {
                    throw new PolicyIncomplete(named + ": only an attribute can be absent - a figure is derived");
                }
                if (!(operand instanceof Operand.None)) {
                    throw new PolicyIncomplete(named + ": " + operator + " takes no operand");
                }
            } else if (operator.membership()) {
                if (type != AttributeValueType.CODE || !(operand instanceof Operand.CodesOperand)) {
                    throw new PolicyIncomplete(named + ": " + operator + " compares a code attribute with a code set");
                }
            } else if (operator.ordering()) {
                boolean agrees = (type == AttributeValueType.INTEGER && operand instanceof Operand.IntegerOperand)
                        || (type == AttributeValueType.MONEY && operand instanceof Operand.MoneyOperand);
                if (!agrees) {
                    throw new PolicyIncomplete(named + ": " + operator + " orders an integer or money with an operand of"
                            + " the same type");
                }
            } else {
                boolean agrees = switch (type) {
                    case INTEGER -> operand instanceof Operand.IntegerOperand;
                    case MONEY -> operand instanceof Operand.MoneyOperand;
                    case BOOLEAN -> operand instanceof Operand.BooleanOperand;
                    case CODE -> operand instanceof Operand.CodesOperand codes && codes.codes().size() == 1;
                };
                if (!agrees) {
                    throw new PolicyIncomplete(named + ": " + operator + " compares with one value of the "
                            + type + " the subject holds");
                }
            }
            if ((effect == PolicyEffect.CAP_AMOUNT) != cap.isPresent()) {
                throw new PolicyIncomplete(named + ": a CAP_AMOUNT rule carries its ceiling, and no other rule does");
            }
            if (cap.isPresent() && !cap.get().isPositive()) {
                throw new PolicyIncomplete(named + ": a cap is positive");
            }
            if (!reason.adverse()) {
                throw new PolicyIncomplete(named + ": its effect judges against the applicant, so its reason code is an"
                        + " adverse one - " + reason.code() + " is not");
            }
        }

        /**
         * Whether this rule triggers WHENEVER {@code kind} is unavailable, whatever else the snapshot holds - decided
         * from the rule alone ({@code INV-CRD-10}). An unavailable source leaves {@code SOURCE_UNAVAILABLE} naming it
         * (a {@link SourceKindsMarker} value) and every one of its own attributes {@code ABSENT}, so exactly three
         * shapes are guaranteed: {@code SOURCE_UNAVAILABLE IS_PRESENT}; {@code SOURCE_UNAVAILABLE IN} a set holding
         * every marker value naming the kind; and {@code IS_ABSENT} on one of the kind's own attributes.
         */
        public boolean triggersWhenUnavailable(CreditSourceKind kind) {
            Objects.requireNonNull(kind, "kind");
            if (!(subject instanceof Subject.Attribute attribute)) {
                return false;
            }
            if (attribute.code() == CreditAttributeCode.SOURCE_UNAVAILABLE) {
                if (operator == PolicyOperator.IS_PRESENT) {
                    return true;
                }
                return operator == PolicyOperator.IN && operand instanceof Operand.CodesOperand codes
                        && codes.codes().containsAll(markerValuesNaming(kind));
            }
            return operator == PolicyOperator.IS_ABSENT && SnapshotFreezer.codesOf(kind).contains(attribute.code());
        }

        /**
         * Whether this rule keeps the platform from approving ANY request whose exposure exceeds {@code limit} - decided
         * from the rule alone ({@code INV-CRD-09}; `P10-DOC-001`). Headroom is the limit less the exposure, so exactly
         * these shapes are guaranteed to trigger past the limit: {@code EXPOSURE_HEADROOM LT x} or {@code LE x} with
         * {@code x >= 0}, and {@code EXPOSURE GT x} or {@code GE x} with {@code x <= limit}; each with an effect that
         * never approves - {@code HARD_DECLINE}, {@code DECLINE} or {@code REFER} (a referral meets a person, whose
         * approval is bounded by the limit re-read under the profile lock). An exposure the assessment could not
         * compute leaves the rule unassessed, and an approval resting on it becomes the policy's fallback.
         */
        public boolean refusesExposurePast(Money limit) {
            Objects.requireNonNull(limit, "limit");
            if (effect == PolicyEffect.CAP_AMOUNT || !(subject instanceof Subject.Figure figure)
                    || !(operand instanceof Operand.MoneyOperand money)) {
                return false;
            }
            return switch (figure.figure()) {
                case EXPOSURE_HEADROOM -> (operator == PolicyOperator.LT || operator == PolicyOperator.LE)
                        && !money.value().isNegative();
                case EXPOSURE -> (operator == PolicyOperator.GT || operator == PolicyOperator.GE)
                        && money.value().compareTo(limit) <= 0;
                default -> false;
            };
        }

        /** Every {@code SOURCE_UNAVAILABLE} value that names {@code kind} - one per set of kinds holding it. */
        static Set<String> markerValuesNaming(CreditSourceKind kind) {
            Set<String> values = new HashSet<>();
            CreditSourceKind[] all = CreditSourceKind.values();
            for (int mask = 1; mask < (1 << all.length); mask++) {
                Set<CreditSourceKind> kinds = EnumSet.noneOf(CreditSourceKind.class);
                for (int i = 0; i < all.length; i++) {
                    if ((mask & (1 << i)) != 0) {
                        kinds.add(all[i]);
                    }
                }
                if (kinds.contains(kind)) {
                    values.add(SourceKindsMarker.of(kinds).value());
                }
            }
            return values;
        }
    }

    /** What a rule reads: an attribute of the vocabulary or a derived figure. */
    public sealed interface Subject permits Subject.Attribute, Subject.Figure {

        AttributeValueType valueType();

        /** The stored name. */
        String name();

        record Attribute(CreditAttributeCode code) implements Subject {
            public Attribute {
                Objects.requireNonNull(code, "code");
            }

            @Override
            public AttributeValueType valueType() {
                return code.valueType();
            }

            @Override
            public String name() {
                return code.name();
            }
        }

        record Figure(PolicyFigure figure) implements Subject {
            public Figure {
                Objects.requireNonNull(figure, "figure");
            }

            @Override
            public AttributeValueType valueType() {
                return figure.valueType();
            }

            @Override
            public String name() {
                return figure.name();
            }
        }
    }

    /** A rule's typed operand. */
    public sealed interface Operand
            permits Operand.None, Operand.IntegerOperand, Operand.MoneyOperand, Operand.BooleanOperand,
                    Operand.CodesOperand {

        /** The presence operators' - none. */
        record None() implements Operand {}

        record IntegerOperand(long value) implements Operand {}

        /** Money in the product's currency (the policy judges the currency). */
        record MoneyOperand(Money value) implements Operand {
            public MoneyOperand {
                Objects.requireNonNull(value, "value");
            }
        }

        record BooleanOperand(boolean value) implements Operand {}

        /** One code (for {@code EQ}/{@code NE}) or a set of them, in their stored canonical order. */
        record CodesOperand(List<String> codes) implements Operand {

            private static final Pattern MEMBER = Pattern.compile("[A-Z0-9_]{1,64}");

            public CodesOperand {
                Objects.requireNonNull(codes, "codes");
                if (codes.isEmpty() || codes.size() > 64) {
                    throw new PolicyIncomplete("a code operand holds 1 to 64 codes");
                }
                List<String> sorted = new ArrayList<>(new java.util.TreeSet<>(codes));
                if (sorted.size() != codes.size()) {
                    throw new PolicyIncomplete("a code operand names each code once");
                }
                for (String code : sorted) {
                    if (code == null || !MEMBER.matcher(code).matches()) {
                        throw new PolicyIncomplete("a code operand holds upper-case codes - letters, digits, underscores");
                    }
                }
                codes = List.copyOf(sorted);
            }
        }
    }

    /**
     * The policy is incomplete or ill typed ({@code 422 credit.PolicyIncomplete}); the message names the defect -
     * never a threshold.
     */
    public static final class PolicyIncomplete extends IllegalArgumentException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public PolicyIncomplete(String defect) {
            super(defect);
        }
    }
}
