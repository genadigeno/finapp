package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplConditionEvent;
import com.finapp.bnpl.BnplConditionKind;
import com.finapp.bnpl.BnplConditionStore;
import com.finapp.bnpl.BnplDelinquencyBucket;
import com.finapp.bnpl.BnplFeeAssessment;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The condition gauges, hermetic (`P12-TSK-020`; {@code PHASE_12_PLAN.md} section 15): {@code finapp.bnpl.delinquency
 * {product, bucket}} and {@code finapp.bnpl.default{product}} registered eagerly, read from the store's counts - NaN when
 * unreadable, never zero for a failed read - and tagged by the closed vocabularies only, never an obligation or a DPD. No
 * {@code DataSource} named (TestTaxonomyTest).
 */
@DisplayName("the BNPL condition gauges: obligation counts by bucket and default (P12-TSK-020)")
class BnplConditionMetricsTest {

    @Test
    @DisplayName("every product and bucket registered; the counts the store's, absent ones zero; NaN when unreadable")
    void theGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Map<BnplProduct, Map<BnplDelinquencyBucket, Long>> pastDue = new EnumMap<>(BnplProduct.class);
        pastDue.put(BnplProduct.BNPL_PAY_IN_4, new EnumMap<>(Map.of(BnplDelinquencyBucket.B2_30_59, 3L)));
        new BnplConditionMetrics(store(pastDue, Map.of(BnplProduct.BNPL_PAY_IN_N, 2L)),
                BnplConditionMetricsTest::connection, Clock.systemUTC(), registry);
        assertThat(registry.find(BnplConditionMetrics.DELINQUENCY).gauges())
                .hasSize(BnplProduct.values().length * BnplDelinquencyBucket.values().length);
        assertThat(registry.find(BnplConditionMetrics.DEFAULT).gauges()).hasSize(BnplProduct.values().length);
        assertThat(registry.get(BnplConditionMetrics.DELINQUENCY).tags("product", "bnpl_pay_in_4", "bucket", "b2_30_59")
                .gauge().value()).isEqualTo(3.0);
        assertThat(registry.get(BnplConditionMetrics.DELINQUENCY).tags("product", "bnpl_pay_in_n", "bucket", "b1_1_29")
                .gauge().value()).isZero();
        assertThat(registry.get(BnplConditionMetrics.DEFAULT).tags("product", "bnpl_pay_in_n").gauge().value())
                .isEqualTo(2.0);

        SimpleMeterRegistry unreadable = new SimpleMeterRegistry();
        new BnplConditionMetrics(store(pastDue, Map.of()), () -> {
            throw new SQLException("unreadable");
        }, Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), java.time.ZoneOffset.UTC), unreadable);
        assertThat(unreadable.get(BnplConditionMetrics.DEFAULT).tags("product", "bnpl_pay_in_4").gauge().value())
                .isNaN();
    }

    private static BnplConditionStore store(Map<BnplProduct, Map<BnplDelinquencyBucket, Long>> pastDue,
            Map<BnplProduct, Long> defaulted) {
        return new BnplConditionStore() {
            @Override
            public Instant databaseNow(Connection unitOfWork) {
                throw new UnsupportedOperationException("the gauges read counts only");
            }

            @Override
            public Optional<BnplConditionEvent> latest(Connection unitOfWork, BnplObligationId obligation,
                    BnplConditionKind kind) {
                throw new UnsupportedOperationException("the gauges read counts only");
            }

            @Override
            public void insertCondition(Connection unitOfWork, BnplConditionEvent event) {
                throw new UnsupportedOperationException("the gauges never write");
            }

            @Override
            public List<BnplFeeAssessment> assessments(Connection unitOfWork, BnplObligationId obligation) {
                throw new UnsupportedOperationException("the gauges read counts only");
            }

            @Override
            public void insertAssessment(Connection unitOfWork, BnplFeeAssessment assessment) {
                throw new UnsupportedOperationException("the gauges never write");
            }

            @Override
            public Map<BnplProduct, Map<BnplDelinquencyBucket, Long>> pastDueByProductAndBucket(Connection unitOfWork) {
                return pastDue;
            }

            @Override
            public Map<BnplProduct, Long> defaultedByProduct(Connection unitOfWork) {
                return defaulted;
            }
        };
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(BnplConditionMetricsTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    }
}
