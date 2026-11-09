package com.finapp.app.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the doors' meter: a closed set of series, present before the first request (P15-TSK-022)")
class SourceLimitMetersTest {

    @Test
    @DisplayName("every door has every outcome at zero from the start, and twenty-five series is all there is")
    void theSeriesAreEagerAndClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new SourceLimitMeters(registry);

        assertThat(registry.find("finapp.abuse.requests").counters()).hasSize(Door.values().length * 5);
        for (Door door : Door.values()) {
            for (String outcome : new String[] {"admitted", "refused", "at_capacity", "store_unreachable", "store_error"}) {
                assertThat(registry.get("finapp.abuse.requests").tag("type", door.label()).tag("outcome", outcome).counter().count()).isZero();
            }
        }
    }

    @Test
    @DisplayName("a recorded outcome moves its own series and no other")
    void anOutcomeMovesOneSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SourceLimitMeters meters = new SourceLimitMeters(registry);

        meters.record(Door.RECOVERY, SourceLimitMeters.Outcome.REFUSED);

        assertThat(registry.get("finapp.abuse.requests").tag("type", "recovery").tag("outcome", "refused").counter().count()).isEqualTo(1);
        assertThat(registry.get("finapp.abuse.requests").tag("type", "recovery").tag("outcome", "admitted").counter().count()).isZero();
        assertThat(registry.get("finapp.abuse.requests").tag("type", "registration").tag("outcome", "refused").counter().count()).isZero();
    }
}
