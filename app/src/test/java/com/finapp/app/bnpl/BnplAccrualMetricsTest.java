package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplAccrualStore;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.bnpl.BnplInterestAccrual;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accrual's series, hermetic (`P12-TSK-017`; PHASE_12_PLAN.md section 15): {@code finapp.bnpl.accrual{outcome}}
 * registered eagerly and counted once per accrued date of a committed call, never a figure; and
 * {@code finapp.bnpl.accrual.lag} read from the store - the seconds since the end of the oldest unaccrued elapsed date, 0
 * when none, NaN when unreadable - cached behind the refresh floor. No {@code DataSource} named (TestTaxonomyTest).
 */
@DisplayName("the BNPL accrual's meters: per-date counts and the lag (P12-TSK-017)")
class BnplAccrualMetricsTest {

    @Test
    @DisplayName("every outcome is registered at zero; a committed call counts its posted and zero dates once each, a"
            + " failed call once")
    void theCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplAccrualMetrics metrics = metrics(registry);
        assertThat(registry.find(BnplAccrualMetrics.ACCRUAL).counters()).hasSize(3);
        metrics.record(new BnplAccruals.Outcome(BnplAccruals.Outcome.Kind.ACCRUED,
                Optional.of(BnplProduct.BNPL_PAY_IN_N), 5, 2, true));
        metrics.record(new BnplAccruals.Outcome(BnplAccruals.Outcome.Kind.NOT_ACCRUING,
                Optional.of(BnplProduct.BNPL_PAY_IN_4), 0, 0, true));
        metrics.failed();
        assertThat(count(registry, "posted")).isEqualTo(5.0);
        assertThat(count(registry, "zero")).isEqualTo(2.0);
        assertThat(count(registry, "failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the lag is the store's oldest unaccrued date in seconds - 0 when none, NaN when unreadable - refreshed"
            + " at most every 15 seconds")
    void theLag() {
        AtomicReference<Optional<Duration>> lag = new AtomicReference<>(Optional.of(Duration.ofHours(30)));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T08:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BnplAccrualMetrics(store(lag), BnplAccrualMetricsTest::connection, clock, registry);
        assertThat(gauge(registry)).isEqualTo(30 * 3600.0);
        assertThat(Duration.ofHours(30)).isGreaterThan(BnplAccrualMetrics.LAG_ALERT);
        lag.set(Optional.empty());
        assertThat(gauge(registry)).as("cached behind the refresh floor").isEqualTo(30 * 3600.0);
        now.set(now.get().plusSeconds(16));
        assertThat(gauge(registry)).isZero();

        SimpleMeterRegistry unreadable = new SimpleMeterRegistry();
        new BnplAccrualMetrics(store(lag), () -> {
            throw new SQLException("unreadable");
        }, clock, unreadable);
        assertThat(gauge(unreadable)).isNaN();
    }

    /** Meters over a store that reads nothing and a connection source that always fails - the lag NaN. */
    static BnplAccrualMetrics metrics(SimpleMeterRegistry registry) {
        return new BnplAccrualMetrics(store(new AtomicReference<>(Optional.empty())), () -> {
            throw new SQLException("no database in the unit tier");
        }, Clock.systemUTC(), registry);
    }

    private static BnplAccrualStore store(AtomicReference<Optional<Duration>> lag) {
        return new BnplAccrualStore() {
            @Override
            public Optional<BnplInterestAccrual> latest(Connection unitOfWork, BnplObligationId obligation) {
                return Optional.empty();
            }

            @Override
            public void insert(Connection unitOfWork, BnplInterestAccrual accrual) {
                throw new UnsupportedOperationException("the meters never write");
            }

            @Override
            public Optional<Duration> oldestLag(Connection unitOfWork) {
                return lag.get();
            }
        };
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(BnplAccrualMetricsTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    }

    private static double gauge(SimpleMeterRegistry registry) {
        return registry.get(BnplAccrualMetrics.LAG).gauge().value();
    }

    private static double count(SimpleMeterRegistry registry, String outcome) {
        return registry.get(BnplAccrualMetrics.ACCRUAL).tags("outcome", outcome).counter().count();
    }
}
