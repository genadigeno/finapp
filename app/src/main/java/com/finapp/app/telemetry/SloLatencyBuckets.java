package com.finapp.app.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import java.util.Set;

/**
 * One latency boundary on every {@code http.server.requests} timer (`P15-TSK-025`, widened by `P15-TSK-012`; {@code docs/architecture/SLOS.md}).
 *
 * <p>{@code http.server.requests} publishes no buckets by default, so "99% within a second" could not be asked of it.
 * The boundary covers the whole meter name, not the routes the objectives name: under Prometheus one metric family has
 * one type, and a timer with buckets (a histogram) beside a timer without them (a summary) under the same name made
 * every scrape answer 500 with a {@code ClassCastException} on the first instance serving both kinds of route, which is
 * what a rehearsal under traffic found. {@link #ROUTES} are the routes {@code slo-recording.yml} reads, held equal to
 * it by {@code SloRulesResolveTest}.
 */
final class SloLatencyBuckets implements MeterFilter {

    /** The objective's boundary, published as {@code le="1.0"}. */
    static final Duration OBJECTIVE = Duration.ofSeconds(1);

    private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

    /** The routes the latency objectives read: the authentication and session routes, and the transfer command's. */
    static final Set<String> ROUTES = Set.of(
            "/v1/authentications",
            "/v1/authentications/mfa",
            "/v1/sessions",
            "/v1/sessions/current",
            "/v1/sessions/{id}",
            "/v1/transfers");

    @Override
    public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
        if (!HTTP_SERVER_REQUESTS.equals(id.getName())) {
            return config;
        }
        return DistributionStatisticConfig.builder()
                .serviceLevelObjectives((double) OBJECTIVE.toNanos())
                .build()
                .merge(config);
    }
}
