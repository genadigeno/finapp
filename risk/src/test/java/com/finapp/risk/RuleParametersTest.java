package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A rule's parameters held to its kind's schema, hermetic (`P13-TSK-002`; ADR-0109 decision 2; {@code INV-RSK-05}):
 * for every one of the twenty-two kinds a conforming set builds and a missing, an undeclared and a mistyped parameter
 * each refuse; every value type's bounds; the typed reads; nothing of a value in a {@code toString}.
 */
@DisplayName("rule parameters are held to their kind's schema (P13-TSK-002)")
class RuleParametersTest {

    @Test
    @DisplayName("for every kind: the declared set builds; a missing, an undeclared and a mistyped parameter are each"
            + " PolicyInvalid")
    void everyKindsSchema() {
        for (RuleKind kind : RuleKind.values()) {
            Map<String, ParameterValue> conforming = conforming(kind);
            RuleParameters built = RuleParameters.of(kind, conforming);
            assertThat(built.kind()).isEqualTo(kind);
            assertThat(built.values()).as(kind.name()).hasSize(kind.parameters().size());

            Map<String, ParameterValue> extra = new HashMap<>(conforming);
            extra.put("unexpected", new ParameterValue.Count(1));
            assertThatThrownBy(() -> RuleParameters.of(kind, extra)).as(kind + " with an undeclared parameter")
                    .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("declares no parameter");

            for (RuleKind.Parameter declared : kind.parameters()) {
                Map<String, ParameterValue> missing = new HashMap<>(conforming);
                missing.remove(declared.name());
                assertThatThrownBy(() -> RuleParameters.of(kind, missing)).as(kind + " without " + declared.name())
                        .isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("requires the parameter");

                Map<String, ParameterValue> mistyped = new HashMap<>(conforming);
                mistyped.put(declared.name(), otherType(declared.type()));
                assertThatThrownBy(() -> RuleParameters.of(kind, mistyped)).as(kind + " with " + declared.name()
                        + " mistyped").isInstanceOf(RiskPolicy.PolicyInvalid.class).hasMessageContaining("is a");
            }
        }
        assertThat(RuleParameters.none(RuleKind.SCREENING_HIT).values()).isEmpty();
        assertThatThrownBy(() -> RuleParameters.none(RuleKind.AMOUNT_BAND)).isInstanceOf(RiskPolicy.PolicyInvalid.class);
    }

    @Test
    @DisplayName("every value type's bounds: a negative count or amount, basis points past 10000, zero days or hours, an"
            + " empty or malformed corridor set")
    void everyTypesBounds() {
        assertThatThrownBy(() -> new ParameterValue.Count(-1)).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new ParameterValue.MinorAmount(-1)).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new ParameterValue.BasisPoints(10_001)).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new ParameterValue.BasisPoints(-1)).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new ParameterValue.Days(0)).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new ParameterValue.Hours(0)).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new ParameterValue.Corridors(Set.of())).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new ParameterValue.Corridors(Set.of("EUR-USD"))).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThatThrownBy(() -> new ParameterValue.Corridors(Set.of("eur-usd-us"))).isInstanceOf(RiskPolicy.PolicyInvalid.class);
        assertThat(new ParameterValue.Count(0).value()).isZero();
        assertThat(new ParameterValue.BasisPoints(10_000).value()).isEqualTo(10_000);
        assertThat(new ParameterValue.Corridors(Set.of("EUR-USD-US", "EUR-GBP-GB")).sorted())
                .containsExactly("EUR-GBP-GB", "EUR-USD-US");
    }

    @Test
    @DisplayName("the typed reads answer the declared name and type; a read of another name or type is a programming"
            + " error, never a policy's; the toString names no value")
    void theTypedReads() {
        RuleParameters structuring = RuleParameters.of(RuleKind.STRUCTURING, conforming(RuleKind.STRUCTURING));
        assertThat(structuring.window("window")).isEqualTo(WindowKind.D1);
        assertThat(structuring.minorAmount("below_minor")).isEqualTo(100L);
        assertThat(structuring.count("threshold_count")).isEqualTo(3L);
        assertThatThrownBy(() -> structuring.count("window")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> structuring.value("nothing")).isInstanceOf(IllegalArgumentException.class);
        RuleParameters velocity = RuleParameters.of(RuleKind.VELOCITY_COUNT, conforming(RuleKind.VELOCITY_COUNT));
        assertThat(velocity.dimension("dimension")).isEqualTo(VelocityDimension.PARTY);
        RuleParameters corridor = RuleParameters.of(RuleKind.CORRIDOR_HIGH_RISK, conforming(RuleKind.CORRIDOR_HIGH_RISK));
        assertThat(corridor.corridors("corridors")).containsExactly("EUR-USD-US");
        RuleParameters session = RuleParameters.of(RuleKind.SESSION_ASSURANCE_BELOW,
                conforming(RuleKind.SESSION_ASSURANCE_BELOW));
        assertThat(session.assurance("assurance")).isEqualTo(RiskAssurance.MULTI_FACTOR);
        RuleParameters profile = RuleParameters.of(RuleKind.PROFILE_RATING, conforming(RuleKind.PROFILE_RATING));
        assertThat(profile.rating("rating")).isEqualTo(RiskRating.HIGH);
        RuleParameters ratio = RuleParameters.of(RuleKind.RAPID_IN_OUT, conforming(RuleKind.RAPID_IN_OUT));
        assertThat(ratio.basisPoints("ratio_bps")).isEqualTo(50);
        RuleParameters age = RuleParameters.of(RuleKind.ACCOUNT_AGE_BELOW, conforming(RuleKind.ACCOUNT_AGE_BELOW));
        assertThat(age.days("days")).isEqualTo(7);
        RuleParameters recovery = RuleParameters.of(RuleKind.RECENT_RECOVERY, conforming(RuleKind.RECENT_RECOVERY));
        assertThat(recovery.hours("lookback_hours")).isEqualTo(24);
        assertThat(structuring.toString()).doesNotContain("100").contains("STRUCTURING");
    }

    // -----------------------------------------------------------------

    static Map<String, ParameterValue> conforming(RuleKind kind) {
        Map<String, ParameterValue> values = new HashMap<>();
        for (RuleKind.Parameter parameter : kind.parameters()) {
            values.put(parameter.name(), of(parameter.type()));
        }
        return values;
    }

    static ParameterValue of(RuleKind.ParameterType type) {
        return switch (type) {
            case COUNT -> new ParameterValue.Count(3);
            case MINOR_AMOUNT -> new ParameterValue.MinorAmount(100);
            case BASIS_POINTS -> new ParameterValue.BasisPoints(50);
            case DAYS -> new ParameterValue.Days(7);
            case HOURS -> new ParameterValue.Hours(24);
            case WINDOW -> new ParameterValue.Window(WindowKind.D1);
            case VELOCITY_DIMENSION -> new ParameterValue.Dimension(VelocityDimension.PARTY);
            case CORRIDOR_SET -> new ParameterValue.Corridors(Set.of("EUR-USD-US"));
            case ASSURANCE -> new ParameterValue.Assurance(RiskAssurance.MULTI_FACTOR);
            case RATING -> new ParameterValue.Rating(RiskRating.HIGH);
        };
    }

    /** A value of any type but {@code type}. */
    static ParameterValue otherType(RuleKind.ParameterType type) {
        List<RuleKind.ParameterType> types = List.of(RuleKind.ParameterType.values());
        return of(types.get((types.indexOf(type) + 1) % types.size()));
    }
}
