package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sweeps' failure counters (`P16-TSK-017`): every series the rules read exists at zero on a fresh instance, named by the
 * convention, tagged by the sweep alone, and each schedule's handle counts only its own sweep.
 */
@DisplayName("the sweeps' failure counters are eager, well named, tagged by the sweep alone, and each handle counts its own (P16-TSK-017)")
class SweepMetersTest {

    @Test
    @DisplayName("every payments sweep and the payout sweep publish their three counters at zero, before any tick")
    void everySeriesExistsAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new SweepMeters(registry);
        for (String name : List.of(SweepMeters.PAYMENT_TICK_FAILED, SweepMeters.PAYMENT_ROW_UNREADABLE, SweepMeters.PAYMENT_ROW_FAILED)) {
            for (SweepMeters.PaymentSweep sweep : SweepMeters.PaymentSweep.values()) {
                assertThat(registry.get(name).tag("leg", sweep.name().toLowerCase(Locale.ROOT)).counter().count())
                        .as("%s{leg=%s}", name, sweep).isZero();
            }
        }
        for (String name : List.of(SweepMeters.PAYOUT_TICK_FAILED, SweepMeters.PAYOUT_ROW_UNREADABLE, SweepMeters.PAYOUT_ROW_FAILED)) {
            assertThat(registry.get(name).counter().count()).as(name).isZero();
        }
        List<Meter> meters = registry.getMeters();
        assertThat(meters).hasSize(3 * SweepMeters.PaymentSweep.values().length + 3);
        assertThat(meters).allSatisfy(meter -> {
            assertThat(MetricNames.isWellFormed(meter.getId().getName())).as(meter.getId().getName()).isTrue();
            assertThat(meter.getId().getTags()).as("no identifier, no amount: the sweep's own name at most (ADR-0072)")
                    .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("leg"));
        });
        assertThat(Stream.of(SweepMeters.PaymentSweep.values()).map(sweep -> sweep.name().toLowerCase(Locale.ROOT)))
                .containsExactly("card", "withdrawal", "dispute_answer", "return", "pay_in");
    }

    @Test
    @DisplayName("a handle counts its own sweep's tick and rows, and a tally of nothing counts nothing")
    void aHandleCountsItsOwnSweep() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SweepMeters meters = new SweepMeters(registry);
        SweepMeters.Sweep returns = meters.payments(SweepMeters.PaymentSweep.RETURN);
        returns.tickFailed();
        returns.rows(2, 3);
        returns.rows(0, 0);
        meters.payouts().rows(1, 0);
        assertThat(count(registry, SweepMeters.PAYMENT_TICK_FAILED, "return")).isEqualTo(1.0);
        assertThat(count(registry, SweepMeters.PAYMENT_ROW_UNREADABLE, "return")).isEqualTo(2.0);
        assertThat(count(registry, SweepMeters.PAYMENT_ROW_FAILED, "return")).isEqualTo(3.0);
        assertThat(count(registry, SweepMeters.PAYMENT_ROW_FAILED, "card")).isZero();
        assertThat(registry.get(SweepMeters.PAYOUT_ROW_UNREADABLE).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(SweepMeters.PAYOUT_TICK_FAILED).counter().count()).isZero();
    }

    private static double count(SimpleMeterRegistry registry, String name, String leg) {
        Counter counter = registry.get(name).tag("leg", leg).counter();
        return counter.count();
    }
}
