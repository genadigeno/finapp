package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The withdrawal risk seam cannot be skipped (`P13-TSK-009`; the Phase 4 seam contract, {@code TransferExecution}'s
 * "required, with no defaulted overload": a skipped control must not compile). Held structurally: {@link Withdrawals}
 * has exactly one public constructor, that constructor takes a {@link WithdrawalRiskDecision} over the dispatch's own
 * {@code Connection}, and nothing in payments' main code implements the seam - no permit-all default exists to wire by
 * accident (the composition's only implementation is {@code app}'s, over risk).
 */
@DisplayName("the withdrawal risk seam is a required parameter (P13-TSK-009)")
class WithdrawalsSeamIsRequiredTest {

    @Test
    @DisplayName("exactly one public constructor, and it takes the seam over the dispatch's Connection")
    void theSeamIsRequired() {
        Constructor<?>[] constructors = Withdrawals.class.getConstructors();
        assertThat(constructors).as("no defaulted overload").hasSize(1);
        Constructor<?> only = constructors[0];
        assertThat(Arrays.stream(only.getGenericParameterTypes())
                        .filter(type -> type instanceof ParameterizedType parameterized
                                && parameterized.getRawType().equals(WithdrawalRiskDecision.class)
                                && parameterized.getActualTypeArguments()[0].equals(Connection.class))
                        .count())
                .as("the seam over the dispatch's own unit of work")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a null seam is refused at construction")
    void aNullSeamIsRefused() {
        Constructor<?> only = Withdrawals.class.getConstructors()[0];
        Object[] arguments = new Object[only.getParameterCount()];
        // Every other collaborator present but irrelevant: the seam's own check must speak for itself.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> only.newInstance(arguments))
                .hasRootCauseInstanceOf(NullPointerException.class)
                .rootCause()
                .hasMessageContaining("risk");
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
                                            .contains("implements WithdrawalRiskDecision");
                                } catch (java.io.IOException unreadable) {
                                    throw new java.io.UncheckedIOException(unreadable);
                                }
                            })
                            .toList())
                    .isEmpty();
        }
    }
}
