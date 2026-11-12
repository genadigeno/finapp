package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.FileSystemResource;

/**
 * The platform's own components watched (`P16-TSK-003`; THREAT_MODEL.md section 6 item 43 (b)): the migrator Job, the cluster watcher that
 * reads it, and the edge, in {@code platform.yml}'s {@code platform-components} group. <strong>Whether they fire is {@code promtool}'s</strong>
 * ({@code infra/prometheus/tests/platform-components.test.yml}). This holds what a build can: every {@code for} against the numbers it is
 * derived from, read from the manifests that set them and never copied; the Job, Deployment and namespace the rules read are the ones the
 * manifests run; every series and label the rules read is one the pinned images publish, from the capture of their runs; the edge's ratio is
 * per edge over its controllers at the threshold the SLO document gives.
 */
@DisplayName("the platform's own components: the migrator Job, the cluster watcher and the edge, each rule bounded by what it waits for (P16-TSK-003)")
class PlatformComponentAlertRulesTest {

    private static final String RULES = "infra/prometheus/rules/platform.yml";
    private static final String CASES = "infra/prometheus/tests/platform-components.test.yml";
    private static final String CAPTURE = "infra/prometheus/platform-components-series.txt";
    private static final String JOB = "deploy/base/migrator-job.yaml";
    private static final String APP = "deploy/base/deployment.yaml";
    private static final String KIND_APP = "deploy/overlays/kind/kustomization.yaml";
    private static final String WATCHER = "deploy/observability/base/cluster-watch.yaml";
    private static final String SCRAPE = "deploy/observability/overlays/kind/prometheus.yml";
    private static final String SLOS = "docs/architecture/SLOS.md";

    private static final Set<String> RULE_NAMES =
            Set.of("MigratorJobFailed", "MigratorJobOverdue", "ClusterWatchBlind", "EdgeErrorRatioHigh", "EdgeUnscraped");

    /** What the scrape adds to every series (job, instance), on kind (env), and to the proxy's (edge). */
    private static final Set<String> SCRAPE_LABELS = Set.of("job", "instance", "env", "edge");

    /** The Job controller's first backoff, Kubernetes' own constant: each try after a failure waits twice the last. */
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(10);

    @Test
    @DisplayName("the group is the five rules, every one owned by platform: three on the Job and its watcher, two on the edge")
    void theGroupIsTheFiveRules() {
        assertThat(rules().keySet()).isEqualTo(RULE_NAMES);
        assertThat(group().get("labels")).isEqualTo(Map.of("owner", "platform"));
    }

    @Test
    @DisplayName("MigratorJobOverdue waits out the Job's own deadline, the watcher's collection, the scrape and the evaluation, with a minute"
            + " to spare, each read from the manifest that sets it: a Job inside its deadline never pages")
    void theOverdueRuleWaitsOutTheDeadline() {
        Duration deadline = Duration.ofSeconds(((Number) spec(job()).get("activeDeadlineSeconds")).longValue());
        Duration lag = collection().plus(scrapeInterval()).plus(evaluationInterval());
        Duration waits = waitsFor("MigratorJobOverdue");
        assertThat(deadline).as("the deadline read").isEqualTo(Duration.ofSeconds(900));
        assertThat(waits).as("the deadline %s, the collection, the scrape and the evaluation %s, and a minute", deadline, lag)
                .isGreaterThanOrEqualTo(deadline.plus(lag).plus(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("MigratorJobFailed waits out the Job's longest backoff between two tries, the collection, the scrape and the evaluation: a"
            + " transient failure retried never pages")
    void theFailedRuleWaitsOutTheBackoff() {
        int backoffLimit = ((Number) spec(job()).get("backoffLimit")).intValue();
        assertThat(backoffLimit).as("the limit read").isEqualTo(2);
        // The tries after the first wait 10 s, 20 s, ...: the longest wait between two tries of this Job is the last.
        Duration longest = FIRST_BACKOFF.multipliedBy(1L << Math.max(0, backoffLimit - 1));
        Duration waits = waitsFor("MigratorJobFailed");
        assertThat(waits).as("the longest backoff %s, the collection, the scrape and the evaluation", longest)
                .isGreaterThan(longest.plus(collection()).plus(scrapeInterval()).plus(evaluationInterval()));
        // A deleted Job's state is read for the exporter's expiry: kept below what the failed rule waits, so a replaced predecessor's
        // failure cannot hold through it alone.
        assertThat(expiry()).as("the exporter's metric_expiration").isLessThan(waits);
    }

    @Test
    @DisplayName("the rules read the Job, the Deployment and the namespace the manifests run: the migrator Job's name, finapp-app, finapp")
    void theRulesReadWhatTheManifestsRun() {
        String jobName = String.valueOf(map(job().get("metadata")).get("name"));
        String appName = String.valueOf(map(yaml(APP).get("metadata")).get("name"));
        String namespace = String.valueOf(yaml(KIND_APP).get("namespace"));
        assertThat(List.of(jobName, appName, namespace)).containsExactly("finapp-migrator", "finapp-app", "finapp");
        for (String name : List.of("MigratorJobFailed", "MigratorJobOverdue")) {
            Set<String> selected = selectedValues(expr(name), "k8s_job_name");
            assertThat(selected).as("%s reads the Job by its name", name).containsOnly(jobName);
        }
        assertThat(selectedValues(expr("ClusterWatchBlind"), "k8s_deployment_name")).containsOnly(appName);
        assertThat(selectedValues(expr("ClusterWatchBlind"), "k8s_namespace_name")).containsOnly(namespace);
        // A Job is read by its name, never its uid: one release's Job replaces the last under the same name.
        for (String name : List.of("MigratorJobFailed", "MigratorJobOverdue")) {
            assertThat(expr(name)).as(name).doesNotContain("k8s_job_uid").contains("max by (k8s_namespace_name, k8s_job_name)");
        }
        // And the watcher reads that namespace.
        Map<String, Object> receiver = map(map(yaml(WATCHER).get("receivers")).get("k8s_cluster"));
        assertThat((List<?>) receiver.get("namespaces")).containsExactly(namespace);
    }

    @Test
    @DisplayName("every series the rules read is one the pinned images published, and every label they select or group by is that series'"
            + " own or one the scrape adds")
    void everySeriesAndLabelIsPublished() {
        Map<String, Set<String>> published = capture();
        assertThat(published).as("the capture was read").containsKeys("k8s_job_failed_pods", "traefik_entrypoint_requests_total");
        Pattern selector = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\{([^}]*)}");
        Pattern matcher = Pattern.compile("([a-z_]+)\\s*(=~|!=|=)");
        Pattern grouping = Pattern.compile("\\b(?:by|on)\\s*\\(([a-z_, ]*)\\)");
        int read = 0;
        for (String name : RULE_NAMES) {
            String expr = expr(name);
            Matcher series = selector.matcher(expr);
            while (series.find()) {
                String metric = series.group(1);
                Set<String> labels = "up".equals(metric) ? SCRAPE_LABELS : published.get(metric);
                assertThat(labels).as("%s reads %s, which the pinned images publish", name, metric).isNotNull();
                Matcher label = matcher.matcher(series.group(2));
                while (label.find()) {
                    assertThat(union(labels, SCRAPE_LABELS)).as("%s selects %s on %s", name, label.group(1), metric).contains(label.group(1));
                }
                read++;
            }
            Matcher by = grouping.matcher(expr);
            while (by.find()) {
                for (String label : by.group(1).split(",\\s*")) {
                    if (label.isBlank()) {
                        continue;
                    }
                    boolean somewhere = SCRAPE_LABELS.contains(label) || published.values().stream().anyMatch(set -> set.contains(label));
                    assertThat(somewhere).as("%s groups by %s, which some published series carries", name, label).isTrue();
                }
            }
        }
        assertThat(read).as("not vacuous: the selectors were read").isGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("the edge's ratio is per edge over its controllers, on the TLS listener, over the SLO document's fast pair at the budget of"
            + " the transfer, payment, merchant and authentication availability objectives")
    void theEdgeRatioIsTheFastPairPerEdge() {
        String expr = expr("EdgeErrorRatioHigh");
        assertThat(count(expr, "sum by (edge) (rate(traefik_entrypoint_requests_total{job=\"finapp-edge\", entrypoint=\"websecure\""))
                .as("numerator and denominator, both windows, each summed per edge").isEqualTo(4);
        assertThat(expr).contains("[5m]").contains("[1h]").doesNotContain("instance");
        Map<String, String> budgets = new TreeMap<>();
        Matcher row = Pattern.compile("(?m)^\\| `([a-z_]+)` \\|.*\\| ([0-9.]+) \\|\\s*$").matcher(RepositoryPaths.read(SLOS));
        while (row.find()) {
            budgets.put(row.group(1), row.group(2));
        }
        Set<String> carried = new TreeSet<>();
        for (String objective : List.of("transfer_availability", "payment_checkout", "merchant_api", "authentication_availability")) {
            carried.add(budgets.get(objective));
        }
        assertThat(carried).as("the four availability objectives' budget, read from SLOS.md").hasSize(1);
        String budget = carried.iterator().next();
        assertThat(count(expr, "> (14.4 * " + budget + ")")).as("both windows over the fast pair's threshold").isEqualTo(2);
        assertThat(waitsFor("EdgeErrorRatioHigh")).as("the fast pair's for, SLOS.md section 1").isEqualTo(Duration.ofMinutes(2));
        String percent = new java.math.BigDecimal("14.4").multiply(new java.math.BigDecimal(budget)).movePointRight(2).stripTrailingZeros().toPlainString();
        assertThat(String.valueOf(map(rules().get("EdgeErrorRatioHigh").get("annotations")).get("description"))).contains("Over " + percent + "%");
        assertThat(severity("EdgeErrorRatioHigh")).isEqualTo("page");
        assertThat(expr("EdgeUnscraped")).contains("sum by (edge) (up{job=\"finapp-edge\"}) == 0")
                .contains("absent(up{job=\"finapp-edge\", edge=\"public\"})").contains("absent(up{job=\"finapp-edge\", edge=\"operator\"})")
                .contains("count(up{job=\"finapp\", env!=\"local\"}) > 0");
    }

    @Test
    @DisplayName("the scrape finds every controller of both edges and the watcher by DNS, names the edge from the Service, and reads the"
            + " proxy's management port")
    void theScrapeFindsTheEdgesAndTheWatcher() {
        String scrape = RepositoryPaths.read(SCRAPE).replace("\r\n", "\n");
        assertThat(scrape).contains("- job_name: finapp-edge\n").contains("- job_name: finapp-cluster-watch\n")
                .contains("\"finapp-edge-public-scrape.finapp-edge.svc.cluster.local\", \"finapp-edge-operator-scrape.finapp-edge.svc.cluster.local\"")
                .contains("regex: finapp-edge-(public|operator)-scrape\\..*\n        target_label: edge")
                .contains("finapp-cluster-watch.finapp-observability.svc.cluster.local");
        assertThat(Pattern.compile("(?s)- job_name: finapp-edge\n.*?port: 8081").matcher(scrape).find()).as("the management port").isTrue();
        assertThat(Pattern.compile("(?s)- job_name: finapp-cluster-watch\n.*?port: 8889").matcher(scrape).find()).as("the exporter").isTrue();
    }

    @Test
    @DisplayName("every rule here has a promtool case that fires it and one that keeps it silent")
    void eachIsTestedBothWays() {
        String cases = RepositoryPaths.read(CASES).replace("\r\n", "\n");
        for (String name : RULE_NAMES) {
            assertThat(Pattern.compile("alertname: " + name + "\n\\s+exp_alerts:\n\\s+- exp_labels:").matcher(cases).find())
                    .as("a case where %s fires", name).isTrue();
            assertThat(cases).as("a case where %s stays silent", name).contains("alertname: " + name + "\n        exp_alerts: []");
        }
    }

    // ------------------------------------------------------------------------------------ reading

    private static Map<String, Object> group() {
        for (Object node : (List<?>) yaml(RULES).get("groups")) {
            Map<String, Object> group = map(node);
            if ("platform-components".equals(group.get("name"))) {
                return group;
            }
        }
        throw new AssertionError("no group platform-components in " + RULES);
    }

    private static Map<String, Map<String, Object>> rules() {
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        for (Object node : (List<?>) group().get("rules")) {
            Map<String, Object> rule = map(node);
            rules.put(String.valueOf(rule.get("alert")), rule);
        }
        return rules;
    }

    private static String expr(String name) {
        assertThat(rules()).containsKey(name);
        return String.valueOf(rules().get(name).get("expr")).replaceAll("\\s+", " ").strip();
    }

    private static Duration waitsFor(String name) {
        return DurationStyle.detectAndParse(String.valueOf(rules().get(name).get("for")));
    }

    private static String severity(String name) {
        return String.valueOf(map(rules().get(name).get("labels")).get("severity"));
    }

    private static Set<String> selectedValues(String expr, String label) {
        Set<String> values = new TreeSet<>();
        Matcher value = Pattern.compile("\\b" + label + "=\"([^\"]*)\"").matcher(expr);
        while (value.find()) {
            values.add(value.group(1));
        }
        assertThat(values).as("%s is selected", label).isNotEmpty();
        return values;
    }

    private static Map<String, Object> job() {
        return yaml(JOB);
    }

    private static Map<String, Object> spec(Map<String, Object> document) {
        return map(document.get("spec"));
    }

    private static Duration collection() {
        Map<String, Object> receiver = map(map(yaml(WATCHER).get("receivers")).get("k8s_cluster"));
        return DurationStyle.detectAndParse(String.valueOf(receiver.get("collection_interval")));
    }

    private static Duration expiry() {
        Map<String, Object> exporter = map(map(yaml(WATCHER).get("exporters")).get("prometheus"));
        return DurationStyle.detectAndParse(String.valueOf(exporter.get("metric_expiration")));
    }

    private static Duration scrapeInterval() {
        return DurationStyle.detectAndParse(String.valueOf(map(yaml(SCRAPE).get("global")).get("scrape_interval")));
    }

    private static Duration evaluationInterval() {
        return DurationStyle.detectAndParse(String.valueOf(map(yaml(SCRAPE).get("global")).get("evaluation_interval")));
    }

    /** Each published name with its labels, from the capture. */
    private static Map<String, Set<String>> capture() {
        Map<String, Set<String>> published = new TreeMap<>();
        Matcher line = Pattern.compile("(?m)^([a-z_][a-z0-9_]*)\\{([^}]*)}\\s*$").matcher(RepositoryPaths.read(CAPTURE));
        while (line.find()) {
            Set<String> labels = new TreeSet<>();
            for (String pair : line.group(2).split(",")) {
                labels.add(pair.substring(0, pair.indexOf('=')));
            }
            published.put(line.group(1), labels);
        }
        return published;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new TreeSet<>(a);
        all.addAll(b);
        return all;
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            found++;
        }
        return found;
    }

    private static Map<String, Object> yaml(String path) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new FileSystemResource(RepositoryPaths.locate(path)));
        Map<String, Object> document = factory.getObject();
        assertThat(document).as("%s must parse", path).isNotNull();
        return document;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertThat(node).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }
}
