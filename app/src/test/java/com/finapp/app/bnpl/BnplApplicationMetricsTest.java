package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.bnpl.application{product, outcome}} (`P12-TSK-010`; lending's {@code LoanApplicationMetrics}): every
 * product's every outcome registered at zero from startup, each act counted once, tagged by product and outcome only.
 */
@DisplayName("the BNPL application meter (P12-TSK-010)")
class BnplApplicationMetricsTest {

    @Test
    @DisplayName("every product and outcome is registered eagerly at zero; an act increments its own series alone")
    void registeredEagerlyAndCountedOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplApplicationMetrics metrics = new BnplApplicationMetrics(registry);
        assertThat(registry.find(BnplApplicationMetrics.APPLICATION).counters())
                .hasSize(BnplProduct.values().length * BnplApplicationMetrics.Outcome.values().length)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        metrics.record(BnplProduct.BNPL_PAY_IN_4, BnplApplicationMetrics.Outcome.SUBMITTED);
        assertThat(count(registry, "BNPL_PAY_IN_4", "submitted")).isEqualTo(1.0);
        assertThat(count(registry, "BNPL_PAY_IN_N", "submitted")).isZero();
        assertThat(registry.find(BnplApplicationMetrics.APPLICATION).counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTags()).hasSize(2));
    }

    private static double count(SimpleMeterRegistry registry, String product, String outcome) {
        Counter counter = registry.find(BnplApplicationMetrics.APPLICATION).tags("product", product, "outcome", outcome)
                .counter();
        assertThat(counter).isNotNull();
        return counter.count();
    }
}
