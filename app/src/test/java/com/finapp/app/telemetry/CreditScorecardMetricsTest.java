package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.ScorecardFamily;
import com.finapp.credit.ScorecardStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The requests waiting for a scorecard (`P15-TSK-025`; the debt row "No alert names a missing {@code ACTIVE} scorecard"). */
@DisplayName("finapp.credit.scorecard.awaited: the store's count for RETAIL_SCORECARD, NaN when unreadable (P15-TSK-025)")
class CreditScorecardMetricsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("the gauge reads what the store counts, for the retail family")
    void itReadsTheCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CreditScorecardMetrics(store(4L), () -> connection(), CLOCK, registry);

        assertThat(registry.get(CreditScorecardMetrics.AWAITED).gauge().value()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("a read that fails is NaN, never a zero that says nobody is waiting")
    void unreadableIsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CreditScorecardMetrics(store(new IllegalStateException("the read failed")), () -> connection(), CLOCK, registry);

        assertThat(registry.get(CreditScorecardMetrics.AWAITED).gauge().value()).isNaN();
    }

    private static ScorecardStore store(Object answer) {
        return (ScorecardStore) Proxy.newProxyInstance(CreditScorecardMetricsTest.class.getClassLoader(), new Class<?>[] {ScorecardStore.class},
                (self, method, args) -> {
                    assertThat(method.getName()).isEqualTo("awaitingAnActiveVersion");
                    assertThat(args[1]).isEqualTo(ScorecardFamily.RETAIL_SCORECARD);
                    if (answer instanceof RuntimeException failure) {
                        throw failure;
                    }
                    return answer;
                });
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(CreditScorecardMetricsTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                (self, method, args) -> null);
    }
}
