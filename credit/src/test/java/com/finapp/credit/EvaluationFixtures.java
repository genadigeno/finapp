package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure inputs for the evaluator's cases (`P10-TSK-013`) - a snapshot, its assessment and a policy, no database. */
final class EvaluationFixtures {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final UUID DECISION = UUID.fromString("0190a1b2-5c0e-7000-8000-0000000e0001");
    static final UUID PARTY = UUID.fromString("0190a1b2-5c0e-7000-8000-0000000e0002");
    static final UUID MODEL = UUID.fromString("0190a1b2-5c0e-7000-8000-0000000e0003");
    static final PinnedVersions VERSIONS = new PinnedVersions(CreditPolicyV1.PERSONAL_LOAN_ID.value(), MODEL, 1);

    private EvaluationFixtures() {}

    static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, EUR);
    }

    /** Every attribute present and clean - a request v1 approves in full up to its ceiling. */
    static List<CreditAttribute> clean() {
        AttributeProvenance bureau = new AttributeProvenance.Provider(CreditSourceKind.BUREAU, "bureau-sim-a", 1);
        AttributeProvenance findata = new AttributeProvenance.Provider(CreditSourceKind.FINANCIAL_DATA, "findata-sim-a", 1);
        List<CreditAttribute> all = new ArrayList<>();
        for (CreditAttribute attribute : CanonicalSnapshotTest.attributes()) {
            all.add(switch (attribute.code()) {
                case BUREAU_DEFAULTS_72M -> new CreditAttribute(attribute.code(), new AttributeValue.IntegerValue(0), bureau);
                case FINDATA_MONTHLY_INCOME ->
                        new CreditAttribute(attribute.code(), new AttributeValue.MoneyValue(eur(330_000)), findata);
                case FINDATA_MONTHLY_COMMITTED_EXPENDITURE ->
                        new CreditAttribute(attribute.code(), new AttributeValue.MoneyValue(eur(120_000)), findata);
                default -> attribute;
            });
        }
        return all;
    }

    /** {@code attributes} with {@code code} holding {@code value}. */
    static List<CreditAttribute> with(List<CreditAttribute> attributes, CreditAttributeCode code, AttributeValue value) {
        List<CreditAttribute> all = new ArrayList<>();
        for (CreditAttribute attribute : attributes) {
            all.add(attribute.code() == code ? new CreditAttribute(code, value, attribute.provenance()) : attribute);
        }
        return all;
    }

    /** {@code attributes} without {@code code} at all - a snapshot that was never given it. */
    static List<CreditAttribute> without(List<CreditAttribute> attributes, CreditAttributeCode code) {
        return attributes.stream().filter(attribute -> attribute.code() != code).toList();
    }

    /** {@code attributes} as a bureau outage freezes them: every bureau code ABSENT, the marker naming BUREAU. */
    static List<CreditAttribute> bureauUnavailable(List<CreditAttribute> attributes) {
        List<CreditAttribute> all = new ArrayList<>();
        Set<CreditAttributeCode> bureau = SnapshotFreezer.codesOf(CreditSourceKind.BUREAU);
        for (CreditAttribute attribute : attributes) {
            if (bureau.contains(attribute.code())) {
                all.add(new CreditAttribute(attribute.code(), new AttributeValue.Absent(), attribute.provenance()));
            } else if (attribute.code() == CreditAttributeCode.SOURCE_UNAVAILABLE) {
                all.add(new CreditAttribute(attribute.code(), SourceKindsMarker.of(Set.of(CreditSourceKind.BUREAU)),
                        attribute.provenance()));
            } else {
                all.add(attribute);
            }
        }
        return all;
    }

    static SnapshotContent snapshot(List<CreditAttribute> attributes, Money requested) {
        return new SnapshotContent(DECISION, PARTY, CreditProduct.PERSONAL_LOAN, requested, Optional.of(36), VERSIONS,
                attributes);
    }

    static SnapshotContent snapshot(Money requested) {
        return snapshot(clean(), requested);
    }

    /** An assessment of {@code snapshot}: the figures as given. */
    static CreditAssessment assessment(
            SnapshotContent snapshot,
            AffordabilityAssessment.Assessment affordability,
            ExposureAssessment.Assessment exposure,
            int score) {
        return new CreditAssessment(CreditAssessmentId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000e0004")),
                DecisionSnapshotId.of(UUID.fromString("0190a1b2-5c0e-7000-8000-0000000e0005")), snapshot.decisionRequest(),
                new byte[32], snapshot.versions(), EUR, affordability, exposure, score, Instant.EPOCH);
    }

    /** An assessment whose figures are all assessed: affordable with 1,000.00 to spare, 20,000.00 of headroom. */
    static CreditAssessment assessment(SnapshotContent snapshot, int score) {
        return assessment(snapshot, affordable(true), within(eur(2_000_000)), score);
    }

    static AffordabilityAssessment.Assessment affordable(boolean affordable) {
        return new AffordabilityAssessment.Assessment.Assessed(eur(330_000), eur(140_000), eur(35_000), eur(30_000),
                eur(125_000), affordable);
    }

    static ExposureAssessment.Assessment within(Money headroom) {
        return new ExposureAssessment.Assessment.Assessed(eur(4_000_000).minus(headroom), headroom, !headroom.isNegative());
    }

    static AffordabilityAssessment.Assessment unaffordable() {
        return new AffordabilityAssessment.Assessment.Unassessable(Set.of(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS));
    }

    static ExposureAssessment.Assessment unexposed() {
        return new ExposureAssessment.Assessment.Unassessable(Set.of(CreditAttributeCode.BUREAU_TOTAL_BALANCE));
    }

    /** A PERSONAL_LOAN policy reading no source (so owing no fallback rule) with {@code rules} and a ceiling. */
    static CreditPolicy policy(Money ceiling, UnavailableFallback fallback, List<CreditPolicy.PolicyRule> rules) {
        return new CreditPolicy(CreditProduct.PERSONAL_LOAN, 900, eur(100_00), 300, eur(40_000_00), Map.of(), fallback,
                ceiling, rules);
    }

    static CreditPolicy policy(List<CreditPolicy.PolicyRule> rules) {
        return policy(eur(1_000_000_00), UnavailableFallback.REFER, rules);
    }

    static CreditPolicy.PolicyRule rule(String code, CreditPolicy.Subject subject, PolicyOperator operator,
            CreditPolicy.Operand operand, PolicyEffect effect, ReasonCode reason) {
        return CreditPolicyV1.rule(code, subject, operator, operand, effect, reason);
    }

    /** A rule that always triggers: {@code SCORE GE 0}. */
    static CreditPolicy.PolicyRule always(String code, PolicyEffect effect, ReasonCode reason) {
        return new CreditPolicy.PolicyRule(code, CreditPolicyV1.figure(PolicyFigure.SCORE), PolicyOperator.GE,
                new CreditPolicy.Operand.IntegerOperand(0), effect,
                effect == PolicyEffect.CAP_AMOUNT ? Optional.of(eur(1_000_00)) : Optional.empty(), reason);
    }

    /** A cap that always triggers, at {@code cap}. */
    static CreditPolicy.PolicyRule cap(String code, Money cap, ReasonCode reason) {
        return new CreditPolicy.PolicyRule(code, CreditPolicyV1.figure(PolicyFigure.SCORE), PolicyOperator.GE,
                new CreditPolicy.Operand.IntegerOperand(0), PolicyEffect.CAP_AMOUNT, Optional.of(cap), reason);
    }
}
