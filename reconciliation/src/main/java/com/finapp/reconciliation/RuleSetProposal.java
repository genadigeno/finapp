package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A whole proposed rule set version for one source (`P8-TSK-022`, ADR-0068 §8): every member a
 * version holds — the dating lags, the matching rules, the tolerances, the provider's pinned fee
 * terms and the high-value thresholds — stated at once, because `V012` admits a member row only
 * in its proposal's own transaction and freezes the version from {@code PROPOSED}: an approver
 * approves exactly what was proposed, and nothing is appended after review.
 *
 * <p>{@link #validate()} is the domain rank of the member tables' {@code CHECK}s (`V002` as
 * `V010` and `V012` regenerated them), judged before anything is stored, so a malformed version
 * is a {@link RuleSetAdministration.RuleSetInvalid} rather than a constraint violation three
 * layers down. The tolerance vocabulary is judged FIRST: an amount tolerance is unrepresentable
 * ({@code INV-REC-08}), and that refusal is its own answer whatever else is wrong.
 *
 * <p>Refusals name the defect and at most an enumeration member — never a supplied value, so the
 * free-text reason and an unknown key kind are never echoed back.
 */
public record RuleSetProposal(
        UUID sourceId,
        int fundingLagDays,
        int gainMinAgeDays,
        Map<ExpectationKind, Integer> lagDays,
        List<Rule> rules,
        List<Tolerance> tolerances,
        List<FeeTerms> feeSchedules,
        Map<CurrencyCode, Long> severityThresholds,
        String reason) {

    /** {@code rule_set_reason_bounded} and {@code rule_set_event_reason_bounded}. */
    public static final int MAX_REASON_LENGTH = 1000;

    /** The date window's comparison — the one tolerance measured in days. */
    static final String SETTLEMENT_DATE_DAYS = "SETTLEMENT_DATE_DAYS";

    /**
     * {@code tolerance_comparison}: the two fee bounds and the date window, and nothing else —
     * there is no amount member (ADR-0068 §7, {@code INV-REC-08}).
     */
    private static final Set<String> PERMITTED_COMPARISONS =
            Set.of("PROCESSING_FEE_PER_LINE", "PROCESSING_FEE_PER_BATCH", SETTLEMENT_DATE_DAYS);

    /** The line side's key a fee or adjustment names its original by — never an index key. */
    private static final String ORIGINAL_REF = "ORIGINAL_REF";

    /** {@code rule_key_kind}: the expectation key vocabulary plus the line side's own key. */
    private static final Set<String> RULE_KEY_KINDS = ruleKeyKinds();

    /**
     * {@code rule_line_type} as `V020` regenerated it, listed rather than derived so a line type
     * added to the item vocabulary is not silently admitted here before the {@code CHECK} is.
     */
    private static final Set<ExternalLineType> RULE_LINE_TYPES =
            Collections.unmodifiableSet(
                    EnumSet.of(
                            ExternalLineType.CAPTURE,
                            ExternalLineType.REFUND,
                            ExternalLineType.CHARGEBACK,
                            ExternalLineType.CHARGEBACK_REVERSAL,
                            ExternalLineType.DISPUTE_FEE,
                            ExternalLineType.PROCESSING_FEE,
                            ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                            ExternalLineType.CREDIT_IN,
                            ExternalLineType.DEBIT_OUT,
                            ExternalLineType.SCHEME_FEE,
                            ExternalLineType.PAYOUT_EXECUTED,
                            ExternalLineType.PAYOUT_RETURNED,
                            ExternalLineType.PAYOUT_FEE,
                            ExternalLineType.BANK_CREDIT,
                            ExternalLineType.BANK_DEBIT,
                            ExternalLineType.BANK_FEE,
                            // P9-TSK-011, reconciliation V020: the FX provider's legs and fee.
                            ExternalLineType.FX_SOLD,
                            ExternalLineType.FX_BOUGHT,
                            ExternalLineType.FX_FEE));

    /** {@code provider_fee_line_type} as `V020` regenerated it: the five priced fee lines. */
    private static final Set<ExternalLineType> FEE_LINE_TYPES =
            Collections.unmodifiableSet(
                    EnumSet.of(
                            ExternalLineType.PROCESSING_FEE,
                            ExternalLineType.SCHEME_FEE,
                            ExternalLineType.BANK_FEE,
                            ExternalLineType.PAYOUT_FEE,
                            ExternalLineType.FX_FEE));

    /** {@code rate NUMERIC(7, 6)}: six decimal places, and below ten. */
    private static final int MAX_RATE_SCALE = 6;

    private static final BigDecimal RATE_CEILING = BigDecimal.TEN;

    /** {@code provider_fee_scale_bounded}. */
    private static final int MAX_FEE_SCALE = 9;

    /** {@code provider_fee_rounding}: the one named policy. */
    private static final String HALF_UP = "HALF_UP";

    /**
     * One matching rule (`V002`'s {@code rule}): {@code keyKind} is text because the rule
     * vocabulary is the expectation key kinds plus the line side's {@code ORIGINAL_REF}.
     */
    public record Rule(
            int priority,
            ExternalLineType lineType,
            Optional<String> keyKind,
            Optional<ExpectationKind> expectationKind,
            Cardinality cardinality,
            boolean operationAnchored,
            int graceHours) {

        public Rule {
            Objects.requireNonNull(lineType, "lineType must not be null");
            Objects.requireNonNull(keyKind, "keyKind must not be null; use Optional.empty()");
            Objects.requireNonNull(
                    expectationKind, "expectationKind must not be null; use Optional.empty()");
            Objects.requireNonNull(cardinality, "cardinality must not be null");
        }
    }

    /**
     * One tolerance (`V002`'s {@code tolerance}): a date window is {@code days}; a fee bound is
     * {@code absoluteMinor} in a named {@code currency}. Exactly one shape per comparison.
     */
    public record Tolerance(
            String comparison,
            Optional<CurrencyCode> currency,
            Optional<Long> absoluteMinor,
            Optional<Integer> days) {

        public Tolerance {
            Objects.requireNonNull(comparison, "comparison must not be null");
            Objects.requireNonNull(currency, "currency must not be null; use Optional.empty()");
            Objects.requireNonNull(
                    absoluteMinor, "absoluteMinor must not be null; use Optional.empty()");
            Objects.requireNonNull(days, "days must not be null; use Optional.empty()");
        }
    }

    /**
     * One pinned provider term (`V002`'s {@code provider_fee_schedule}): a fee line's expected
     * value is {@code round(rate x gross + fixed)} under the NAMED rounding (ADR-0068 §3, §7).
     */
    public record FeeTerms(
            ExternalLineType lineType,
            CurrencyCode currency,
            BigDecimal rate,
            long fixedMinor,
            int scale,
            String roundingPolicy) {

        public FeeTerms {
            Objects.requireNonNull(lineType, "lineType must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(rate, "rate must not be null");
            Objects.requireNonNull(roundingPolicy, "roundingPolicy must not be null");
        }
    }

    public RuleSetProposal {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        lagDays = orderedLags(lagDays);
        rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
        tolerances = List.copyOf(Objects.requireNonNull(tolerances, "tolerances must not be null"));
        feeSchedules =
                List.copyOf(Objects.requireNonNull(feeSchedules, "feeSchedules must not be null"));
        severityThresholds = orderedThresholds(severityThresholds);
    }

    /**
     * Judges the whole version before anything is stored.
     *
     * @throws RuleSetAdministration.ToleranceNotPermitted when any tolerance compares anything
     *     but a fee against its terms or a date — judged first ({@code INV-REC-08})
     * @throws RuleSetAdministration.RuleSetInvalid naming the first defect otherwise
     */
    public void validate() {
        for (Tolerance tolerance : tolerances) {
            if (!PERMITTED_COMPARISONS.contains(tolerance.comparison())) {
                throw new RuleSetAdministration.ToleranceNotPermitted();
            }
        }
        RuleSetAdministration.refuseReason(reason);
        if (fundingLagDays < 0) {
            throw invalid("funding_lag_days must not be negative");
        }
        if (gainMinAgeDays < 0) {
            throw invalid("gain_min_age_days must not be negative");
        }
        for (Map.Entry<ExpectationKind, Integer> lag : lagDays.entrySet()) {
            if (lag.getValue() < 0) {
                throw invalid("the lag for " + lag.getKey().name() + " must not be negative");
            }
        }
        validateRules();
        validateTolerances();
        validateFeeSchedules();
        for (Map.Entry<CurrencyCode, Long> threshold : severityThresholds.entrySet()) {
            if (threshold.getValue() < 0) {
                throw invalid(
                        "the high-value threshold for " + threshold.getKey().code()
                                + " must not be negative");
            }
        }
    }

    private void validateRules() {
        if (rules.isEmpty()) {
            throw invalid("a version holds at least one matching rule");
        }
        Set<Integer> priorities = new HashSet<>();
        for (Rule rule : rules) {
            String line = rule.lineType().name();
            if (rule.priority() < 1) {
                throw invalid("a rule's priority is at least 1");
            }
            if (!priorities.add(rule.priority())) {
                throw invalid("each rule's priority appears once in the version");
            }
            if (rule.keyKind().isPresent() && !RULE_KEY_KINDS.contains(rule.keyKind().get())) {
                throw invalid("a " + line + " rule names a key kind outside the rule vocabulary");
            }
            if (!RULE_LINE_TYPES.contains(rule.lineType())) {
                throw invalid(line + " is not a line type a matching rule may name");
            }
            if (rule.cardinality() == Cardinality.ONE_TO_ONE
                    && rule.keyKind().filter(ORIGINAL_REF::equals).isPresent()) {
                // The matcher reads a ONE_TO_ONE rule's key as an expectation key kind
                // (MatchingRules); ORIGINAL_REF is the line side's, read only by the CHECK and
                // CORRECTION legs, so such a rule would fail every evaluation of its line type.
                throw invalid(
                        "a ONE_TO_ONE " + line + " rule keys on an expectation key kind;"
                                + " ORIGINAL_REF is read only by CHECK and CORRECTION rules");
            }
            if (rule.operationAnchored() && rule.expectationKind().isEmpty()) {
                throw invalid(
                        "an operation-anchored " + line + " rule names the expectation kind it"
                                + " reaches (rule_anchor_names_its_kind)");
            }
            if (rule.graceHours() < 0) {
                throw invalid("a " + line + " rule's grace_hours must not be negative");
            }
        }
    }

    private void validateTolerances() {
        Set<ToleranceKey> seen = new HashSet<>();
        for (Tolerance tolerance : tolerances) {
            String comparison = tolerance.comparison();
            if (SETTLEMENT_DATE_DAYS.equals(comparison)) {
                if (tolerance.days().isEmpty()
                        || tolerance.days().get() < 0
                        || tolerance.currency().isPresent()
                        || tolerance.absoluteMinor().isPresent()) {
                    throw invalid(
                            "a SETTLEMENT_DATE_DAYS tolerance is a count of days of at least"
                                    + " zero, with no currency and no minor units");
                }
            } else if (tolerance.currency().isEmpty()
                    || tolerance.absoluteMinor().isEmpty()
                    || tolerance.absoluteMinor().get() < 0
                    || tolerance.days().isPresent()) {
                throw invalid(
                        "a " + comparison + " tolerance is a bound of at least zero minor units"
                                + " in a named currency, with no days");
            }
            if (!seen.add(new ToleranceKey(comparison, tolerance.currency()))) {
                throw invalid(
                        "each " + comparison + " tolerance appears once per currency"
                                + " (tolerance_once)");
            }
        }
    }

    private void validateFeeSchedules() {
        Set<FeeKey> seen = new HashSet<>();
        for (FeeTerms terms : feeSchedules) {
            String line = terms.lineType().name();
            if (!FEE_LINE_TYPES.contains(terms.lineType())) {
                throw invalid(
                        line + " carries no fee schedule: only " + FEE_LINE_TYPES + " are priced");
            }
            if (terms.rate().signum() < 0) {
                throw invalid("a " + line + " rate must not be negative");
            }
            if (terms.rate().stripTrailingZeros().scale() > MAX_RATE_SCALE) {
                // NUMERIC(7, 6) would ROUND a seventh significant decimal silently: the pinned
                // terms would not be the terms the approver read.
                throw invalid("a " + line + " rate carries at most six decimal places");
            }
            if (terms.rate().compareTo(RATE_CEILING) >= 0) {
                throw invalid("a " + line + " rate is below 10 (NUMERIC(7, 6))");
            }
            if (terms.fixedMinor() < 0) {
                throw invalid("a " + line + " fixed part must not be negative");
            }
            if (terms.scale() < 0 || terms.scale() > MAX_FEE_SCALE) {
                throw invalid("a " + line + " fee schedule's scale is 0.." + MAX_FEE_SCALE);
            }
            if (terms.scale() != terms.currency().minorUnits()) {
                // P9-TSK-003: FeeCheck prices in raw minor units (rate x gross + fixed), so a JPY
                // schedule entered at scale 2 would read its fixed part a hundred times too large,
                // silently. A schedule is priced in its currency's own minor units (INV-MON-05);
                // this door is its only writer, since no migration seeds a rule set (D26).
                throw invalid("a " + line + " fee schedule in " + terms.currency() + " is priced"
                        + " at " + terms.currency() + "'s " + terms.currency().minorUnits()
                        + " minor units, not at scale " + terms.scale());
            }
            if (!HALF_UP.equals(terms.roundingPolicy())) {
                throw invalid("a " + line + " fee schedule names the HALF_UP rounding policy");
            }
            if (!seen.add(new FeeKey(terms.lineType(), terms.currency()))) {
                throw invalid(
                        "each " + line + " fee schedule appears once per currency"
                                + " (provider_fee_schedule_pk)");
            }
        }
    }

    private static RuleSetAdministration.RuleSetInvalid invalid(String defect) {
        return new RuleSetAdministration.RuleSetInvalid(defect);
    }

    private record ToleranceKey(String comparison, Optional<CurrencyCode> currency) {}

    private record FeeKey(ExternalLineType lineType, CurrencyCode currency) {}

    private static Set<String> ruleKeyKinds() {
        Set<String> kinds = new HashSet<>();
        for (KeyKind kind : KeyKind.values()) {
            kinds.add(kind.name());
        }
        kinds.add(ORIGINAL_REF);
        return Set.copyOf(kinds);
    }

    /** A defensive, deterministic copy: kind order, no null kind or lag. */
    static Map<ExpectationKind, Integer> orderedLags(Map<ExpectationKind, Integer> lagDays) {
        Objects.requireNonNull(lagDays, "lagDays must not be null");
        Map<ExpectationKind, Integer> copy = new EnumMap<>(ExpectationKind.class);
        lagDays.forEach(
                (kind, lag) ->
                        copy.put(
                                Objects.requireNonNull(kind, "a lag's kind must not be null"),
                                Objects.requireNonNull(lag, "a lag must not be null")));
        return Collections.unmodifiableMap(copy);
    }

    /** A defensive, deterministic copy: currency-code order, no null currency or threshold. */
    static Map<CurrencyCode, Long> orderedThresholds(Map<CurrencyCode, Long> thresholds) {
        Objects.requireNonNull(thresholds, "severityThresholds must not be null");
        Map<CurrencyCode, Long> copy = new TreeMap<>(Comparator.comparing(CurrencyCode::code));
        thresholds.forEach(
                (currency, minor) ->
                        copy.put(
                                Objects.requireNonNull(
                                        currency, "a threshold's currency must not be null"),
                                Objects.requireNonNull(minor, "a threshold must not be null")));
        return Collections.unmodifiableMap(copy);
    }
}
