package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The 1 s boundary, on the routes that carry a latency objective and on no other (`P15-TSK-025`). */
@DisplayName("the SLO routes publish a 1 s bucket, and no other route publishes any (P15-TSK-025)")
class SloLatencyBucketsTest {

    @Test
    @DisplayName("an SLO route publishes le=\"1.0\", the label the recording rules select")
    void anSloRouteHasTheBoundary() {
        PrometheusMeterRegistry registry = registry();
        timer(registry, "/v1/transfers").record(Duration.ofMillis(200));
        timer(registry, "/v1/transfers").record(Duration.ofSeconds(3));

        assertThat(registry.scrape()).contains("http_server_requests_seconds_bucket{")
                .containsPattern("http_server_requests_seconds_bucket\\{[^}]*le=\"1.0\"[^}]*} 1\\b");
    }

    @Test
    @DisplayName("any other route publishes no bucket, so the other routes' cardinality is untouched")
    void anyOtherRouteHasNone() {
        PrometheusMeterRegistry registry = registry();
        timer(registry, "/v1/me/consents").record(Duration.ofMillis(200));

        assertThat(registry.scrape()).doesNotContain("http_server_requests_seconds_bucket");
    }

    private static PrometheusMeterRegistry registry() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().meterFilter(new SloLatencyBuckets());
        return registry;
    }

    private static Timer timer(PrometheusMeterRegistry registry, String uri) {
        return Timer.builder("http.server.requests").tag("uri", uri).tag("method", "POST").tag("status", "201").register(registry);
    }
}
