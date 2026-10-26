package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.CensusKind;
import com.finapp.risk.ProofVerdict;
import com.finapp.risk.RiskOperationsReports;
import com.finapp.risk.VelocityProof;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proofs' gauges (`P13-TSK-023`; {@code PHASE_13_PLAN.md} section 15; {@code DESIGN-023} D10; the review's I1):
 * registered eagerly for every verdict and census kind, with the readings' outcome counter; 1 on the reading's verdict
 * and 0 on the others; NaN - never "clean" - when the reading fails or grows stale; one reading per refresh floor,
 * however often scraped, <strong>started off the scrape's thread, single-flight</strong> - the scrape answers the last
 * reading meanwhile; a floor below a minute refused. No schedule, no system actor.
 */
@DisplayName("the risk proofs' gauges: eager, 1 on the reading's verdict, NaN when unreadable or stale, behind a floor,"
        + " single-flight off the scrape thread (P13-TSK-023)")
class RiskProofMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<RiskOperationsReports.Reading> next = new AtomicReference<>();
    private final AtomicInteger readings = new AtomicInteger();
    private final MutableClock clock = new MutableClock();
    /** The refreshes handed to the refresher and not yet run - a refresher the suite runs by hand. */
    private final List<Runnable> queued = new ArrayList<>();

    @Test
    @DisplayName("every series is registered before any reading: three velocity verdicts, five census kinds by three"
            + " verdicts, the readings completed and failed")
    void registeredEagerly() {
        metrics(RiskProofMetrics.DEFAULT_REFRESH, Runnable::run);
        assertThat(registry.find(RiskProofMetrics.VELOCITY).gauges()).hasSize(VelocityProof.Verdict.values().length);
        assertThat(registry.find(RiskProofMetrics.CENSUS).gauges())
                .hasSize(CensusKind.values().length * ProofVerdict.values().length);
        assertThat(registry.find(RiskProofMetrics.READING).counters()).hasSize(2);
        assertThat(readings).as("registration reads nothing").hasValue(0);
    }

    @Test
    @DisplayName("1 on the verdict the reading reached and 0 on the others; a diverged census alone shows DIVERGED")
    void oneOnTheReadingsVerdict() {
        RiskProofMetrics metrics = metrics(RiskProofMetrics.DEFAULT_REFRESH, Runnable::run);
        Map<CensusKind, ProofVerdict> censuses = held();
        censuses.put(CensusKind.MONITORING, ProofVerdict.DIVERGED);
        next.set(new RiskOperationsReports.Reading(NOW, VelocityProof.Verdict.HELD, censuses));

        assertThat(velocity(VelocityProof.Verdict.HELD)).isEqualTo(1.0);
        assertThat(velocity(VelocityProof.Verdict.DIVERGED)).isEqualTo(0.0);
        assertThat(census(CensusKind.MONITORING, ProofVerdict.DIVERGED)).isEqualTo(1.0);
        assertThat(census(CensusKind.MONITORING, ProofVerdict.HELD)).isEqualTo(0.0);
        assertThat(census(CensusKind.ASSESSMENT, ProofVerdict.HELD)).isEqualTo(1.0);
        assertThat(readings).as("one reading answered every gauge").hasValue(1);
        assertThat(outcome(RiskProofMetrics.COMPLETED)).isEqualTo(1.0);
        assertThat(metrics.readNow().censuses()).isEqualTo(censuses);
    }

    @Test
    @DisplayName("an unreadable reading is NaN on every gauge, never clean, and counted failed; the next reading after"
            + " the floor recovers")
    void unreadableIsNaN() {
        metrics(Duration.ofMinutes(1), Runnable::run);
        next.set(null);
        assertThat(velocity(VelocityProof.Verdict.HELD)).isNaN();
        assertThat(census(CensusKind.FILING, ProofVerdict.HELD)).isNaN();
        assertThat(outcome(RiskProofMetrics.FAILED)).isEqualTo(1.0);

        next.set(new RiskOperationsReports.Reading(NOW, VelocityProof.Verdict.DIVERGED, held()));
        assertThat(velocity(VelocityProof.Verdict.DIVERGED)).as("still behind the floor").isNaN();
        clock.now = NOW.plus(Duration.ofMinutes(2));
        assertThat(velocity(VelocityProof.Verdict.DIVERGED)).isEqualTo(1.0);
        assertThat(outcome(RiskProofMetrics.COMPLETED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("one reading per floor however often scraped; a floor below a minute is refused")
    void behindTheFloor() {
        metrics(RiskProofMetrics.DEFAULT_REFRESH, Runnable::run);
        next.set(new RiskOperationsReports.Reading(NOW, VelocityProof.Verdict.VACUOUS, held()));
        for (int i = 0; i < 10; i++) {
            velocity(VelocityProof.Verdict.VACUOUS);
        }
        assertThat(readings).hasValue(1);
        clock.now = NOW.plus(RiskProofMetrics.DEFAULT_REFRESH);
        velocity(VelocityProof.Verdict.VACUOUS);
        assertThat(readings).hasValue(2);
        assertThatThrownBy(() -> new RiskProofMetrics(next::get, Runnable::run, clock, registry,
                Duration.ofSeconds(59))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a scrape never reads: past the floor it starts ONE reading on the refresher and answers the last one"
            + " meanwhile - ten scrapes of a slow reading start one; the reading lands for the next scrape")
    void singleFlightOffTheScrapeThread() {
        metrics(RiskProofMetrics.DEFAULT_REFRESH, queued::add);
        next.set(new RiskOperationsReports.Reading(NOW, VelocityProof.Verdict.HELD, held()));
        for (int i = 0; i < 10; i++) {
            assertThat(velocity(VelocityProof.Verdict.HELD)).as("no reading yet - absent, never clean").isNaN();
        }
        assertThat(queued).as("one reading started however often scraped").hasSize(1);
        assertThat(readings).as("the scrape's thread read nothing").hasValue(0);
        runQueued();
        assertThat(velocity(VelocityProof.Verdict.HELD)).isEqualTo(1.0);
        assertThat(queued).isEmpty();

        clock.now = NOW.plus(RiskProofMetrics.DEFAULT_REFRESH);
        next.set(new RiskOperationsReports.Reading(clock.now, VelocityProof.Verdict.DIVERGED, held()));
        for (int i = 0; i < 10; i++) {
            assertThat(velocity(VelocityProof.Verdict.HELD)).as("the last reading served while the next runs")
                    .isEqualTo(1.0);
        }
        assertThat(queued).hasSize(1);
        runQueued();
        assertThat(velocity(VelocityProof.Verdict.DIVERGED)).isEqualTo(1.0);
        assertThat(readings).hasValue(2);
    }

    @Test
    @DisplayName("a reading older than two floors is NaN - a refresher that never finished cannot keep an old HELD"
            + " on the gauge; a refresher that refuses is counted failed and tried again at the next scrape")
    void staleIsAbsent() {
        RiskProofMetrics metrics = metrics(RiskProofMetrics.DEFAULT_REFRESH, queued::add);
        next.set(new RiskOperationsReports.Reading(NOW, VelocityProof.Verdict.HELD, held()));
        velocity(VelocityProof.Verdict.HELD);
        runQueued();
        assertThat(velocity(VelocityProof.Verdict.HELD)).isEqualTo(1.0);

        clock.now = NOW.plus(RiskProofMetrics.DEFAULT_REFRESH);
        assertThat(velocity(VelocityProof.Verdict.HELD)).isEqualTo(1.0);
        assertThat(queued).as("the refresh started - and, here, never finishes").hasSize(1);
        clock.now = NOW.plus(metrics.staleAfter());
        assertThat(velocity(VelocityProof.Verdict.HELD)).as("stale beyond two floors").isNaN();
        assertThat(queued).as("still one in flight - nothing stacks").hasSize(1);

        SimpleMeterRegistry refusing = new SimpleMeterRegistry();
        AtomicInteger offered = new AtomicInteger();
        Executor full = reading -> {
            offered.incrementAndGet();
            throw new RejectedExecutionException("full");
        };
        new RiskProofMetrics(next::get, full, clock, refusing, RiskProofMetrics.DEFAULT_REFRESH);
        Gauge gauge = refusing.find(RiskProofMetrics.VELOCITY).tag("verdict", "HELD").gauge();
        assertThat(gauge.value()).isNaN();
        assertThat(gauge.value()).isNaN();
        assertThat(offered).as("the flag released: each scrape tries again").hasValue(2);
        assertThat(refusing.find(RiskProofMetrics.READING).tag("outcome", RiskProofMetrics.FAILED).counter().count())
                .isEqualTo(2.0);
    }

    // ------------------------------------------------------------------

    private RiskProofMetrics metrics(Duration refresh, Executor refresher) {
        return new RiskProofMetrics(() -> {
            readings.incrementAndGet();
            RiskOperationsReports.Reading reading = next.get();
            if (reading == null) {
                throw new IllegalStateException("the snapshot could not be taken");
            }
            return reading;
        }, refresher, clock, registry, refresh);
    }

    private void runQueued() {
        List<Runnable> running = new ArrayList<>(queued);
        queued.clear();
        running.forEach(Runnable::run);
    }

    private double velocity(VelocityProof.Verdict verdict) {
        Gauge gauge = registry.find(RiskProofMetrics.VELOCITY).tag("verdict", verdict.name()).gauge();
        return gauge.value();
    }

    private double census(CensusKind kind, ProofVerdict verdict) {
        return registry.find(RiskProofMetrics.CENSUS).tag("kind", kind.name()).tag("verdict", verdict.name()).gauge()
                .value();
    }

    private double outcome(String outcome) {
        return registry.find(RiskProofMetrics.READING).tag("outcome", outcome).counter().count();
    }

    private static Map<CensusKind, ProofVerdict> held() {
        Map<CensusKind, ProofVerdict> censuses = new EnumMap<>(CensusKind.class);
        for (CensusKind kind : CensusKind.values()) {
            censuses.put(kind, ProofVerdict.HELD);
        }
        return censuses;
    }

    /** A clock the suite moves. */
    private static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
