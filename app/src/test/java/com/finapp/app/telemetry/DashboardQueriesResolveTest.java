package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.RepositoryPaths;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every query in the committed dashboard names a series the application actually publishes.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A dashboard whose query names a series that does not exist does not fail. It renders, with
 * every panel reading "No data", and looks exactly like a quiet system. During an incident that is
 * the worst possible failure: the operator concludes nothing is wrong.
 *
 * <p>This is not hypothetical. It happened during {@code P0-TSK-029} itself: {@code
 * baseUnit("events")} made Micrometer publish {@code finapp_outbox_pending_events} while the
 * dashboard queried {@code finapp_outbox_pending}, and the endpoint test missed it because a
 * substring assertion is satisfied by the longer name. It was found by looking at a browser. This
 * is that check, automated.
 *
 * <h2>What it deliberately covers</h2>
 *
 * <p>Framework series too — {@code http_server_requests_seconds_count}, {@code
 * hikaricp_connections_active}. Those are not our contract, which is exactly why they are worth
 * checking: a Spring Boot or Micrometer upgrade can rename one, and nothing else in this build
 * would notice until somebody opened the dashboard during an outage.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardQueriesResolveTest {

    private static final String DASHBOARD = "infra/grafana/dashboards/finapp-platform.json";

    /** A PromQL metric selector: an identifier not immediately followed by an opening bracket. */
    private static final Pattern METRIC = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b(?!\\s*\\()");

    /**
     * PromQL tokens that are not series names.
     *
     * <p>Aggregation operators appear here because they are written {@code sum by (outcome) (...)}
     * - followed by a keyword rather than a bracket - so the lookahead above does not exclude
     * them. Functions like {@code rate(} are excluded by the lookahead and need no entry.
     *
     * <p>The parser is deliberately simple, and deliberately errs towards treating an unknown
     * token AS a series: that produces a visible, fixable failure, whereas the other direction
     * silently stops checking whichever query it misparsed - which is the failure this whole class
     * exists to prevent.
     */
    private static final Set<String> NOT_A_SERIES =
            Set.of(
                    // Aggregation operators.
                    "sum", "min", "max", "avg", "group", "stddev", "stdvar", "count",
                    "count_values", "bottomk", "topk", "quantile",
                    // Aggregation modifiers and label names used in this dashboard.
                    "by", "on", "without", "ignoring", "outcome", "le");

    @LocalServerPort private int port;

    @Autowired private MeterRegistry registry;

    /**
     * Both conditions the dashboard's framework panels need, and each was found by this test
     * failing rather than by reading documentation.
     *
     * <p>{@code hikaricp_*} exists only once the pool has actually initialised - with the
     * datasource pointed at a closed port there is no pool and no Hikari metrics, only the
     * generic {@code jdbc_connections_*}. That is why this test needs a real database.
     *
     * <p>{@code http_server_requests_*} is registered when the first request is served, not at
     * startup, so a test that asserted before serving one would report a dashboard error that
     * does not exist.
     */
    @BeforeEach
    void serveOneRequestSoTheHttpTimerExists() throws Exception {
        HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("every series the dashboard queries is one the application publishes")
    void everyQueriedSeriesExists() {
        Set<String> published = publishedSeriesNames();
        assertThat(published).as("the registry must expose series to check against").isNotEmpty();

        List<String> queried = queriedSeriesNames();
        assertThat(queried).as("the dashboard must contain queries").isNotEmpty();

        assertThat(queried)
                .as(
                        """
                        A dashboard panel queries a series the application does not publish. It will \
                        render "No data" and look exactly like a quiet system, which during an \
                        incident is the worst way to be wrong. Published series: %s""",
                        published)
                .allSatisfy(series -> assertThat(published).contains(series));
    }

    @Test
    @DisplayName("the guard is not vacuous: it reads real queries and a real registry")
    void theGuardSeesBothSides() {
        assertThat(queriedSeriesNames())
                .as("the outbox panels are the ones this task added")
                .contains("finapp_outbox_pending", "finapp_outbox_oldest_seconds");
        assertThat(publishedSeriesNames()).contains("finapp_outbox_pending");
    }

    // -----------------------------------------------------------------

    /**
     * Series names as Prometheus publishes them, taken from the registry's own naming convention
     * rather than by translating dots to underscores here. Micrometer appends a base unit to the
     * name, which is what made the original defect invisible - a translation written in this test
     * would have reproduced the same wrong answer.
     */
    private Set<String> publishedSeriesNames() {
        PrometheusMeterRegistry prometheus =
                registry instanceof PrometheusMeterRegistry direct
                        ? direct
                        : (PrometheusMeterRegistry)
                                ((io.micrometer.core.instrument.composite.CompositeMeterRegistry) registry)
                                        .getRegistries().stream()
                                        .filter(PrometheusMeterRegistry.class::isInstance)
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("no Prometheus registry"));

        Set<String> names = new TreeSet<>();
        for (String line : prometheus.scrape().lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int end = line.indexOf('{');
            if (end < 0) {
                end = line.indexOf(' ');
            }
            if (end > 0) {
                names.add(line.substring(0, end));
            }
        }
        return names;
    }

    private static List<String> queriedSeriesNames() {
        JsonNode dashboard = JsonMapper.builder().build().readTree(RepositoryPaths.read(DASHBOARD));
        return dashboard.path("panels").valueStream()
                .flatMap(panel -> panel.path("targets").valueStream())
                .map(target -> target.path("expr").stringValue())
                .filter(expr -> expr != null && !expr.isBlank())
                .flatMap(DashboardQueriesResolveTest::seriesIn)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private static java.util.stream.Stream<String> seriesIn(String expr) {
        Matcher matcher = METRIC.matcher(expr);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!NOT_A_SERIES.contains(candidate)) {
                found.add(candidate);
            }
        }
        return found.stream();
    }
}
