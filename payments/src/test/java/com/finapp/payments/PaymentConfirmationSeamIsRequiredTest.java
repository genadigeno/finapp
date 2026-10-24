package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The checkout payment risk seam cannot be skipped (`P13-TSK-010`; the Phase 4 seam contract, {@code WithdrawalsSeam
 * IsRequiredTest}'s shape: a skipped control must not compile). Held structurally: {@link PaymentConfirmation} has
 * exactly one public constructor, it takes a {@link PaymentRiskDecision} over the confirmation's own
 * {@code Connection}, a null seam is refused, and nothing in payments' main code implements the seam - no permit-all
 * default to wire by accident (the composition's only implementation is {@code app}'s, over risk).
 */
@DisplayName("the checkout payment risk seam is a required parameter (P13-TSK-010)")
class PaymentConfirmationSeamIsRequiredTest {

    @Test
    @DisplayName("exactly one public constructor, and it takes the seam over the confirmation's Connection")
    void theSeamIsRequired() {
        Constructor<?>[] constructors = PaymentConfirmation.class.getConstructors();
        assertThat(constructors).as("no defaulted overload").hasSize(1);
        assertThat(Arrays.stream(constructors[0].getGenericParameterTypes())
                        .filter(type -> type instanceof ParameterizedType parameterized
                                && parameterized.getRawType().equals(PaymentRiskDecision.class)
                                && parameterized.getActualTypeArguments()[0].equals(Connection.class))
                        .count())
                .as("the seam over the confirmation's own unit of work")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a null seam is refused at construction")
    void aNullSeamIsRefused() {
        Constructor<?> only = PaymentConfirmation.class.getConstructors()[0];
        Object[] arguments = new Object[only.getParameterCount()];
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> only.newInstance(arguments))
                .hasRootCauseInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("payments ships no implementation of the seam - no permit-all to wire by accident")
    void paymentsShipsNoDefault() throws Exception {
        java.nio.file.Path main = java.nio.file.Path.of("src/main/java/com/finapp/payments");
        try (java.util.stream.Stream<java.nio.file.Path> sources = java.nio.file.Files.list(main)) {
            assertThat(sources
                            .filter(path -> path.toString().endsWith(".java"))
                            .filter(path -> {
                                try {
                                    return java.nio.file.Files.readString(path)
                                            .contains("implements PaymentRiskDecision");
                                } catch (java.io.IOException unreadable) {
                                    throw new java.io.UncheckedIOException(unreadable);
                                }
                            })
                            .toList())
                    .isEmpty();
        }
    }
}
