package com.finapp.credit;

import static com.finapp.credit.EvaluationFixtures.assessment;
import static com.finapp.credit.EvaluationFixtures.clean;
import static com.finapp.credit.EvaluationFixtures.eur;
import static com.finapp.credit.EvaluationFixtures.snapshot;
import static com.finapp.credit.EvaluationFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One input, one result ({@code INV-CRD-01}; `P10-TSK-013`): the evaluator reads no clock, no locale, no time zone and no
 * hash ordering, so neither the order the snapshot's attributes or a set operand's codes were loaded in, nor the JVM's
 * locale or zone, changes what it concludes.
 */
@DisplayName("the evaluator is deterministic (P10-TSK-013)")
class EvaluatorDeterminismTest {

    private static final PolicyEvaluator ENGINE = EngineVersions.STANDARD.engine(1);

    /** Three inputs exercising every path: an approval at a cap, a referral by the fallback, a decline with dedup. */
    private static List<EvaluationResult> evaluateAll(Random shuffle) {
        List<EvaluationResult> results = new ArrayList<>();
        List<CreditPolicy.PolicyRule> rules = CreditPolicyV1.rules(CreditProduct.PERSONAL_LOAN);
        rules.add(CreditPolicyV1.rule("RISK_SIGNAL_HIGH", CreditPolicyV1.attribute(CreditAttributeCode.RISK_SIGNAL),
                PolicyOperator.IN, new CreditPolicy.Operand.CodesOperand(List.of("HIGH", "SEVERE", "BLOCKED", "WATCH")),
                PolicyEffect.REFER, ReasonCode.RISK_REFERRAL));
        CreditPolicy v1 = shuffled(CreditPolicyV1.policy(CreditProduct.PERSONAL_LOAN, rules), shuffle);
        for (int score : new int[] {550, 700, 400}) {
            List<CreditAttribute> attributes = new ArrayList<>(score == 700
                    ? with(clean(), CreditAttributeCode.BUREAU_DEFAULTS_72M, new AttributeValue.Absent())
                    : clean());
            Collections.shuffle(attributes, shuffle);
            SnapshotContent snapshot = snapshot(attributes, eur(12_000_00));
            results.add(ENGINE.evaluate(snapshot, assessment(snapshot, score), v1));
        }
        return results;
    }

    /** {@code policy} rebuilt from its rules as rows arrive in any order: each set operand's codes shuffled. */
    private static CreditPolicy shuffled(CreditPolicy policy, Random shuffle) {
        List<CreditPolicy.PolicyRule> rules = new ArrayList<>();
        for (CreditPolicy.PolicyRule rule : policy.rules()) {
            CreditPolicy.Operand operand = rule.operand();
            if (operand instanceof CreditPolicy.Operand.CodesOperand codes) {
                List<String> members = new ArrayList<>(codes.codes());
                Collections.shuffle(members, shuffle);
                operand = new CreditPolicy.Operand.CodesOperand(members);
            }
            rules.add(new CreditPolicy.PolicyRule(rule.ruleCode(), rule.subject(), rule.operator(), operand, rule.effect(),
                    rule.cap(), rule.reason()));
        }
        return new CreditPolicy(policy.product(), policy.assessmentRateBps(), policy.minimumDisposable(),
                policy.minimumPaymentRatioBps(), policy.maximumExposure(), policy.maximumDataAge(),
                policy.unavailableFallback(), policy.autoApprovalCeiling(), rules);
    }

    private static <T> T under(Locale locale, TimeZone zone, Supplier<T> work) {
        Locale locale0 = Locale.getDefault();
        TimeZone zone0 = TimeZone.getDefault();
        try {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
            return work.get();
        } finally {
            Locale.setDefault(locale0);
            TimeZone.setDefault(zone0);
        }
    }

    @Test
    @DisplayName("shuffled load orders, three locales and three time zones conclude identically")
    void oneInputOneResult() {
        List<EvaluationResult> reference = evaluateAll(new Random(1));
        assertThat(reference).extracting(EvaluationResult::outcome).containsExactly(EvaluationOutcome.APPROVE,
                EvaluationOutcome.REFER, EvaluationOutcome.DECLINE);
        List<Locale> locales = List.of(Locale.ROOT, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar-SA"));
        List<TimeZone> zones = List.of(TimeZone.getTimeZone("UTC"), TimeZone.getTimeZone("Pacific/Kiritimati"),
                TimeZone.getTimeZone("America/Adak"));
        long seed = 2;
        for (Locale locale : locales) {
            for (TimeZone zone : zones) {
                for (int run = 0; run < 5; run++) {
                    Random shuffle = new Random(seed++);
                    assertThat(under(locale, zone, () -> evaluateAll(shuffle))).as("%s %s run %d", locale, zone.getID(), run)
                            .isEqualTo(reference);
                }
            }
        }
    }
}
