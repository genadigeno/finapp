package com.finapp.app.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import java.util.Set;

/**
 * One latency boundary on the routes that carry a latency objective (`P15-TSK-025`; {@code docs/architecture/SLOS.md}).
 *
 * <p>{@code http.server.requests} publishes no buckets by default, so "99% within a second" could not be asked of it. A
 * property would add them to all 397 routes; this adds one boundary to the routes the objectives name, and leaves every
 * other route's cardinality alone. {@code slo-recording.yml} names the same routes, and {@code SloRulesResolveTest} holds the
 * two equal and each route mapped.
 */
final class SloLatencyBuckets implements MeterFilter {

    /** The objective's boundary, published as {@code le="1.0"}. */
    static final Duration OBJECTIVE = Duration.ofSeconds(1);

    private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

    /** The authentication and session routes, and the transfer command's. */
    static final Set<String> ROUTES = Set.of(
            "/v1/authentications",
            "/v1/authentications/mfa",
            "/v1/sessions",
            "/v1/sessions/current",
            "/v1/sessions/{id}",
            "/v1/transfers");

    @Override
    public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
        if (!HTTP_SERVER_REQUESTS.equals(id.getName()) || !ROUTES.contains(id.getTag("uri"))) {
            return config;
        }
        return DistributionStatisticConfig.builder()
                .serviceLevelObjectives((double) OBJECTIVE.toNanos())
                .build()
                .merge(config);
    }
}
