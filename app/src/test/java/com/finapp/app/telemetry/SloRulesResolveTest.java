package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The series and routes the new rules read are real (`P15-TSK-025`). A rule over a name nobody publishes is silent for ever, so
 * this asks the live registry, as {@code AlertRulesResolveTest} does for the domain files; and a route selector matching no
 * route burns nothing, so it asks the handler mapping too. Hermetic: pointed at a database that is not there, as
 * {@code MetricConventionTest} is, because a gauge exists whether or not it can be read.
 */
@Tag("slice")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("the SLO, correctness, flow and platform rules read series and routes that exist (P15-TSK-025)")
class SloRulesResolveTest {

    private static final List<String> FILES = List.of("slo-recording.yml", "correctness.yml", "flows.yml", "platform.yml");

    /** {@code name{...}}, a series with its selector. The recording rules' {@code finapp:} names are not series a scrape holds. */
    private static final Pattern SELECTOR = Pattern.compile("\\b(finapp_[a-z0-9_]+)(\\{[^}]*})?");

    /** What this context does not publish, each for a stated reason. */
    private static final Set<String> ELSEWHERE = Set.of(
            // A consumer loop's gauge: consumers are off in the test contexts; InboxConsumersTest holds its registration.
            "finapp_inbox_lag");

    @Autowired private PrometheusMeterRegistry registry;

    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlers;

    @Test
    @DisplayName("every finapp series the rules read is published, with the labels they select on")
    void everySeriesIsPublished() {
        String scrape = registry.scrape();
        Map<String, String> asked = new TreeMap<>();
        for (String file : FILES) {
            String rules = RepositoryPaths.read("infra/prometheus/rules/" + file);
            for (String line : rules.split("\n")) {
                String stripped = line.strip();
                if (!stripped.startsWith("expr:")) {
                    continue;
                }
                Matcher selector = SELECTOR.matcher(stripped);
                while (selector.find()) {
                    asked.merge(selector.group(1), selector.group(2) == null ? "" : selector.group(2), (a, b) -> a + b);
                }
            }
        }
        assertThat(asked).as("not vacuous").containsKeys("finapp_ledger_trial_balance", "finapp_outbox_deadlettered", "finapp_transfers_conflict_total");
        asked.forEach((series, selectors) -> {
            if (ELSEWHERE.contains(series)) {
                return;
            }
            List<String> lines = scrape.lines().filter(line -> line.startsWith(series + "{") || line.startsWith(series + " ")).toList();
            assertThat(lines).as("%s is published", series).isNotEmpty();
            Matcher label = Pattern.compile("([a-z_]+)=\"([^\"]*)\"").matcher(selectors);
            while (label.find()) {
                String pair = label.group(1) + "=\"" + label.group(2) + "\"";
                assertThat(lines).as("%s has a series with %s", series, pair).anySatisfy(line -> assertThat(line).contains(pair));
            }
        });
    }

    @Test
    @DisplayName("every route the SLIs select exists, the latency SLIs select exactly the routes that carry the 1 s boundary")
    void everyRouteTheSlisSelectExists() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingInfo info : handlers.getHandlerMethods().keySet()) {
            routes.addAll(info.getPatternValues());
        }
        assertThat(routes).as("the handler mapping is read").contains("/v1/transfers");

        String recording = RepositoryPaths.read("infra/prometheus/rules/slo-recording.yml");
        Set<String> latencyRoutes = new TreeSet<>();
        Matcher exact = Pattern.compile("uri=\"([^\"]+)\"").matcher(recording);
        List<String> selectors = new ArrayList<>();
        while (exact.find()) {
            assertThat(routes).as("the route %s exists", exact.group(1)).contains(exact.group(1));
        }
        Matcher regex = Pattern.compile("uri=~\"([^\"]+)\"").matcher(recording);
        while (regex.find()) {
            selectors.add(regex.group(1));
        }
        assertThat(selectors).as("not vacuous").isNotEmpty();
        for (String selector : selectors) {
            Pattern pattern = Pattern.compile(selector.replace("\\\\", "\\"));
            assertThat(routes).as("the selector %s matches a route", selector).anySatisfy(route -> assertThat(pattern.matcher(route).matches()).isTrue());
        }
        for (String line : recording.split("\n")) {
            if (line.contains("le=\"1.0\"")) {
                Matcher uri = Pattern.compile("uri=(~?)\"([^\"]+)\"").matcher(line);
                assertThat(uri.find()).isTrue();
                Pattern matching = uri.group(1).isEmpty() ? Pattern.compile(Pattern.quote(uri.group(2))) : Pattern.compile(uri.group(2).replace("\\\\", "\\"));
                routes.stream().filter(route -> matching.matcher(route).matches()).forEach(latencyRoutes::add);
            }
        }
        assertThat(latencyRoutes).as("the buckets are where the SLIs read them, and nowhere else").isEqualTo(new TreeSet<>(SloLatencyBuckets.ROUTES));
    }
}
