package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.reconciliation.AgedReadings;
import com.finapp.reconciliation.Severity;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The break-age counts (`P16-TSK-029`, {@code THREAT_MODEL.md} §6 item 42 (a)): every series eager; a break or a suspense item is
 * counted exactly when the age gauge its rule reads is past that rule's threshold, the second either side of the bound included; the
 * bounds are the committed rules' own thresholds, read from the rule file; an unreadable database reads NaN on every series; one
 * reading per refresh floor.
 */
@DisplayName("the break-age counts: what is past its bound, per severity and in suspense (P16-TSK-029)")
class AgedCountMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final String RULES = "infra/prometheus/rules/settlement-reconciliation.yml";

    /** A clock the test moves - the refresh floor and the cutoffs are about time passing. */
    private static final class MovingClock extends Clock {
        private final AtomicReference<Instant> now;

        MovingClock(Instant start) {
            now = new AtomicReference<>(start);
        }

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

    /**
     * Rows held in memory and counted with the statements' own predicates: a break counts when raised at or before its severity's
     * cutoff, a suspense item when opened on or before the suspense cutoff ({@code JdbcAgedReadings}).
     */
    private static final class Rows implements AgedReadings {
        private final Map<Severity, List<Instant>> breaks = new EnumMap<>(Severity.class);
        private final List<LocalDate> suspense = new ArrayList<>();
        private final AtomicInteger reads = new AtomicInteger();
        private Map<Severity, Instant> lastCutoffs;
        private LocalDate lastSuspenseCutoff;

        Rows raised(Severity severity, Instant at) {
            breaks.computeIfAbsent(severity, none -> new ArrayList<>()).add(at);
            return this;
        }

        Rows opened(LocalDate on) {
            suspense.add(on);
            return this;
        }

        @Override
        public Map<Severity, Long> agedBreaks(Connection connection, Map<Severity, Instant> raisedAtOrBefore) {
            reads.incrementAndGet();
            lastCutoffs = Map.copyOf(raisedAtOrBefore);
            Map<Severity, Long> counts = new EnumMap<>(Severity.class);
            breaks.forEach((severity, raises) -> counts.put(severity,
                    raises.stream().filter(raise -> !raise.isAfter(raisedAtOrBefore.get(severity))).count()));
            return counts;
        }

        @Override
        public long agedSuspense(Connection connection, LocalDate openedOnOrBefore) {
            lastSuspenseCutoff = openedOnOrBefore;
            return suspense.stream().filter(opened -> !opened.isAfter(openedOnOrBefore)).count();
        }
    }

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
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

    private double aged(Severity severity) {
        return registry.find(AgedCountMetrics.BREAK_AGED).tag("severity", severity.name().toLowerCase(Locale.ROOT)).gauge().value();
    }

    private double suspense() {
        return registry.find(AgedCountMetrics.SUSPENSE_AGED).gauge().value();
    }

    @Test
    @DisplayName("every severity's count and the suspense count exist eagerly, and read an honest zero when nothing is aged")
    void everySeriesIsEager() {
        new AgedCountMetrics(new Rows(), this::connection, new MovingClock(NOW), registry);

        for (Severity severity : Severity.values()) {
            assertThat(registry.find(AgedCountMetrics.BREAK_AGED).tag("severity", severity.name().toLowerCase(Locale.ROOT)).gauge())
                    .as("break.aged{%s} exists eagerly", severity).isNotNull();
            assertThat(aged(severity)).isZero();
        }
        assertThat(registry.find(AgedCountMetrics.BREAK_AGED).gauges()).hasSize(Severity.values().length);
        assertThat(registry.find(AgedCountMetrics.SUSPENSE_AGED).gauges()).hasSize(1);
        assertThat(suspense()).isZero();
    }

    @Test
    @DisplayName("a break counts once its whole-second age is past its severity's bound, and not a second before; a newly aged one"
            + " behind a standing one raises the count")
    void aBreakCountsExactlyWhenItsAgeRuleWouldHold() {
        Rows rows = new Rows()
                // HIGH, bound one day (BreakAgeHigh: age > 86400): the standing one, one just past, one on the bound.
                .raised(Severity.HIGH, NOW.minus(Duration.ofDays(90)))
                .raised(Severity.HIGH, NOW.minusSeconds(86_401))
                .raised(Severity.HIGH, NOW.minusMillis(86_400_500))
                // CRITICAL, bound nothing (BreakAgeCritical: age > 0): a second old counts, half a second does not.
                .raised(Severity.CRITICAL, NOW.minusSeconds(1))
                .raised(Severity.CRITICAL, NOW.minusMillis(500))
                // MEDIUM, five days; LOW, fifteen.
                .raised(Severity.MEDIUM, NOW.minus(Duration.ofDays(5)))
                .raised(Severity.MEDIUM, NOW.minus(Duration.ofDays(5)).minusSeconds(1))
                .raised(Severity.LOW, NOW.minus(Duration.ofDays(15)).minusSeconds(1))
                .raised(Severity.LOW, NOW.minus(Duration.ofDays(14)));
        new AgedCountMetrics(rows, this::connection, new MovingClock(NOW), registry);

        assertThat(aged(Severity.HIGH)).as("the standing HIGH and the one 86,401 s old; 86,400.5 s ages 86,400 whole seconds, not past")
                .isEqualTo(2.0d);
        assertThat(aged(Severity.CRITICAL)).as("one whole second is past a bound of nothing; half a second ages at zero").isEqualTo(1.0d);
        assertThat(aged(Severity.MEDIUM)).as("exactly five days is not past five days").isEqualTo(1.0d);
        assertThat(aged(Severity.LOW)).isEqualTo(1.0d);
        assertThat(rows.lastCutoffs).as("each severity's cutoff: now less its bound and one second")
                .containsEntry(Severity.CRITICAL, NOW.minusSeconds(1))
                .containsEntry(Severity.HIGH, NOW.minusSeconds(86_401))
                .containsEntry(Severity.MEDIUM, NOW.minusSeconds(5 * 86_400 + 1))
                .containsEntry(Severity.LOW, NOW.minusSeconds(15 * 86_400 + 1))
                .hasSize(Severity.values().length);
    }

    @Test
    @DisplayName("a suspense item counts once its age from midnight UTC of the day it opened is past thirty days, never on the day"
            + " the age reaches thirty days exactly")
    void aSuspenseItemCountsExactlyWhenItsAgeRuleWouldHold() {
        Rows rows = new Rows().opened(LocalDate.parse("2026-08-30")).opened(LocalDate.parse("2026-08-31"));
        new AgedCountMetrics(rows, this::connection, new MovingClock(NOW), registry);
        assertThat(suspense()).as("opened 2026-08-30 is 30 d 12 h old at noon on 2026-09-29; 2026-08-31 is 29 d 12 h").isEqualTo(1.0d);
        assertThat(rows.lastSuspenseCutoff).isEqualTo(LocalDate.parse("2026-08-30"));

        SimpleMeterRegistry atMidnight = new SimpleMeterRegistry();
        Rows edge = new Rows().opened(LocalDate.parse("2026-08-30")).opened(LocalDate.parse("2026-08-29"));
        new AgedCountMetrics(edge, this::connection, new MovingClock(Instant.parse("2026-09-29T00:00:00Z")), atMidnight);
        assertThat(atMidnight.find(AgedCountMetrics.SUSPENSE_AGED).gauge().value())
                .as("at midnight, an item opened 2026-08-30 is exactly 2,592,000 s old - not past - and one a day older is").isEqualTo(1.0d);
        assertThat(edge.lastSuspenseCutoff).isEqualTo(LocalDate.parse("2026-08-29"));
    }

    @Test
    @DisplayName("the bounds are the committed age rules' own thresholds, read from the rule file, and each age rule has its rise rule"
            + " on the count beside it")
    void theBoundsAreTheRulesThresholds() {
        String rules = RepositoryPaths.read(RULES).replace("\r\n", "\n");
        Map<String, Severity> ageRules = Map.of("BreakAgeCritical", Severity.CRITICAL, "BreakAgeHigh", Severity.HIGH,
                "BreakAgeMedium", Severity.MEDIUM, "BreakAgeLow", Severity.LOW);
        ageRules.forEach((alert, severity) -> {
            assertThat(threshold(rules, alert)).as("%s's threshold is the %s bound", alert, severity)
                    .isEqualTo(AgedCountMetrics.BREAK_BOUNDS.get(severity).toSeconds());
            String level = severity.name().toLowerCase(Locale.ROOT);
            assertThat(expression(rules, alert)).as("%s reads the age of %s", alert, level)
                    .contains("finapp_reconciliation_break_age{severity=\"" + level + "\"}");
            String rise = "BreakNewlyAged" + severity.name().charAt(0) + level.substring(1);
            assertThat(expression(rules, rise)).as("%s reads the %s count", rise, level)
                    .contains("finapp_reconciliation_break_aged{severity=\"" + level + "\"}");
        });
        assertThat(threshold(rules, "SuspenseItemAged")).isEqualTo(AgedCountMetrics.SUSPENSE_BOUND.toSeconds());
        assertThat(expression(rules, "SuspenseItemNewlyAged")).contains("finapp_reconciliation_suspense_aged");
        assertThat(AgedCountMetrics.BREAK_BOUNDS).as("a bound for every severity").hasSize(Severity.values().length);
    }

    @Test
    @DisplayName("an unreadable database, or a reading that throws, reads NaN on every series - never a comforting zero - and the"
            + " connection is closed")
    void anUnreadableReadingIsNaN() {
        new AgedCountMetrics(new Rows(), () -> {
            throw new SQLException("nope");
        }, new MovingClock(NOW), registry);
        assertThat(registry.find(AgedCountMetrics.BREAK_AGED).gauges()).isNotEmpty().allSatisfy(gauge -> assertThat(gauge.value()).isNaN());
        assertThat(suspense()).isNaN();

        SimpleMeterRegistry failing = new SimpleMeterRegistry();
        Rows throwing = new Rows() {
            @Override
            public long agedSuspense(Connection connection, LocalDate openedOnOrBefore) {
                throw new IllegalStateException("the count failed");
            }
        };
        new AgedCountMetrics(throwing.raised(Severity.HIGH, NOW.minus(Duration.ofDays(9))), this::connection, new MovingClock(NOW),
                failing);
        assertThat(failing.find(AgedCountMetrics.BREAK_AGED).gauges())
                .as("one reading feeds every series: a failed half is no half-truth").allSatisfy(gauge -> assertThat(gauge.value()).isNaN());
        assertThat(failing.find(AgedCountMetrics.SUSPENSE_AGED).gauge().value()).isNaN();
        assertThat(closed.get()).as("every connection opened is closed").isEqualTo(opened.get());
    }

    @Test
    @DisplayName("one reading per refresh floor serves every series; past the floor it reads again, at the moved clock")
    void theFloorCachesOneReading() {
        MovingClock clock = new MovingClock(NOW);
        Rows rows = new Rows().raised(Severity.HIGH, NOW.minusSeconds(86_390));
        new AgedCountMetrics(rows, this::connection, clock, registry);

        registry.find(AgedCountMetrics.BREAK_AGED).gauges().forEach(Gauge::value);
        registry.find(AgedCountMetrics.SUSPENSE_AGED).gauges().forEach(Gauge::value);
        assertThat(rows.reads).as("one reading for the whole scrape").hasValue(1);
        assertThat(aged(Severity.HIGH)).as("86,390 s: not yet past a day").isZero();

        clock.advance(Duration.ofSeconds(14));
        assertThat(aged(Severity.HIGH)).as("inside the floor, served cached").isZero();
        assertThat(rows.reads).hasValue(1);

        clock.advance(Duration.ofSeconds(2));
        assertThat(aged(Severity.HIGH)).as("16 s later, 86,406 s old: past the bound").isEqualTo(1.0d);
        assertThat(rows.reads).hasValue(2);
        assertThat(closed.get()).isEqualTo(opened.get());
    }

    /** An alert's threshold: the number its expression compares with ">". */
    private static long threshold(String rules, String alert) {
        Matcher number = Pattern.compile("> (\\d+)\\s*$").matcher(expression(rules, alert));
        assertThat(number.find()).as("%s compares with a number", alert).isTrue();
        return Long.parseLong(number.group(1));
    }

    /** An alert's expression, the first {@code expr:} after its name. */
    private static String expression(String rules, String alert) {
        Matcher rule = Pattern.compile("(?m)^\\s*- alert: " + alert + "\\n(?:\\s*#[^\\n]*\\n)*\\s*expr: (?:>-\\n)?([^\\n]*(?:\\n\\s{10,}[^-#\\s][^\\n]*)*)")
                .matcher(rules);
        assertThat(rule.find()).as("the rule file has the alert %s", alert).isTrue();
        return rule.group(1).strip();
    }
}
