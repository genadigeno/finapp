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
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proofs' gauges (`P13-TSK-023`; {@code PHASE_13_PLAN.md} section 15; {@code DESIGN-023} D10): registered eagerly for
 * every verdict and census kind; 1 on the reading's verdict and 0 on the others; NaN - never "clean" - when the reading
 * fails; one reading per refresh floor, however often scraped, and a floor below a minute refused. No schedule, no
 * system actor: a scrape reads.
 */
@DisplayName("the risk proofs' gauges: eager, 1 on the reading's verdict, NaN when unreadable, behind a floor"
        + " (P13-TSK-023)")
class RiskProofMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<RiskOperationsReports.Reading> next = new AtomicReference<>();
    private final AtomicInteger readings = new AtomicInteger();
    private final MutableClock clock = new MutableClock();

    @Test
    @DisplayName("every series is registered before any reading: three velocity verdicts, five census kinds by three"
            + " verdicts")
    void registeredEagerly() {
        metrics(RiskProofMetrics.DEFAULT_REFRESH);
        assertThat(registry.find(RiskProofMetrics.VELOCITY).gauges()).hasSize(VelocityProof.Verdict.values().length);
        assertThat(registry.find(RiskProofMetrics.CENSUS).gauges())
                .hasSize(CensusKind.values().length * ProofVerdict.values().length);
        assertThat(readings).as("registration reads nothing").hasValue(0);
    }

    @Test
    @DisplayName("1 on the verdict the reading reached and 0 on the others; a diverged census alone shows DIVERGED")
    void oneOnTheReadingsVerdict() {
        RiskProofMetrics metrics = metrics(RiskProofMetrics.DEFAULT_REFRESH);
        Map<CensusKind, ProofVerdict> censuses = held();
        censuses.put(CensusKind.MONITORING, ProofVerdict.DIVERGED);
        next.set(new RiskOperationsReports.Reading(NOW, VelocityProof.Verdict.HELD, censuses));

        assertThat(velocity(VelocityProof.Verdict.HELD)).isEqualTo(1.0);
        assertThat(velocity(VelocityProof.Verdict.DIVERGED)).isEqualTo(0.0);
        assertThat(census(CensusKind.MONITORING, ProofVerdict.DIVERGED)).isEqualTo(1.0);
        assertThat(census(CensusKind.MONITORING, ProofVerdict.HELD)).isEqualTo(0.0);
        assertThat(census(CensusKind.ASSESSMENT, ProofVerdict.HELD)).isEqualTo(1.0);
        assertThat(readings).as("one reading answered every gauge").hasValue(1);
        assertThat(metrics.readNow().censuses()).isEqualTo(censuses);
    }

    @Test
    @DisplayName("an unreadable reading is NaN on every gauge, never clean; the next reading after the floor recovers")
    void unreadableIsNaN() {
        metrics(Duration.ofMinutes(1));
        next.set(null);
        assertThat(velocity(VelocityProof.Verdict.HELD)).isNaN();
        assertThat(census(CensusKind.FILING, ProofVerdict.HELD)).isNaN();

        next.set(new RiskOperationsReports.Reading(NOW, VelocityProof.Verdict.DIVERGED, held()));
        assertThat(velocity(VelocityProof.Verdict.DIVERGED)).as("still behind the floor").isNaN();
        clock.now = NOW.plus(Duration.ofMinutes(2));
        assertThat(velocity(VelocityProof.Verdict.DIVERGED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("one reading per floor however often scraped; a floor below a minute is refused")
    void behindTheFloor() {
        metrics(RiskProofMetrics.DEFAULT_REFRESH);
        next.set(new RiskOperationsReports.Reading(NOW, VelocityProof.Verdict.VACUOUS, held()));
        for (int i = 0; i < 10; i++) {
            velocity(VelocityProof.Verdict.VACUOUS);
        }
        assertThat(readings).hasValue(1);
        clock.now = NOW.plus(RiskProofMetrics.DEFAULT_REFRESH);
        velocity(VelocityProof.Verdict.VACUOUS);
        assertThat(readings).hasValue(2);
        assertThatThrownBy(() -> new RiskProofMetrics(next::get, clock, registry, Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------

    private RiskProofMetrics metrics(Duration refresh) {
        return new RiskProofMetrics(() -> {
            readings.incrementAndGet();
            RiskOperationsReports.Reading reading = next.get();
            if (reading == null) {
                throw new IllegalStateException("the snapshot could not be taken");
            }
            return reading;
        }, clock, registry, refresh);
    }

    private double velocity(VelocityProof.Verdict verdict) {
        Gauge gauge = registry.find(RiskProofMetrics.VELOCITY).tag("verdict", verdict.name()).gauge();
        return gauge.value();
    }

    private double census(CensusKind kind, ProofVerdict verdict) {
        return registry.find(RiskProofMetrics.CENSUS).tag("kind", kind.name()).tag("verdict", verdict.name()).gauge()
                .value();
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
