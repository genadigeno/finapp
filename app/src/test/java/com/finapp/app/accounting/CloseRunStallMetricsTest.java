package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.accounting.close.run.stalled} (`P14-TSK-017`; {@code PHASE_14_PLAN.md} sections 8.1, 16; the owner's decision of
 * 2026-11-02: the run's birth by the schedule stands on this condition): zero through the ordinary gap, one past the bound, zero again
 * once the check leg births the run; cached for 15 s; no value - never a reassuring zero - when the database cannot be read; and no tag.
 * The statement itself on the database's clock is {@code CloseRunStatementsTest}'s and {@code CloseRunStallDatabaseTest}'s (owed, C5).
 */
@DisplayName("finapp.accounting.close.run.stalled (P14-TSK-017)")
class CloseRunStallMetricsTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(FIXED, new SecureRandom());
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Connection CONNECTION = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final CloseRunStall STALL = new CloseRunStall(Duration.ofSeconds(30), CloseRunStall.STANDARD_POLLS);

    private final MovingClock clock = new MovingClock();
    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore store = new InMemoryCloseRunStore(periods);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private boolean unreadable;

    @BeforeEach
    void marchIsLocked() {
        AccountingPeriodId march = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(CONNECTION, new AccountingPeriodStore.NewPeriod(march, YearMonth.of(2097, 3),
                CorrelationId.generate(IDS)));
        periods.markLocked(CONNECTION, march, 1, ACCOUNTANT, Optional.empty());
        Instant lockedAt = periods.find(CONNECTION, march).orElseThrow().lockedAt().orElseThrow();
        // The store's clock stands at the lock: the bound is measured from there, as the database's clock would.
        store.advance(Duration.between(Instant.parse("2097-04-05T09:00:00Z"), lockedAt));
    }

    @Test
    @DisplayName("the ordinary gap is silent; past the bound it speaks, with no schedule and no leader; the check leg's birth clears it")
    void itSpeaksOnlyPastTheBound() {
        new CloseRunStallMetrics(store, STALL, this::open, clock, registry);
        assertThat(gauge()).as("just locked: the one-poll gap").isZero();

        store.advance(STALL.after().minusMillis(1));
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge()).as("a millisecond inside the bound").isZero();

        store.advance(Duration.ofMillis(1));
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge()).as("at the bound, on the store's clock").isEqualTo(1.0);

        new CloseRuns(store, periods, new InMemoryAccountingBreakStore(), CloseRuns.Leases.STANDARD, (uow, record) -> { }, IDS, FIXED)
                .startMissing(CONNECTION, Actor.SYSTEM);
        assertThat(gauge()).as("cached: the 15 s floor").isEqualTo(1.0);
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge()).as("the run is born").isZero();
    }

    @Test
    @DisplayName("ten instances read one number: each its own gauge, all from the database's row")
    void tenInstancesOneNumber() {
        store.advance(STALL.after());
        for (int instance = 0; instance < 10; instance++) {
            SimpleMeterRegistry own = new SimpleMeterRegistry();
            new CloseRunStallMetrics(store, STALL, this::open, clock, own);
            assertThat(own.get(CloseRunStallMetrics.STALLED).gauge().value()).as("instance %s", instance).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("unreadable is no value, never zero - and the meter carries no tag: a count names no period")
    void unreadableIsNoValue() {
        unreadable = true;
        new CloseRunStallMetrics(store, STALL, this::open, clock, registry);
        assertThat(gauge()).isNaN();
        assertThat(registry.get(CloseRunStallMetrics.STALLED).gauge().getId().getTags()).isEmpty();
        unreadable = false;
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge()).as("read again past the floor").isZero();
    }

    @Test
    @DisplayName("the bound: ten polls of the leg's thirty seconds by default - and refused under three, so the gap can never trip it")
    void theBound() {
        assertThat(STALL.after()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new CloseRunStall(Duration.ofMinutes(1), CloseRunStall.MIN_POLLS).after()).isEqualTo(Duration.ofMinutes(3));
    }

    private Connection open() throws SQLException {
        if (unreadable) {
            throw new SQLException("the database is away", "08006");
        }
        return CONNECTION;
    }

    private double gauge() {
        return registry.get(CloseRunStallMetrics.STALLED).gauge().value();
    }

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2097-04-05T09:00:00Z");

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
