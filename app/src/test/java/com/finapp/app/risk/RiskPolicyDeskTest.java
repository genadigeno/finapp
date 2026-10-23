package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.RiskAssurance;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyStore;
import com.finapp.risk.RiskPolicyVersion;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RiskPolicyVersionStatus;
import com.finapp.risk.RiskRule;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleParameters;
import com.finapp.risk.testing.RiskPolicyFixtures;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary half of the risk policy door, hermetic (`P13-TSK-002`): the closed body parsed exactly into the domain's
 * words - every fixture's shape round-trips, every unknown or misplaced word {@code risk.PolicyInvalid} before anything
 * is keyed, a parameter outside its kind's schema refused by name, a withdrawal's hold validity defaulted - and the
 * view serving the whole content and never a reason or a person. The door over HTTP - permissions, the step-up, keyed
 * replay, the refusals on the error contract - is {@code RiskPolicyEndpointDatabaseTest}'s (written, owed - C5).
 */
@DisplayName("the risk policy desk parses the closed body into the domain's words (P13-TSK-002)")
class RiskPolicyDeskTest {

    @Test
    @DisplayName("every v1 fixture, written over the wire, parses back to exactly itself")
    void everyFixtureRoundTrips() {
        for (RiskOperation operation : RiskPolicyFixtures.operations()) {
            RiskPolicy fixture = RiskPolicyFixtures.v1(operation);
            RiskPolicy parsed = RiskPolicyDesk.policy(request(fixture, Map.of()));
            assertThat(parsed).as(operation.name()).isEqualTo(fixture);
        }
    }

    @Test
    @DisplayName("every unknown or misplaced word is PolicyInvalid: operation, engine, currency, a kind, a decision, a"
            + " rating, an assurance, a window, a dimension, a fractional or non-numeric number, a corridor list that is"
            + " not one, a parameter the kind does not declare, a missing one, the credit signal")
    void everyUnknownWordIsPolicyInvalid() {
        RiskPolicy transfer = RiskPolicyFixtures.v1(RiskOperation.TRANSFER);
        List<Map<String, Object>> defects = List.of(
                Map.of("operation", "MORTGAGE"),
                Map.of("operation", "CREDIT_SIGNAL"),
                Map.of("engine", "RISK_ENGINE_V9"),
                Map.of("currency", "EURO"),
                Map.of("challengeAssurance", "TRIPLE_FACTOR"),
                Map.of("challengeAssurance", "SINGLE_FACTOR"),
                Map.of("holdValidityHours", 72),
                Map.of("scoreBands", List.of(new RiskPolicyController.RiskScoreBandRequest(0, 1000, "MAYBE"))),
                Map.of("scoreBands", List.of(new RiskPolicyController.RiskScoreBandRequest(0, 1000, "HOLD"))),
                Map.of("failSafeBands", List.of(new RiskPolicyController.RiskFailSafeBandRequest(0L, null, "REVIEW"))),
                Map.of("ratingBands", List.of(new RiskPolicyController.RiskRatingBandRequest(0, 1000, "LOW"))));
        for (Map<String, Object> defect : defects) {
            assertThatThrownBy(() -> RiskPolicyDesk.policy(request(transfer, defect)))
                    .as(defect.toString()).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        }
        List<RiskPolicyController.RiskRuleRequest> badRules = List.of(
                new RiskPolicyController.RiskRuleRequest("GUT_FEELING", Map.of(), 100, null),
                new RiskPolicyController.RiskRuleRequest("AMOUNT_BAND", Map.of("threshold_minor", 500_00), null, "MAYBE"),
                new RiskPolicyController.RiskRuleRequest("AMOUNT_BAND", Map.of("threshold_minor", 500_00), null, "HOLD"),
                new RiskPolicyController.RiskRuleRequest("STRUCTURING", Map.of("window", "D1", "below_minor", 100_000,
                        "threshold_count", 3, "sum_minor", 250_000), 100, null),
                new RiskPolicyController.RiskRuleRequest("AMOUNT_BAND", Map.of("threshold_minor", 5.5), 100, null),
                new RiskPolicyController.RiskRuleRequest("AMOUNT_BAND", Map.of("threshold_minor", "five hundred"), 100, null),
                new RiskPolicyController.RiskRuleRequest("AMOUNT_BAND", Map.of("threshold_minor", -1), 100, null),
                new RiskPolicyController.RiskRuleRequest("AMOUNT_BAND", Map.of("threshold_minor", 500_00, "window", "D1"),
                        100, null),
                new RiskPolicyController.RiskRuleRequest("AMOUNT_BAND", Map.of(), 100, null),
                new RiskPolicyController.RiskRuleRequest("AMOUNT_BAND", null, 100, null),
                new RiskPolicyController.RiskRuleRequest("VELOCITY_COUNT", Map.of("dimension", "PLANET", "window", "D1",
                        "threshold_count", 10), 100, null),
                // P13-TSK-006: a subject kind is no longer a velocity dimension - the source and the destination are.
                new RiskPolicyController.RiskRuleRequest("VELOCITY_COUNT", Map.of("dimension", "ACCOUNT", "window", "D1",
                        "threshold_count", 10), 100, null),
                new RiskPolicyController.RiskRuleRequest("VELOCITY_COUNT", Map.of("dimension", "PARTY", "window", "Y1",
                        "threshold_count", 10), 100, null),
                new RiskPolicyController.RiskRuleRequest("VELOCITY_COUNT", Map.of("dimension", "PARTY", "window", "D1",
                        "threshold_count", 10), 0, null),
                new RiskPolicyController.RiskRuleRequest("PROFILE_RATING", Map.of("rating", "TERRIBLE"), 100, null),
                new RiskPolicyController.RiskRuleRequest("SESSION_ASSURANCE_BELOW", Map.of("assurance", 2), 100, null),
                new RiskPolicyController.RiskRuleRequest("NEW_PAYEE", Map.of("lookback_days", 30), null, null),
                new RiskPolicyController.RiskRuleRequest("NEW_PAYEE", Map.of("lookback_days", 30_000_000_000L), 100, null),
                // P13-TSK-004 (owed by P13-TSK-003's gate): a ROUND_AMOUNT modulus of 0 - "a multiple of nothing" -
                // is refused at the door, so the engine never meets a rule that throws.
                new RiskPolicyController.RiskRuleRequest("ROUND_AMOUNT", Map.of("modulus_minor", 0), 100, null));
        for (RiskPolicyController.RiskRuleRequest bad : badRules) {
            assertThatThrownBy(() -> RiskPolicyDesk.policy(request(transfer, Map.of("rules", List.of(bad)))))
                    .as(bad.toString()).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        }
        RiskPolicy crossBorder = RiskPolicyFixtures.v1(RiskOperation.CROSS_BORDER);
        for (Object corridors : List.of("EUR-USD-US", List.of("EUR-USD"), List.of(1), List.of())) {
            assertThatThrownBy(() -> RiskPolicyDesk.policy(request(crossBorder, Map.of("rules", List.of(
                    new RiskPolicyController.RiskRuleRequest("CORRIDOR_HIGH_RISK", Map.of("corridors", corridors), 100, null))))))
                    .as(corridors.toString()).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        }
        assertThatThrownBy(() -> RiskPolicyDesk.parameters(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", 1, "extra", 2)))
                .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("declares no parameter 'extra'");
    }

    @Test
    @DisplayName("numbers are read exactly: an integer, a long, a decimal with no fraction and a digit string are each"
            + " the whole number; a withdrawal with no hold validity takes 72 h; the reads refuse an unknown operation")
    void numbersAreReadExactly() {
        for (Object raw : List.of(500_00, 500_00L, new BigDecimal("50000"), new BigDecimal("50000.0"), "50000")) {
            RuleParameters parsed = RiskPolicyDesk.parameters(RuleKind.AMOUNT_BAND, Map.of("threshold_minor", raw));
            assertThat(parsed.minorAmount("threshold_minor")).as(raw.getClass().getSimpleName()).isEqualTo(500_00L);
        }
        RuleParameters corridors = RiskPolicyDesk.parameters(RuleKind.CORRIDOR_HIGH_RISK,
                Map.of("corridors", List.of("EUR-USD-US", "EUR-GBP-GB", "EUR-USD-US")));
        assertThat(corridors.corridors("corridors")).containsExactly("EUR-GBP-GB", "EUR-USD-US");
        RiskPolicy withdrawal = RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL);
        Map<String, Object> noHold = new HashMap<>();
        noHold.put("holdValidityHours", null);
        assertThat(RiskPolicyDesk.policy(request(withdrawal, noHold)).holdValidity()).contains(Duration.ofHours(72));
        Map<String, Object> dayHold = Map.of("holdValidityHours", 24);
        assertThat(RiskPolicyDesk.policy(request(withdrawal, dayHold)).holdValidity()).contains(Duration.ofHours(24));
    }

    @Test
    @DisplayName("the view serves the whole content and the machine row - every rule's parameters by name, the bands, the"
            + " knobs - and never a reason or a person")
    void theViewServesTheContent() {
        RiskPolicy policy = RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL);
        RiskPolicyVersionId id = RiskPolicyVersionId.of(UUID.fromString("01900000-0000-7000-8000-000000000001"));
        Instant at = Instant.parse("2026-10-23T10:00:00Z");
        RiskPolicyDesk.RiskPolicyView view = RiskPolicyDesk.view(new RiskPolicyVersion(new RiskPolicyStore.VersionRow(id,
                RiskOperation.WITHDRAWAL, 2, "RISK_ENGINE_V1", RiskPolicyVersionStatus.ACTIVE, "alice", at,
                Optional.of("bob"), Optional.of(at), Optional.of(at), Optional.empty()), policy));
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.engine()).isEqualTo("RISK_ENGINE_V1");
        assertThat(view.currency()).isEqualTo("EUR");
        assertThat(view.scale()).isEqualTo(2);
        assertThat(view.rules()).hasSize(policy.rules().size());
        RiskPolicyDesk.RiskRuleView first = view.rules().get(0);
        assertThat(first.kind()).isEqualTo("AMOUNT_BAND");
        assertThat(first.parameters()).containsEntry("threshold_minor", 2_500_00L);
        assertThat(first.hardDecision()).isEqualTo("HOLD");
        assertThat(first.weight()).isNull();
        assertThat(view.scoreBands()).hasSize(4);
        assertThat(view.ratingBands()).isEmpty();
        assertThat(view.failSafeBands()).hasSize(1);
        assertThat(view.failSafeBands().get(0).toMinor()).isNull();
        assertThat(view.challengeAssurance()).isEqualTo("MULTI_FACTOR");
        assertThat(view.overrideThresholdMinor()).isEqualTo(1_000_00L);
        assertThat(view.holdValidityHours()).isEqualTo(72);
        assertThat(Arrays.stream(RiskPolicyDesk.RiskPolicyView.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(name -> name.contains("reason") || name.endsWith("By"));
        assertThat(view.toString()).doesNotContain("alice").doesNotContain("bob");
        // The view's shape is the request's shape: what the operator reads proposes again unchanged.
        RiskPolicy reproposed = RiskPolicyDesk.policy(fromView(view));
        assertThat(reproposed).isEqualTo(policy);
    }

    // -----------------------------------------------------------------

    /** {@code policy} as a proposal body, each entry of {@code changes} replacing one field. */
    static RiskPolicyController.RiskPolicyProposalRequest request(RiskPolicy policy, Map<String, Object> changes) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operation", policy.operation().name());
        fields.put("engine", policy.engine().name());
        fields.put("currency", policy.currency().code());
        List<RiskPolicyController.RiskRuleRequest> rules = new ArrayList<>();
        for (RiskRule rule : policy.rules()) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (RuleKind.Parameter parameter : rule.kind().parameters()) {
                Object raw = RiskPolicyDesk.view(new RiskPolicyVersion(row(policy), policy)).rules()
                        .get(rule.ordinal() - 1).parameters().get(parameter.name());
                parameters.put(parameter.name(), raw);
            }
            rules.add(new RiskPolicyController.RiskRuleRequest(rule.kind().name(), parameters,
                    rule.weight().isPresent() ? rule.weight().getAsInt() : null,
                    rule.hardDecision().map(Enum::name).orElse(null)));
        }
        fields.put("rules", rules);
        fields.put("scoreBands", policy.scoreBands().stream()
                .map(b -> new RiskPolicyController.RiskScoreBandRequest(b.from(), b.to(), b.decision().name())).toList());
        fields.put("ratingBands", policy.ratingBands().stream()
                .map(b -> new RiskPolicyController.RiskRatingBandRequest(b.from(), b.to(), b.rating().name())).toList());
        fields.put("failSafeBands", policy.failSafeBands().stream()
                .map(b -> new RiskPolicyController.RiskFailSafeBandRequest(b.fromMinor(),
                        b.toMinor().isPresent() ? b.toMinor().getAsLong() : null, b.decision().name())).toList());
        fields.put("challengeAssurance", policy.challengeAssurance().map(RiskAssurance::name).orElse(null));
        fields.put("overrideThresholdMinor", policy.overrideThreshold().map(m -> m.minorUnits()).orElse(null));
        fields.put("holdValidityHours", policy.holdValidity().map(d -> (int) d.toHours()).orElse(null));
        fields.put("reason", "the plan's v1 (PHASE_13_PLAN.md section 12.2)");
        fields.putAll(changes);
        return build(fields);
    }

    @SuppressWarnings("unchecked")
    private static RiskPolicyController.RiskPolicyProposalRequest build(Map<String, Object> f) {
        return new RiskPolicyController.RiskPolicyProposalRequest(
                (String) f.get("operation"), (String) f.get("engine"), (String) f.get("currency"),
                (List<RiskPolicyController.RiskRuleRequest>) f.get("rules"),
                (List<RiskPolicyController.RiskScoreBandRequest>) f.get("scoreBands"),
                (List<RiskPolicyController.RiskRatingBandRequest>) f.get("ratingBands"),
                (List<RiskPolicyController.RiskFailSafeBandRequest>) f.get("failSafeBands"),
                (String) f.get("challengeAssurance"),
                f.get("overrideThresholdMinor") == null ? null : ((Number) f.get("overrideThresholdMinor")).longValue(),
                (Integer) f.get("holdValidityHours"),
                (String) f.get("reason"));
    }

    private static RiskPolicyController.RiskPolicyProposalRequest fromView(RiskPolicyDesk.RiskPolicyView view) {
        return new RiskPolicyController.RiskPolicyProposalRequest(view.operation(), view.engine(), view.currency(),
                view.rules().stream().map(r -> new RiskPolicyController.RiskRuleRequest(r.kind(), r.parameters(), r.weight(),
                        r.hardDecision())).toList(),
                view.scoreBands().stream().map(b -> new RiskPolicyController.RiskScoreBandRequest(b.from(), b.to(),
                        b.decision())).toList(),
                view.ratingBands().stream().map(b -> new RiskPolicyController.RiskRatingBandRequest(b.from(), b.to(),
                        b.rating())).toList(),
                view.failSafeBands().stream().map(b -> new RiskPolicyController.RiskFailSafeBandRequest(b.fromMinor(),
                        b.toMinor(), b.decision())).toList(),
                view.challengeAssurance(), view.overrideThresholdMinor(), view.holdValidityHours(), "again");
    }

    private static RiskPolicyStore.VersionRow row(RiskPolicy policy) {
        // A fixed UUIDv7 (ADR-0013): EntityId refuses a random v4.
        return new RiskPolicyStore.VersionRow(
                RiskPolicyVersionId.of(UUID.fromString("01900000-0000-7000-8000-0000000000a1")), policy.operation(), 1,
                policy.engine().name(), RiskPolicyVersionStatus.PROPOSED, "alice", Instant.EPOCH, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
