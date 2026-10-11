package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.lending.offer{product, outcome}} (`P11-TSK-011`, PHASE_11_PLAN.md section 15): every series registered
 * at zero on a fresh instance, and an origination step counted only when it moved the application - a step that found
 * the application moved, or credit still deciding, counts nothing.
 */
@DisplayName("the offer meter (P11-TSK-011)")
class LoanOfferMetricsTest {

    @Test
    @DisplayName("every product and outcome is registered at zero; only a step that moved the application counts, under"
            + " its own outcome")
    void everySeriesEagerAndOnlyMovesCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LoanOfferMetrics metrics = new LoanOfferMetrics(registry);
        for (LoanProduct product : LoanProduct.values()) {
            for (LoanOfferMetrics.Outcome outcome : LoanOfferMetrics.Outcome.values()) {
                assertThat(counter(registry, product, outcome.name().toLowerCase(java.util.Locale.ROOT)).count())
                        .isZero();
            }
        }
        metrics.record(step(LoanOrigination.Outcome.Kind.NOTHING, Optional.empty()));
        metrics.record(step(LoanOrigination.Outcome.Kind.WAITING, Optional.of(LoanProduct.PERSONAL_LOAN)));
        metrics.record(step(LoanOrigination.Outcome.Kind.OFFERED, Optional.of(LoanProduct.PERSONAL_LOAN)));
        metrics.record(step(LoanOrigination.Outcome.Kind.DECLINED, Optional.of(LoanProduct.CREDIT_LINE)));
        metrics.record(step(LoanOrigination.Outcome.Kind.CLOSED_UNDECIDED, Optional.of(LoanProduct.CREDIT_LINE)));
        assertThat(counter(registry, LoanProduct.PERSONAL_LOAN, "offered").count()).isEqualTo(1.0);
        assertThat(counter(registry, LoanProduct.CREDIT_LINE, "application_declined").count()).isEqualTo(1.0);
        assertThat(counter(registry, LoanProduct.CREDIT_LINE, "application_undecided").count()).isEqualTo(1.0);
        assertThat(registry.find(LoanOfferMetrics.OFFER).counters().stream().mapToDouble(Counter::count).sum())
                .as("NOTHING and WAITING count nothing").isEqualTo(3.0);
    }

    private static Counter counter(SimpleMeterRegistry registry, LoanProduct product, String outcome) {
        return registry.find(LoanOfferMetrics.OFFER).tag("product", product.name()).tag("outcome", outcome).counter();
    }

    private static LoanOrigination.Outcome step(LoanOrigination.Outcome.Kind kind, Optional<LoanProduct> product) {
        return new LoanOrigination.Outcome(kind, product, Optional.empty());
    }
}
