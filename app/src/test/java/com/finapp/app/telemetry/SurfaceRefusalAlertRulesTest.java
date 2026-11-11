package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.surface.Surface;
import com.finapp.app.surface.SurfaceDoor;
import com.finapp.app.surface.SurfaceRefusalMeters;
import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ByteArrayResource;

/**
 * A page on any proven refusal and a ticket on any unmarked one (`P15-TSK-043`; {@code THREAT_MODEL.md} §6 item 39 (b), T3, T31),
 * as written, over the series {@link SurfaceRefusalMeters} registers, each with its runbook section and its promtool cases both
 * ways. <strong>Whether they fire is {@code promtool}'s</strong>: {@code infra/prometheus/tests/identity-surface.test.yml}, through
 * the pinned Prometheus image ({@code infra/scripts/alert-rules-check.sh}, CI's {@code alerts} job). This holds what the build can
 * without one: a rule removed, demoted, unlinked, or reading a series or a label the meter never publishes is red here.
 */
@DisplayName("a page on any proven refusal, a ticket on any unmarked one: as written, over the meter's own series (P15-TSK-043)")
class SurfaceRefusalAlertRulesTest {

    private static final String RULES = "infra/prometheus/rules/flows.yml";
    private static final String CASES = "infra/prometheus/tests/identity-surface.test.yml";
    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";
    private static final String PAGE = "OperatorPasswordProvenOnPublic";
    private static final String TICKET = "StaffRefusedUnmarked";
    private static final String SERIES = "finapp_identity_surface_refusal_total";

    @Test
    @DisplayName("the page: any proven refusal on any instance, by kind, in ten minutes - or a replica first seen already counting one -"
            + " at page, waiting for nothing")
    void thePageIsAsWritten() {
        Map<String, Object> rule = rule(PAGE);
        String proven = SERIES + "{outcome=\"credential_proven\"}";
        assertThat(String.valueOf(rule.get("expr")).strip()).isEqualTo(
                "sum by (kind) (increase(" + proven + "[10m])) > 0 or sum by (kind) (" + proven + " > 0 unless " + proven
                        + " offset 10m) > 0");
        assertThat(severity(rule)).isEqualTo("page");
        assertThat(String.valueOf(rule.get("for"))).isEqualTo("0s");
    }

    @Test
    @DisplayName("the ticket: any unmarked refusal at any door on any instance in ten minutes - or a replica first seen already"
            + " counting one - at ticket, waiting for nothing")
    void theTicketIsAsWritten() {
        Map<String, Object> rule = rule(TICKET);
        String unmarked = SERIES + "{kind=\"unmarked\"}";
        assertThat(String.valueOf(rule.get("expr")).strip()).isEqualTo(
                "sum(increase(" + unmarked + "[10m])) > 0 or sum(" + unmarked + " > 0 unless " + unmarked + " offset 10m) > 0");
        assertThat(severity(rule)).isEqualTo("ticket");
        assertThat(String.valueOf(rule.get("for"))).isEqualTo("0s");
    }

    @Test
    @DisplayName("each rule reads the series the meter publishes, with the label values it selects: the page's at sign-in alone,"
            + " the ticket's at every door")
    void eachReadsTheMetersOwnSeries() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new SurfaceRefusalMeters(registry);
        List<String> published = registry.scrape().lines().filter(line -> line.startsWith(SERIES + "{")).toList();
        assertThat(published).as("eagerly registered: every door, both refusing surfaces").hasSize(18);

        for (String alert : List.of(PAGE, TICKET)) {
            Matcher selector = Pattern.compile(Pattern.quote(SERIES) + "\\{([a-z_]+)=\"([a-z_]+)\"}")
                    .matcher(String.valueOf(rule(alert).get("expr")));
            List<String> pairs = new ArrayList<>();
            while (selector.find()) {
                pairs.add(selector.group(1) + "=\"" + selector.group(2) + "\"");
            }
            assertThat(pairs).as("%s selects its series by one label, the same each time", alert).hasSize(3).containsOnly(pairs.get(0));
            assertThat(published).as("%s's %s is published", alert, pairs.get(0)).anySatisfy(line -> assertThat(line).contains(pairs.get(0)));
        }
        assertThat(published.stream().filter(line -> line.contains("outcome=\"credential_proven\"")))
                .as("the page's series: sign-in alone, on both refusing surfaces")
                .hasSize(2).allSatisfy(line -> assertThat(line).contains("type=\"" + SurfaceDoor.AUTHENTICATION.tag() + "\""));
        assertThat(published.stream().filter(line -> line.contains("kind=\"" + Surface.UNMARKED.tag() + "\"")))
                .as("the ticket's series: every door's").hasSize(9);
    }

    @Test
    @DisplayName("each alert names OPERATIONS_RUNBOOK.md section 11.37, which names it, and has promtool cases that fire it and"
            + " cases that keep it silent")
    void eachIsLinkedAndTestedBothWays() {
        String runbook = RepositoryPaths.read(RUNBOOK);
        int at = runbook.indexOf("\n### 11.37 ");
        assertThat(at).as("section 11.37 exists").isPositive();
        int next = runbook.indexOf("\n### ", at + 1);
        String section = runbook.substring(at, next < 0 ? runbook.length() : next);
        String cases = RepositoryPaths.read(CASES).replace("\r\n", "\n");
        assertThat(cases).contains("rule_files:\n  - ../rules/flows.yml");
        for (String alert : List.of(PAGE, TICKET)) {
            assertThat(String.valueOf(annotations(rule(alert)).get("description"))).as(alert)
                    .contains("See OPERATIONS_RUNBOOK.md section 11.37.");
            assertThat(section).as("section 11.37 names %s", alert).contains("`" + alert + "`");
            Matcher evaluation = Pattern.compile("alertname: " + alert + "\\n\\s+exp_alerts:( \\[])?").matcher(cases);
            int firing = 0;
            int silent = 0;
            while (evaluation.find()) {
                if (evaluation.group(1) == null) {
                    firing++;
                } else {
                    silent++;
                }
            }
            assertThat(firing).as("%s: a case where it fires", alert).isGreaterThanOrEqualTo(2);
            assertThat(silent).as("%s: the opposite case, where it stays silent", alert).isGreaterThanOrEqualTo(2);
        }
    }

    private static String severity(Map<String, Object> rule) {
        return String.valueOf(map(rule.get("labels")).get("severity"));
    }

    private static Map<String, Object> annotations(Map<String, Object> rule) {
        return map(rule.get("annotations"));
    }

    private static Map<String, Object> rule(String alert) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ByteArrayResource(RepositoryPaths.read(RULES).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (Object group : (List<?>) factory.getObject().get("groups")) {
            Map<String, Object> named = map(group);
            for (Object entry : (List<?>) named.get("rules")) {
                Map<String, Object> rule = map(entry);
                if (alert.equals(rule.get("alert"))) {
                    assertThat(map(named.get("labels"))).as("%s is identity's", alert).containsEntry("owner", "identity");
                    return rule;
                }
            }
        }
        throw new AssertionError("no rule named " + alert + " in " + RULES);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertThat(node).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }
}
