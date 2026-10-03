package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.reconciliation.BreakReadings;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.Severity;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The open-break gauges' honesty rules (`P8-TSK-024`, `PHASE_8_PLAN.md` §15): every type by
 * severity and every severity's age exist eagerly; the counts are the readings' own; the age is
 * now minus the oldest stored raise of that severity, an honest zero when none is open; an
 * unreadable database reads NaN on every series, never a comforting zero; and one reading per
 * refresh floor serves every gauge (the {@code ReconciliationMetrics} shape).
 */
@DisplayName("the open-break gauges (P8-TSK-024)")
class BreakMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    /** A clock the test moves - the refresh floor is about time passing. */
    private static final class MovingClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        void advance(Duration by) {
            now.updateAndGet(instant -> instant.plus(by));
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
            return now.get();
        }
    }

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MovingClock clock = new MovingClock();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    /** A connection the readings never touch here - the gauges only open and close it. */
    private Connection connection() {
        opened.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        closed.incrementAndGet();
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private double open(BreakType type, Severity severity) {
        return registry.find(BreakMetrics.BREAK_OPEN)
                .tag("type", lower(type))
                .tag("severity", lower(severity))
                .gauge()
                .value();
    }

    private double age(Severity severity) {
        return registry.find(BreakMetrics.BREAK_AGE)
                .tag("severity", lower(severity))
                .gauge()
                .value();
    }

    private static final List<BreakReadings.OpenBreaks> READING =
            List.of(
                    new BreakReadings.OpenBreaks(
                            BreakType.MISSING_EXTERNAL, Severity.HIGH, 2, NOW.minusSeconds(3_600)),
                    new BreakReadings.OpenBreaks(
                            BreakType.AMOUNT_MISMATCH, Severity.HIGH, 1, NOW.minusSeconds(7_200)),
                    new BreakReadings.OpenBreaks(
                            BreakType.UNKNOWN_EXTERNAL, Severity.CRITICAL, 4, NOW.minusSeconds(90)));

    @Test
    @DisplayName("every type x severity count and every severity's age exist eagerly")
    void everySeriesIsEager() {
        new BreakMetrics(connection -> READING, this::connection, clock, registry);

        for (Severity severity : Severity.values()) {
            for (BreakType type : BreakType.values()) {
                assertThat(registry.find(BreakMetrics.BREAK_OPEN)
                                .tag("type", lower(type))
                                .tag("severity", lower(severity))
                                .gauge())
                        .as("break.open{%s,%s} exists eagerly", type, severity)
                        .isNotNull();
            }
            assertThat(registry.find(BreakMetrics.BREAK_AGE)
                            .tag("severity", lower(severity))
                            .gauge())
                    .as("break.age{%s} exists eagerly", severity)
                    .isNotNull();
        }
        assertThat(registry.find(BreakMetrics.BREAK_OPEN).gauges())
                .hasSize(BreakType.values().length * Severity.values().length);
        assertThat(registry.find(BreakMetrics.BREAK_AGE).gauges())
                .hasSize(Severity.values().length);
    }

    @Test
    @DisplayName("the counts are the readings' own per type and severity; a quiet pair reads an"
            + " honest zero")
    void countsPerTypeAndSeverity() {
        new BreakMetrics(connection -> READING, this::connection, clock, registry);

        assertThat(open(BreakType.MISSING_EXTERNAL, Severity.HIGH)).isEqualTo(2.0d);
        assertThat(open(BreakType.AMOUNT_MISMATCH, Severity.HIGH)).isEqualTo(1.0d);
        assertThat(open(BreakType.UNKNOWN_EXTERNAL, Severity.CRITICAL)).isEqualTo(4.0d);
        assertThat(open(BreakType.MISSING_EXTERNAL, Severity.CRITICAL))
                .as("another severity of the same type is its own series")
                .isZero();
        assertThat(open(BreakType.PROCESSING_ERROR, Severity.LOW)).isZero();
    }

    @Test
    @DisplayName("the age is now minus the oldest raise of that severity, in whole seconds; 0"
            + " when none of that severity is open")
    void agePerSeverity() {
        new BreakMetrics(connection -> READING, this::connection, clock, registry);

        assertThat(age(Severity.HIGH))
                .as("the OLDEST of the two HIGH types")
                .isEqualTo(7_200.0d);
        assertThat(age(Severity.CRITICAL)).isEqualTo(90.0d);
        assertThat(age(Severity.MEDIUM)).as("nothing MEDIUM is open").isZero();
        assertThat(age(Severity.LOW)).isZero();
    }

    @Test
    @DisplayName("a raise stamped after the reading's now ages at zero, never negative")
    void anAgeIsNeverNegative() {
        new BreakMetrics(
                connection -> List.of(new BreakReadings.OpenBreaks(
                        BreakType.FEE_MISMATCH, Severity.MEDIUM, 1, NOW.plusSeconds(30))),
                this::connection,
                clock,
                registry);

        assertThat(age(Severity.MEDIUM)).isZero();
        assertThat(open(BreakType.FEE_MISMATCH, Severity.MEDIUM)).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("nothing open reads zero on every series - an honest zero, not NaN")
    void nothingOpenReadsZero() {
        new BreakMetrics(connection -> List.of(), this::connection, clock, registry);

        for (Gauge gauge : registry.find(BreakMetrics.BREAK_OPEN).gauges()) {
            assertThat(gauge.value()).isZero();
        }
        for (Gauge gauge : registry.find(BreakMetrics.BREAK_AGE).gauges()) {
            assertThat(gauge.value()).isZero();
        }
    }

    @Test
    @DisplayName("an unreadable database reads NaN on every series - never a comforting zero")
    void anUnreadableConnectionReadsNaN() {
        new BreakMetrics(
                connection -> READING,
                () -> {
                    throw new SQLException("nope");
                },
                clock,
                registry);

        assertThat(registry.find(BreakMetrics.BREAK_OPEN).gauges())
                .isNotEmpty()
                .allSatisfy(gauge -> assertThat(gauge.value()).isNaN());
        assertThat(registry.find(BreakMetrics.BREAK_AGE).gauges())
                .isNotEmpty()
                .allSatisfy(gauge -> assertThat(gauge.value()).isNaN());
    }

    @Test
    @DisplayName("a reading that throws reads NaN on every series, and the connection is still"
            + " closed")
    void aFailingReadingReadsNaN() {
        new BreakMetrics(
                connection -> {
                    throw new IllegalStateException("the GROUP BY failed");
                },
                this::connection,
                clock,
                registry);

        assertThat(registry.find(BreakMetrics.BREAK_OPEN).gauges())
                .allSatisfy(gauge -> assertThat(gauge.value()).isNaN());
        assertThat(registry.find(BreakMetrics.BREAK_AGE).gauges())
                .allSatisfy(gauge -> assertThat(gauge.value()).isNaN());
        assertThat(closed.get()).as("every connection opened is closed").isEqualTo(opened.get());
    }

    @Test
    @DisplayName("one reading per refresh floor serves every gauge; past the floor it reads again")
    void theFloorCachesOneReading() {
        AtomicInteger reads = new AtomicInteger();
        new BreakMetrics(
                connection -> {
                    reads.incrementAndGet();
                    return READING;
                },
                this::connection,
                clock,
                registry);

        // A whole scrape: every gauge read once.
        registry.find(BreakMetrics.BREAK_OPEN).gauges().forEach(Gauge::value);
        registry.find(BreakMetrics.BREAK_AGE).gauges().forEach(Gauge::value);
        assertThat(reads).as("one GROUP BY for the whole scrape").hasValue(1);

        clock.advance(Duration.ofSeconds(10));
        assertThat(open(BreakType.MISSING_EXTERNAL, Severity.HIGH)).isEqualTo(2.0d);
        assertThat(reads).as("a second read inside the 15s floor is served cached").hasValue(1);

        clock.advance(Duration.ofSeconds(6));
        assertThat(open(BreakType.MISSING_EXTERNAL, Severity.HIGH)).isEqualTo(2.0d);
        assertThat(reads).as("past the floor the gauges read again").hasValue(2);
        assertThat(closed.get()).isEqualTo(opened.get());
    }

    @Test
    @DisplayName("an unreadable moment is cached for the floor too, and the gauges recover after"
            + " it")
    void anUnreadableMomentRecovers() {
        AtomicInteger attempts = new AtomicInteger();
        new BreakMetrics(
                connection -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("a blip");
                    }
                    return READING;
                },
                this::connection,
                clock,
                registry);

        assertThat(open(BreakType.MISSING_EXTERNAL, Severity.HIGH)).isNaN();
        clock.advance(Duration.ofSeconds(5));
        assertThat(age(Severity.HIGH)).as("still inside the floor").isNaN();
        assertThat(attempts).hasValue(1);

        clock.advance(Duration.ofSeconds(11));
        assertThat(open(BreakType.MISSING_EXTERNAL, Severity.HIGH)).isEqualTo(2.0d);
        assertThat(age(Severity.HIGH))
                .as("aged from the moved clock: 7200s at NOW, 16s later")
                .isEqualTo(7_216.0d);
    }
}
