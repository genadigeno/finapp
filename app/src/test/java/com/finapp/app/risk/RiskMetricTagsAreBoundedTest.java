package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.MetricNames;
import com.finapp.risk.ActionKind;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.CaseKind;
import com.finapp.risk.CaseStatus;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.ReasonCode;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RuleKind;
import com.finapp.risk.SuppressionReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The tipping-off control at the metric sink (`P13-TSK-018`; {@code INV-RSK-08}, {@code INV-AUD-02};
 * {@code PHASE_13_PLAN.md} section 15): <strong>every {@code finapp.risk.*} tag value is drawn from a bounded
 * vocabulary</strong> - a risk enum's names, or a constant the code owns - never an id, an amount, a name or a reason.
 * {@code MetricConventionTest} already holds the tag <em>keys</em> to {@link MetricNames#ALLOWED_TAG_KEYS}; this holds
 * each risk key's <em>values</em> to the one vocabulary that key may carry, over the live registry of the composed
 * application (every risk meter, the bean-registered sweeper gauges included - the {@code MetricConventionTest}
 * reasoning: a registry, not a list, so a new meter is covered without anyone remembering). A new key, or a value from
 * outside its vocabulary, fails here until it is argued for in {@link #VOCABULARIES}.
 */
@org.junit.jupiter.api.Tag("slice") // qualified: io.micrometer.core.instrument.Tag is imported here
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("every finapp.risk tag value is from a bounded vocabulary (P13-TSK-018, INV-RSK-08)")
class RiskMetricTagsAreBoundedTest {

    /** Each tag key a risk meter carries, and the closed vocabulary its value is from. */
    static final Map<String, Set<String>> VOCABULARIES = Map.of(
            // An alert's, a case's or an action's kind - and the sweepers' own kinds (the filing's drive, P13-TSK-021; the
            // rescreening leg, P13-TSK-022).
            "kind", union(List.of(names(AlertKind.values()), names(CaseKind.values()), names(ActionKind.values()),
                    Set.of(RiskMonitoringMetrics.KIND, RiskActionMetrics.KIND, RegulatoryReportMetrics.KIND,
                            RescreeningMetrics.KIND),
                    // P13-TSK-023: the censuses' kinds (finapp.risk.census).
                    names(com.finapp.risk.CensusKind.values()))),
            "severity", names(AlertSeverity.values()),
            // A suppression's reason code; the fail-safe's two reason codes.
            "reason_code", union(List.of(names(SuppressionReason.values()), names(ReasonCode.values()))),
            // A case's status; a regulatory report's (P13-TSK-021).
            "status", union(List.of(names(CaseStatus.values()),
                    names(com.finapp.risk.RegulatoryReportStatus.values()))),
            // An action's outcome and an override's act, lower-cased; a challenge's two outcomes; what became of a party
            // the monitoring rule leg met (P13-TSK-020), lower-cased; the authority's answer to a filing and a call that
            // threw (P13-TSK-021), lower-cased.
            "outcome", union(List.of(lower(names(ActionOutcome.values())), lower(names(RiskOverrideMetrics.Act.values())),
                    Set.of("satisfied", "refused"),
                    lower(names(com.finapp.risk.MonitoringObserver.SubjectOutcome.values())),
                    lower(names(com.finapp.risk.RegulatoryFilingProvider.Outcome.values())),
                    Set.of(RegulatoryReportMetrics.FAILED),
                    // What became of a claimed rescreening permit (P13-TSK-022), lower-cased.
                    lower(names(com.finapp.risk.RescreeningObserver.Conclusion.values())),
                    // P13-TSK-023 (the review's I1): a reading of the proofs completed or failed.
                    Set.of(RiskProofMetrics.COMPLETED, RiskProofMetrics.FAILED))),
            "operation", names(RiskOperation.values()),
            // A decision; the velocity proof's and the censuses' verdicts (P13-TSK-023).
            "verdict", union(List.of(names(RiskDecision.values()),
                    names(com.finapp.risk.VelocityProof.Verdict.values()),
                    names(com.finapp.risk.ProofVerdict.values()))),
            "rule", names(RuleKind.values()),
            "source", Set.of(MonitoringWatermark.LEDGER_JOURNAL));

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("every finapp.risk meter of the composed application carries only vocabulary keys, each with a value"
            + " of its vocabulary")
    void everyRiskTagIsBounded() {
        List<Meter> risk = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("finapp.risk.")).toList();
        assertThat(risk).as("the guard sees the risk meters - the case backlog gauges among them")
                .anyMatch(meter -> meter.getId().getName().equals(RiskCaseBacklogMetrics.BACKLOG))
                .anyMatch(meter -> meter.getId().getName().equals(RiskCaseBacklogMetrics.AGE))
                .anyMatch(meter -> meter.getId().getName().equals("finapp.risk.sweeper.enabled"))
                .hasSizeGreaterThan(100);
        assertThat(violations(risk)).isEmpty();
    }

    @Test
    @DisplayName("the rule has teeth: a tag carrying an id, a subject, an amount or an unknown key is refused")
    void theRuleHasTeeth() {
        SimpleMeterRegistry planted = new SimpleMeterRegistry();
        Counter.builder("finapp.risk.planted").tag("kind", "0192e1a0-0000-7000-8000-000000000018").register(planted);
        Counter.builder("finapp.risk.planted").tag("outcome", "ada lovelace").register(planted);
        Counter.builder("finapp.risk.planted").tag("module", "risk").register(planted);
        Counter.builder("finapp.risk.planted").tag("severity", "HIGH").register(planted);
        List<String> found = violations(planted.getMeters());
        assertThat(found).hasSize(3)
                .anyMatch(violation -> violation.contains("0192e1a0"))
                .anyMatch(violation -> violation.contains("ada lovelace"))
                .anyMatch(violation -> violation.contains("module"));
    }

    static List<String> violations(List<Meter> meters) {
        List<String> violations = new ArrayList<>();
        for (Meter meter : meters) {
            for (Tag tag : meter.getId().getTags()) {
                Set<String> vocabulary = VOCABULARIES.get(tag.getKey());
                if (vocabulary == null) {
                    violations.add(meter.getId().getName() + " carries the key '" + tag.getKey() + "', which no risk"
                            + " vocabulary bounds");
                } else if (!vocabulary.contains(tag.getValue())) {
                    violations.add(meter.getId().getName() + "{" + tag.getKey() + "=" + tag.getValue() + "} is not a"
                            + " value of its vocabulary");
                }
            }
        }
        return violations;
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> lower(Set<String> names) {
        return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> union(List<Set<String>> sets) {
        return sets.stream().flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new));
    }
}
