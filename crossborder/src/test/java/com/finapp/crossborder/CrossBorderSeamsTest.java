package com.finapp.crossborder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Phase 13 seams are reserved, and only reserved (`P9-TSK-016`, ADR-0081 point 8) - the
 * {@code TransferSeamsTest} idiom: the default permits, its size is the no-Phase-13-logic assertion,
 * and the refusal codes exist before anything can produce them. That a {@code REFUSE} writes nothing is
 * proven by the seams' consumer, the authorization (`P9-TSK-019`).
 */
@DisplayName("the cross-border seams are required, field-free and reserved (P9-TSK-016)")
class CrossBorderSeamsTest {

    private static final CorridorKey CORRIDOR =
            new CorridorKey(CurrencyCode.of("EUR"), CurrencyCode.of("USD"), CountryCode.of("US"));

    @Test
    @DisplayName("PermitAllUntilPhase13 permits every instruction, through both seams")
    void theDefaultPermitsEverything() {
        CrossBorderInstruction instruction = new CrossBorderInstruction(
                UUID.randomUUID(), CORRIDOR, Money.of(new BigDecimal("100.00"), CurrencyCode.of("EUR")));
        CrossBorderLimitCheck<Object> limits = new PermitAllUntilPhase13<>();
        CrossBorderRiskDecision<Object> risk = new PermitAllUntilPhase13<>();
        assertThat(limits.check(null, instruction)).isEqualTo(CrossBorderVerdict.PERMIT);
        assertThat(risk.check(null, instruction)).isEqualTo(CrossBorderVerdict.PERMIT);
    }

    @Test
    @DisplayName("the seams' size IS the no-Phase-13-logic assertion: no state, one method each, two verdicts")
    void theSeamsCarryNoLogic() {
        assertThat(PermitAllUntilPhase13.class.getDeclaredFields())
                .as("PermitAllUntilPhase13 must hold no state of any kind")
                .isEmpty();
        assertThat(declaredMethodsOf(CrossBorderLimitCheck.class)).hasSize(1);
        assertThat(declaredMethodsOf(CrossBorderRiskDecision.class)).hasSize(1);
        assertThat(CrossBorderVerdict.values()).containsExactly(CrossBorderVerdict.PERMIT, CrossBorderVerdict.REFUSE);
    }

    @Test
    @DisplayName("the refusal codes are reserved now, each a 422 of its own")
    void theRefusalCodesAreReserved() {
        assertThat(CrossborderErrorCode.LIMIT_REFUSED.code()).isEqualTo("crossborder.LimitRefused");
        assertThat(CrossborderErrorCode.LIMIT_REFUSED.status()).isEqualTo(422);
        assertThat(CrossborderErrorCode.RISK_REFUSED.code()).isEqualTo("crossborder.RiskRefused");
        assertThat(CrossborderErrorCode.RISK_REFUSED.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("an instruction's amount is in its corridor's source currency")
    void theInstructionIsCoherent() {
        assertThatThrownBy(() -> new CrossBorderInstruction(
                        UUID.randomUUID(), CORRIDOR, Money.of(new BigDecimal("1.00"), CurrencyCode.of("USD"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Method[] declaredMethodsOf(Class<?> port) {
        return Arrays.stream(port.getDeclaredMethods()).filter(method -> !method.isSynthetic()).toArray(Method[]::new);
    }
}
