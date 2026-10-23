package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.Money;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A movement as the windows count it (`P13-TSK-006`): every {@link VelocityDimension} is a role some movement can carry,
 * each subject has its dimension's shape, and nothing about who moved how much reaches a {@code toString}.
 */
@DisplayName("a velocity movement carries a subject per role, each in its shape (P13-TSK-006)")
class VelocityMovementTest {

    private static final UUID ID = UUID.fromString("018f0000-0000-7000-8000-00000000000a");
    private static final DeviceHash DEVICE = DeviceHash.of("a device description");

    @Test
    @DisplayName("every velocity dimension maps to a role a movement can carry - the source and the destination apart,"
            + " an account and a beneficiary one destination")
    void everyDimensionIsARoleAMovementCanCarry() {
        Map<VelocityDimension, UnaryOperator<VelocityMovement.Builder>> roles = Map.of(
                VelocityDimension.PARTY, b -> b.party(ID),
                VelocityDimension.SOURCE_ACCOUNT, b -> b.sourceAccount(ID),
                VelocityDimension.DESTINATION, b -> b.destinationAccount(ID),
                VelocityDimension.DEVICE, b -> b.device(DEVICE),
                VelocityDimension.MERCHANT, b -> b.merchant(ID));
        assertThat(roles.keySet()).isEqualTo(EnumSet.allOf(VelocityDimension.class));
        for (Map.Entry<VelocityDimension, UnaryOperator<VelocityMovement.Builder>> role : roles.entrySet()) {
            VelocityMovement movement = role.getValue().apply(VelocityMovement.of(eur(1))).build();
            assertThat(movement.subjects().keySet()).as(role.getKey().name()).containsExactly(role.getKey());
            assertThat(new WindowCut(movement, WindowStarts.ANCHOR).keys())
                    .as("%s counts in every window kind", role.getKey())
                    .extracting(VelocityWindowKey::windowKind)
                    .containsExactly(WindowKind.values());
        }
        assertThat(VelocityMovement.of(eur(1)).destinationBeneficiary(ID).build().subject(VelocityDimension.DESTINATION))
                .contains(ID.toString());
        VelocityMovement transfer = VelocityMovement.of(eur(1)).sourceAccount(ID)
                .destinationAccount(UUID.fromString("018f0000-0000-7000-8000-00000000000b")).build();
        assertThat(transfer.subjects()).hasSize(2);
        assertThat(transfer.subject(VelocityDimension.SOURCE_ACCOUNT)).isNotEqualTo(
                transfer.subject(VelocityDimension.DESTINATION));
    }

    @Test
    @DisplayName("a device only by its hash, every other subject a canonical UUID; one subject per role; an amount 0 to"
            + " the engine's bound, never negative")
    void theShapesAndBounds() {
        assertThatThrownBy(() -> new VelocityMovement(Map.of(VelocityDimension.DEVICE, "Mozilla/5.0"), eur(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Mozilla");
        assertThatThrownBy(() -> new VelocityMovement(Map.of(VelocityDimension.PARTY, "Ada Lovelace"), eur(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Ada");
        assertThatThrownBy(() -> new VelocityMovement(Map.of(VelocityDimension.PARTY, ID.toString().toUpperCase()),
                eur(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VelocityMovement.of(eur(1)).destinationAccount(ID).destinationBeneficiary(ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VelocityMovement.of(eur(-1)).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VelocityMovement.of(eur(EvaluationInputs.MAX_MINOR + 1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(VelocityMovement.of(eur(0)).build().subjects()).isEmpty();
        assertThat(VelocityMovement.of(eur(EvaluationInputs.MAX_MINOR)).device(DEVICE).build().subject(
                VelocityDimension.DEVICE)).contains(DEVICE.value());
        for (VelocityDimension dimension : VelocityDimension.values()) {
            String sample = dimension == VelocityDimension.DEVICE ? DEVICE.value() : ID.toString();
            assertThat(dimension.referenceShape().matcher(sample).matches()).as(dimension.name()).isTrue();
        }
    }

    @Test
    @DisplayName("neither a movement nor a window key names its subject or its amount when printed (INV-RSK-08)")
    void nothingIsPrinted() {
        VelocityMovement movement = VelocityMovement.of(eur(123_45)).party(ID).device(DEVICE).build();
        assertThat(movement.toString()).doesNotContain(ID.toString(), DEVICE.value(), "12345");
        WindowCut cut = new WindowCut(movement, WindowStarts.ANCHOR);
        assertThat(cut.keys()).allSatisfy(key -> assertThat(key.toString()).doesNotContain(ID.toString(),
                DEVICE.value()));
        assertThat(Set.of(VelocityDimension.values())).hasSize(5);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, RiskPolicyFixtures.EUR);
    }
}
