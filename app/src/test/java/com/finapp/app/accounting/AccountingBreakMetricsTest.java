package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.BreakFinding;
import com.finapp.accounting.BreakType;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.accounting.break.open{type}} (`P14-TSK-010`; {@code PHASE_14_PLAN.md} section 16): every break type
 * registered from the start at 0; a raised break counted under its type until it is resolved; cached for 15 s; no value
 * (NaN) when the database cannot be read - never a reassuring zero.
 */
@DisplayName("finapp.accounting.break.open{type} (P14-TSK-010)")
class AccountingBreakMetricsTest {

    private static final Connection CONNECTION = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);

    private final MovingClock clock = new MovingClock();
    private final IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private final InMemoryAccountingBreakStore store = new InMemoryAccountingBreakStore();
    private final AccountingBreaks breaks = new AccountingBreaks(store, new InMemoryJournal(), (uow, record) -> { },
            (uow, envelope, payload, mediaType) -> { }, ids, clock);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private boolean unreadable;

    @Test
    @DisplayName("every type at 0 from the start; a raised break counted under its type, behind the 15 s floor")
    void openBreaksPerType() {
        new AccountingBreakMetrics(breaks, new GlSnapshots(this::open, Duration.ofSeconds(2)), new AccountingGaugeRefresher(Runnable::run), clock, registry);
        for (BreakType type : BreakType.values()) {
            assertThat(gauge(type)).as("%s registered at 0", type).isZero();
        }
        raise(BreakType.MAPPING_GAP, CloseCheckCode.GL_EQUALS_LEDGER, "key:A/OPERATIONAL");
        assertThat(gauge(BreakType.MAPPING_GAP)).as("cached").isZero();
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge(BreakType.MAPPING_GAP)).isEqualTo(1.0);
        assertThat(gauge(BreakType.SHAPE_MISMATCH)).isZero();
        assertThat(registry.find(AccountingBreakMetrics.OPEN).gauges()).hasSize(BreakType.values().length);
    }

    @Test
    @DisplayName("unreadable is no value, never zero")
    void unreadableIsNoValue() {
        unreadable = true;
        new AccountingBreakMetrics(breaks, new GlSnapshots(this::open, Duration.ofSeconds(2)), new AccountingGaugeRefresher(Runnable::run), clock, registry);
        assertThat(gauge(BreakType.SOURCE_DISCREPANCY)).isNaN();
    }

    // -----------------------------------------------------------------

    private Connection open() throws SQLException {
        if (unreadable) {
            throw new SQLException("the database is away", "08006");
        }
        return new SnapshotSession().connection();
    }

    private void raise(BreakType type, CloseCheckCode check, String subject) {
        breaks.raise(CONNECTION, YearMonth.of(2097, 3), new BreakFinding(check, type, subject, "evidence",
                "gl-mapping:v1"), Actor.SYSTEM, CorrelationId.generate(ids));
    }

    private double gauge(BreakType type) {
        return registry.get(AccountingBreakMetrics.OPEN).tag("type", type.name()).gauge().value();
    }

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2097-04-02T09:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
