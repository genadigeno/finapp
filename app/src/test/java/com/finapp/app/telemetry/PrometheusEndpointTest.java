package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The scrape endpoint serves what a Prometheus server needs, and nothing a request can influence.
 *
 * <p>This endpoint is a deliberate widening of an unauthenticated surface that {@code P0-TSK-027}
 * had narrowed to two. What makes it acceptable is that the CONTENT is controlled - which is a
 * claim, and this is where it is checked over real HTTP rather than against the registry.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@Tag("slice")
class PrometheusEndpointTest {

    @LocalServerPort private int port;

    @Test
    @DisplayName("the scrape endpoint serves the platform's own series")
    void theScrapeEndpointServesOurSeries() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        // Micrometer renders dots as underscores for Prometheus. Asserting the translated form is
        // the point: it is what an alert rule is written against, and it is the name an operator
        // types. A test on the Java-side name would pass while the published name was anything.
        // Whole lines, not substrings. The substring form passed while the published name was
        // actually finapp_outbox_pending_events - Micrometer appends a baseUnit to the Prometheus
        // name - so the test was green against a series no dashboard queried.
        assertThat(seriesLine(response.body(), "finapp_outbox_pending"))
                .as("the exact series a dashboard queries")
                .isNotNull();
        assertThat(seriesLine(response.body(), "finapp_outbox_oldest_seconds")).isNotNull();
    }

    @Test
    @DisplayName("an unreachable database is reported as absent, never as a comforting zero")
    void anUnreadableBacklogIsAbsentNotZero() throws Exception {
        // The datasource points at a closed port, so the backlog cannot be read. Reporting 0
        // would say "the outbox is empty and all is well" at the moment nothing can be known, and
        // an alert written on `== 0` would stay silent through the outage. Prometheus renders NaN
        // for a gauge with no value, and NaN is alertable.
        String body = get("/actuator/prometheus").body();

        String pending = seriesLine(body, "finapp_outbox_pending");
        assertThat(pending).as("the series must be published even when it cannot be read").isNotNull();
        assertThat(pending).endsWith("NaN");
    }

    @Test
    @DisplayName("the scrape carries no value a request could have influenced")
    void theScrapeLeaksNothingRequestScoped() throws Exception {
        // Drive a request first, so anything that would capture per-request detail has had the
        // chance to. A correlation identifier or a request path as a label would be both a
        // cardinality explosion and an INV-AUD-02 disclosure with months of retention.
        get("/actuator/health/liveness");
        String body = get("/actuator/prometheus").body();

        for (String forbidden : new String[] {"correlation", "traceid", "trace_id", "jdbc:", "127.0.0.1"}) {
            assertThat(body.toLowerCase(java.util.Locale.ROOT))
                    .as("a scrape must not carry %s", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    private static String seriesLine(String body, String name) {
        // The series name must be the WHOLE token before the value or the labels, so a longer
        // name is not mistaken for this one.
        return body.lines()
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.equals(name) || line.startsWith(name + " ") || line.startsWith(name + "{"))
                .findFirst()
                .orElse(null);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
