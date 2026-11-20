package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.RepositoryPaths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The Operator Console watched (`P17-TSK-044`; finapp-admin-panel {@code docs/CONSOLE_RUNBOOK.md}, Monitoring), in
 * {@code infra/prometheus/rules/console.yml}: a synthetic probe through the operator edge, the console's readiness as the cluster
 * watcher reads it, and the operator edge's 404s and 5xx for the console's Service. CSP violation reports are not collected
 * ({@code OPERATIONS_RUNBOOK.md} section 11.68 says why), and this holds that nothing pretends they are.
 *
 * <p><strong>What this proves, and what it does not.</strong> Each rule is run through time with its {@code for} on
 * {@link RuleTimeline} over {@link RuleEvaluator} - neither is Prometheus, and {@code increase} there is the difference of two
 * scrapes, not Prometheus' extrapolation - so a rule that passes here fires on its break and stays silent on health, the healthy
 * shapes that look like a break included, under this repository's model of PromQL. Whether it does so on Prometheus is
 * {@code infra/prometheus/tests/console.test.yml}'s, run by {@code alert-rules-check.sh}; that run, and the capture of the two
 * series written from documentation ({@code infra/prometheus/console-series.txt}), are owed with the probe's run on {@code kind}.
 * The rest holds what the build can: every threshold and {@code for} against the manifest it is read from, never copied; the
 * probe's question against the edge it asks; the watcher's read of the console's namespace; every series and label against the
 * capture or the documented list.
 */
@DisplayName("the Operator Console watched: the probe through the operator edge, its readiness, its assets' 404s and its 5xx, each rule run"
        + " through time and bounded by the manifests (P17-TSK-044)")
class ConsoleAlertRulesTest {

    private static final String RULES = "infra/prometheus/rules/console.yml";
    private static final String SERIES = "infra/prometheus/console-series.txt";
    private static final String CAPTURE = "infra/prometheus/platform-components-series.txt";
    private static final String CONSOLE = "deploy/console/base/console.yaml";
    private static final String CONSOLE_KUSTOMIZATION = "deploy/console/base/kustomization.yaml";
    private static final String CONSOLE_RBAC = "deploy/console/base/rbac-cluster-watch.yaml";
    private static final String FINAPP_RBAC = "deploy/base/rbac-cluster-watch.yaml";
    private static final String WATCHER = "deploy/observability/base/cluster-watch.yaml";
    private static final String PROBE = "deploy/observability/base/console-probe.yaml";
    private static final String KIND_OBSERVABILITY = "deploy/observability/overlays/kind/kustomization.yaml";
    private static final String SCRAPE = "deploy/observability/overlays/kind/prometheus.yml";
    private static final String OPERATOR_EDGE = "deploy/edge/base/operator-edge.yaml";
    private static final String OPERATOR_STATIC = "deploy/edge/base/traefik-operator.yaml";
    private static final String OPERATOR_ROUTES = "deploy/edge/base/operator-routes.yaml";
    private static final String PUBLIC_EDGE = "deploy/edge/base/public-edge.yaml";
    private static final String CONTROLLERS = "deploy/edge/base/controllers.yaml";
    private static final String EDGE_KUSTOMIZATION = "deploy/edge/base/kustomization.yaml";
    private static final String KIND_CERTIFICATES = "deploy/edge/overlays/kind/certificates.yaml";

    private static final String PROBING = "ConsoleProbeFailing";
    private static final String READINESS = "ConsoleReplicasUnavailable";
    private static final String MISSING = "ConsoleAssetsMissing";
    private static final String ERRORS = "ConsoleServerErrors";

    /** What the scrape adds to every series (job, instance), on kind (env), and to the proxy's (edge). */
    private static final Set<String> SCRAPE_LABELS = Set.of("job", "instance", "env", "edge");

    /** Twenty minutes of evaluations: past every for and every window here. */
    private static final long UNTIL = 1_200;

    private static final String URL = "https://finapp-edge-operator.finapp-edge.svc.cluster.local/console/";

    // ------------------------------------------------------------------------------------ the rules

    @Test
    @DisplayName("the group is the four rules, owned by platform: the probe pages after five minutes, readiness tickets after five, the edge's"
            + " 404s and 5xx ticket after one")
    void theGroupIsTheFourRules() {
        assertThat(rules().keySet()).containsExactly(PROBING, READINESS, MISSING, ERRORS);
        assertThat(group().get("labels")).isEqualTo(Map.of("owner", "platform"));
        assertThat(severity(PROBING)).as("a console no operator can reach pages: the queues cannot be worked through it").isEqualTo("page");
        for (String ticket : List.of(READINESS, MISSING, ERRORS)) {
            assertThat(severity(ticket)).as("%s: the console still answers, or the API serves every act", ticket).isEqualTo("ticket");
        }
        assertThat(waitsFor(PROBING)).as("the console's runbook's five minutes failing").isEqualTo(Duration.ofMinutes(5));
        assertThat(waitsFor(MISSING)).isEqualTo(Duration.ofMinutes(1));
        assertThat(waitsFor(ERRORS)).isEqualTo(Duration.ofMinutes(1));
        // Every rule here states its aggregation, as the platform's own files do (AlertCatalogueTest's STATED_FILES).
        String text = RepositoryPaths.read(RULES).replace("\r\n", "\n");
        for (String name : rules().keySet()) {
            assertThat(Pattern.compile("(?m)^\\s*- alert: " + name + "\\s*\\n\\s*# Aggregation: \\S").matcher(text).find())
                    .as("%s states its aggregation", name).isTrue();
        }
    }

    @Test
    @DisplayName("the probe pages after five minutes of no 2xx and on no probe at all on a deployed fleet; never on a blip, on a second prober"
            + " that reaches the page, or on compose")
    void theProbeFiresOnAFailingPageAndOnNoProbe() {
        String expr = expr(PROBING);
        Duration wait = waitsFor(PROBING);
        LongFunction<List<RuleEvaluator.Sample>> healthy = at -> List.of(status("p:8889", "200", 1), up("finapp", "kind"));
        assertThat(RuleTimeline.of(expr, wait, healthy).firing(UNTIL)).as("the page answering 200").isEmpty();

        LongFunction<List<RuleEvaluator.Sample>> failing = at -> at < 120
                ? List.of(status("p:8889", "200", 1), up("finapp", "kind"))
                : List.of(status("p:8889", "502", 0), up("finapp", "kind"));
        assertThat(RuleTimeline.of(expr, wait, failing).firstFiring(UNTIL)).as("the edge's 502 from 2m: pages at 7m, not before").contains(420L);

        LongFunction<List<RuleEvaluator.Sample>> refused = at -> at < 120
                ? List.of(status("p:8889", "200", 1), up("finapp", "kind"))
                : List.of(status("p:8889", "0", 0), up("finapp", "kind"));
        assertThat(RuleTimeline.of(expr, wait, refused).firstFiring(UNTIL)).as("a refused connection, status 0 in every class").contains(420L);

        LongFunction<List<RuleEvaluator.Sample>> blip = at -> at >= 120 && at < 300
                ? List.of(status("p:8889", "0", 0), up("finapp", "kind"))
                : List.of(status("p:8889", "200", 1), up("finapp", "kind"));
        assertThat(RuleTimeline.of(expr, wait, blip).firing(UNTIL)).as("three minutes refused, then 200 again").isEmpty();

        LongFunction<List<RuleEvaluator.Sample>> twoProbers = at -> List.of(status("p:8889", "0", 0), status("q:8889", "200", 1),
                up("finapp", "kind"));
        assertThat(RuleTimeline.of(expr, wait, twoProbers).firing(UNTIL)).as("one prober cut off, the other reaching the page: max()").isEmpty();

        LongFunction<List<RuleEvaluator.Sample>> noProbe = at -> List.of(up("finapp", "kind"));
        assertThat(RuleTimeline.of(expr, wait, noProbe).firstFiring(UNTIL)).as("no probe on a deployed fleet: unknown, so a page").contains(300L);

        LongFunction<List<RuleEvaluator.Sample>> compose = at -> List.of(up("finapp", "local"));
        assertThat(RuleTimeline.of(expr, wait, compose).firing(UNTIL)).as("compose runs no edge, console or probe").isEmpty();

        LongFunction<List<RuleEvaluator.Sample>> otherClass = at -> List.of(status("p:8889", "200", 1, "5xx"), up("finapp", "kind"));
        assertThat(RuleTimeline.of(expr, wait, otherClass).firstFiring(UNTIL))
                .as("only the 2xx class answers the question: a 5xx-class series alone is no answer").contains(300L);
    }

    @Test
    @DisplayName("readiness tickets after five minutes below two available and on no console Deployment while a watcher answers; never on"
            + " an eviction its replacement outlasts, nor on a watcher down, which ClusterWatchBlind says")
    void readinessFiresBelowTwoAndOnNoDeployment() {
        String expr = expr(READINESS);
        Duration wait = waitsFor(READINESS);
        LongFunction<List<RuleEvaluator.Sample>> healthy = at -> List.of(available("w:8889", 2), available("v:8889", 2),
                up("finapp-cluster-watch", "kind"));
        assertThat(RuleTimeline.of(expr, wait, healthy).firing(UNTIL)).as("both replicas available, two watchers").isEmpty();

        LongFunction<List<RuleEvaluator.Sample>> one = at -> List.of(available("w:8889", at < 120 ? 2 : 1), available("v:8889", at < 120 ? 2 : 1),
                up("finapp-cluster-watch", "kind"));
        assertThat(RuleTimeline.of(expr, wait, one).firstFiring(UNTIL)).as("one available from 2m: a ticket at 7m").contains(420L);
        assertThat(RuleEvaluator.series(expr, one.apply(600), one.apply(600))).as("two watchers, one alert for the Deployment")
                .containsExactly(Map.of("k8s_namespace_name", "finapp-console", "k8s_deployment_name", "finapp-console"));

        LongFunction<List<RuleEvaluator.Sample>> eviction = at -> List.of(available("w:8889", at >= 120 && at < 240 ? 1 : 2),
                up("finapp-cluster-watch", "kind"));
        assertThat(RuleTimeline.of(expr, wait, eviction).firing(UNTIL)).as("two minutes at one, until the replacement is ready").isEmpty();

        LongFunction<List<RuleEvaluator.Sample>> notDeployed = at -> List.of(deployment("finapp", "finapp-app", 3), up("finapp-cluster-watch", "kind"));
        assertThat(RuleTimeline.of(expr, wait, notDeployed).firstFiring(UNTIL)).as("the watcher answers and sees no console").contains(300L);

        LongFunction<List<RuleEvaluator.Sample>> watcherDown = at -> List.of(down("finapp-cluster-watch"));
        assertThat(RuleTimeline.of(expr, wait, watcherDown).firing(UNTIL)).as("the watcher down: ClusterWatchBlind's, not this").isEmpty();
    }

    @Test
    @DisplayName("the edge's 404s for the console's Service ticket, summed over the controllers; one stale tab's 404, the API's 404s and the"
            + " console's 5xx do not")
    void assetsMissingFiresOnTheConsolesOwn404s() {
        String expr = expr(MISSING);
        Duration wait = waitsFor(MISSING);
        LongFunction<List<RuleEvaluator.Sample>> healthy = at -> List.of(requests("o1:8081", "finapp-console@file", "200", at / 15.0),
                requests("o1:8081", "finapp-app@file", "404", at / 30.0), requests("o2:8081", "finapp-console@file", "304", at / 15.0));
        assertThat(RuleTimeline.of(expr, wait, healthy).firing(UNTIL)).as("the console answering, the API's own 404s").isEmpty();

        // A release that forgot the previous assets: from 2m, one 404 a minute at each controller. The evaluator reads two scrapes, so the
        // series exists from the start at 0; its birth at the first 404, which increase() does not count, is promtool's case to show.
        LongFunction<List<RuleEvaluator.Sample>> forgot = at -> List.of(
                requests("o1:8081", "finapp-console@file", "404", Math.max(0, (at - 120) / 60)),
                requests("o2:8081", "finapp-console@file", "404", Math.max(0, (at - 120) / 60)));
        assertThat(RuleTimeline.of(expr, wait, forgot).firstFiring(UNTIL)).as("two a minute summed: past two by 4m, a ticket a minute on")
                .hasValueSatisfying(at -> assertThat(at).isBetween(240L, 330L));

        LongFunction<List<RuleEvaluator.Sample>> oneTab = at -> List.of(requests("o1:8081", "finapp-console@file", "404", at >= 120 ? 1 : 0));
        assertThat(RuleTimeline.of(expr, wait, oneTab).firing(UNTIL)).as("one stale tab from two releases back").isEmpty();

        LongFunction<List<RuleEvaluator.Sample>> errors = at -> List.of(requests("o1:8081", "finapp-console@file", "502", at / 15.0));
        assertThat(RuleTimeline.of(expr, wait, errors).firing(UNTIL)).as("5xx are the other rule's").isEmpty();
    }

    @Test
    @DisplayName("the edge's 5xx for the console's Service ticket, any 5xx code, summed over the controllers; its 404s, its 200s and the API's"
            + " 5xx do not")
    void serverErrorsFiresOnTheConsolesOwn5xx() {
        String expr = expr(ERRORS);
        Duration wait = waitsFor(ERRORS);
        for (String code : List.of("502", "503", "504", "500")) {
            LongFunction<List<RuleEvaluator.Sample>> failing = at -> List.of(
                    requests("o1:8081", "finapp-console@file", code, Math.max(0, (at - 120) / 30)),
                    requests("o1:8081", "finapp-console@file", "200", at / 15.0));
            assertThat(RuleTimeline.of(expr, wait, failing).firstFiring(UNTIL)).as("the console's %s from 2m", code).isPresent();
        }
        LongFunction<List<RuleEvaluator.Sample>> healthy = at -> List.of(requests("o1:8081", "finapp-console@file", "200", at / 15.0),
                requests("o1:8081", "finapp-console@file", "404", at / 15.0), requests("o2:8081", "finapp-app@file", "502", at / 15.0),
                requests("o2:8081", "finapp-console@file", "5000", at / 15.0));
        assertThat(RuleTimeline.of(expr, wait, healthy).firing(UNTIL))
                .as("the console's 404s, the API's 502s, and a code the anchored 5.. does not match").isEmpty();
    }

    // ------------------------------------------------------------------------------------ bounded by the manifests

    @Test
    @DisplayName("readiness reads the console's own Deployment and namespace, its floor is the Deployment's replicas, a rollout never goes"
            + " below it, and the for outlasts a replacement's start, the watcher's collection, the scrape and the evaluation with a minute"
            + " to spare")
    @SuppressWarnings("unchecked")
    void readinessIsBoundedByTheConsolesManifests() {
        List<Map<String, Object>> console = yamlAll(CONSOLE);
        Map<String, Object> deployment = ofKind(console, "Deployment");
        Map<String, Object> spec = map(deployment.get("spec"));
        String name = String.valueOf(map(deployment.get("metadata")).get("name"));
        String namespace = String.valueOf(yaml(CONSOLE_KUSTOMIZATION).get("namespace"));
        String expr = expr(READINESS);
        assertThat(selectedValues(expr, "k8s_deployment_name")).containsOnly(name);
        assertThat(selectedValues(expr, "k8s_namespace_name")).containsOnly(namespace);

        Matcher floor = Pattern.compile("\\) < (\\d+)").matcher(expr);
        assertThat(floor.find()).as("the floor is stated").isTrue();
        assertThat(Integer.parseInt(floor.group(1))).as("below the console's replicas: its redundancy").isEqualTo(spec.get("replicas")).isEqualTo(2);
        Map<String, Object> rolling = map(map(spec.get("strategy")).get("rollingUpdate"));
        assertThat(rolling.get("maxUnavailable")).as("a rollout never drops below the floor, so never tickets").isEqualTo(0);
        assertThat(map(ofKind(console, "PodDisruptionBudget").get("spec")).get("maxUnavailable"))
                .as("an eviction takes one at a time, which the for outlasts").isEqualTo(1);
        assertThat(spec).as("available is ready: no minReadySeconds holds a ready pod back").doesNotContainKey("minReadySeconds");

        Map<String, Object> container = (Map<String, Object>) ((List<Object>) map(map(spec.get("template")).get("spec")).get("containers")).get(0);
        Duration start = probeBudget(map(container.get("startupProbe"))).plus(probeBudget(map(container.get("readinessProbe"))));
        Duration lag = collection(WATCHER, "k8s_cluster").plus(scrapeInterval()).plus(evaluationInterval());
        assertThat(waitsFor(READINESS)).as("a replacement's start %s, the collection, the scrape and the evaluation %s, and a minute", start, lag)
                .isGreaterThanOrEqualTo(start.plus(lag).plus(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("the probe asks GET /console/ of the operator edge's own Service on its TLS port, naming the operator host kind's certificate"
            + " is issued for, once every 30 s, its stale answers dropped well inside the rule's for; the rule reads the job the scrape gives it")
    @SuppressWarnings("unchecked")
    void theProbeAsksThePageThroughTheOperatorEdge() {
        Map<String, Object> probe = yaml(PROBE);
        Map<String, Object> httpcheck = map(map(probe.get("receivers")).get("httpcheck"));
        List<Object> targets = (List<Object>) httpcheck.get("targets");
        assertThat(targets).as("one question: max() over its answers is that question's").hasSize(1);
        Map<String, Object> target = map(targets.get(0));

        Map<String, Object> service = yamlAll(CONTROLLERS).stream().filter(document -> "Service".equals(document.get("kind"))
                && "finapp-edge-operator".equals(map(map(document.get("spec")).get("selector")).get("app.kubernetes.io/name"))).findFirst().orElseThrow();
        Map<String, Object> port = map(((List<Object>) map(service.get("spec")).get("ports")).get(0));
        assertThat(port.get("port")).as("https's own port, so the URL names none").isEqualTo(443);
        String edgeNamespace = String.valueOf(yaml(EDGE_KUSTOMIZATION).get("namespace"));
        String expected = "https://" + map(service.get("metadata")).get("name") + "." + edgeNamespace + ".svc.cluster.local/console/";
        assertThat(target.get("endpoint")).as("the operator edge's Service, the console's page").isEqualTo(expected).isEqualTo(URL);
        assertThat(target.get("method")).isEqualTo("GET");
        assertThat(RepositoryPaths.read(OPERATOR_ROUTES)).as("a path the console's router admits").contains("(Method(`GET`) && PathPrefix(`/console/`))");

        Map<String, Object> tls = map(target.get("tls"));
        assertThat(tls.get("server_name_override")).as("the edge's sniStrict needs the operator host").isEqualTo("${env:FINAPP_OPERATOR_HOST}");
        assertThat(tls.get("insecure_skip_verify")).as("availability, not identity: stated, so a change is a reviewed one (section 11.68)").isEqualTo(true);
        // Unverified, so it must carry nothing worth taking: no header, no authenticator, no cookie, no body - a GET of a static page.
        assertThat(target).as("the probe's request names nothing but where and how long").containsOnlyKeys("endpoint", "method", "timeout", "tls");
        assertThat(tls).as("no client certificate, no CA it pretends to trust").containsOnlyKeys("insecure_skip_verify", "server_name_override");
        assertThat(map(probe.get("receivers"))).as("one receiver, no authenticating extension").containsOnlyKeys("httpcheck");
        assertThat(map(probe.get("extensions"))).containsOnlyKeys("health_check");
        assertThat(map(map(yaml(OPERATOR_STATIC).get("providers")))).as("the providers").containsOnlyKeys("file");
        String host = yamlAll(KIND_CERTIFICATES).stream().filter(document -> "finapp-edge-operator".equals(map(document.get("metadata")).get("name")))
                .map(document -> String.valueOf(((List<Object>) map(document.get("spec")).get("dnsNames")).get(0))).findFirst().orElseThrow();
        assertThat(RepositoryPaths.read(KIND_OBSERVABILITY)).as("kind names the host its operator certificate is issued for")
                .contains("  - name: finapp-console-probe-target\n    literals:\n      - FINAPP_OPERATOR_HOST=" + host + "\n");

        Duration every = DurationStyle.detectAndParse(String.valueOf(httpcheck.get("collection_interval")));
        Duration expiry = DurationStyle.detectAndParse(String.valueOf(map(map(probe.get("exporters")).get("prometheus")).get("metric_expiration")));
        assertThat(every).isEqualTo(Duration.ofSeconds(30));
        assertThat(expiry).as("a live probe re-publishes before its answer expires").isGreaterThanOrEqualTo(every.multipliedBy(2));
        assertThat(expiry.plus(scrapeInterval())).as("a success no longer given is gone well inside the for").isLessThan(waitsFor(PROBING));
        Map<String, Object> pipeline = map(map(map(probe.get("service")).get("pipelines")).get("metrics"));
        assertThat(pipeline.get("receivers")).isEqualTo(List.of("httpcheck"));
        assertThat(pipeline.get("exporters")).isEqualTo(List.of("prometheus"));

        assertThat(selectedValues(expr(PROBING), "job")).containsOnly("finapp-console-probe", "finapp");
        String scrape = RepositoryPaths.read(SCRAPE).replace("\r\n", "\n");
        assertThat(Pattern.compile("(?s)- job_name: finapp-console-probe\n.*?\"finapp-console-probe\\.finapp-observability\\.svc\\.cluster\\.local\"\\]"
                + "\n\\s+type: A\n\\s+port: 8889").matcher(scrape).find()).as("the scrape finds the probe's exporter by its Service").isTrue();
        assertThat(String.valueOf(map(map(probe.get("exporters")).get("prometheus")).get("endpoint"))).endsWith(":8889");
    }

    @Test
    @DisplayName("the 404 and 5xx rules read the operator edge's counters for the console's own Service, by the name the file provider gives it,"
            + " which the console's router alone names and the public edge does not define")
    @SuppressWarnings("unchecked")
    void theEdgeRulesReadTheConsolesService() {
        Map<String, Object> services = map(map(yaml(OPERATOR_EDGE).get("http")).get("services"));
        assertThat(services).containsKey("finapp-console");
        Map<String, Object> router = map(map(map(yaml(OPERATOR_ROUTES).get("http")).get("routers")).get("operator-console"));
        assertThat(router.get("service")).as("the console's one router names it").isEqualTo("finapp-console");
        for (String name : List.of(MISSING, ERRORS)) {
            assertThat(selectedValues(expr(name), "service")).as(name).containsOnly("finapp-console@file");
            assertThat(selectedValues(expr(name), "edge")).as("%s: the console is the operator edge's alone", name).containsOnly("operator");
            assertThat(selectedValues(expr(name), "job")).as(name).containsOnly("finapp-edge");
        }
        assertThat(map(map(yaml(PUBLIC_EDGE).get("http")).get("services"))).as("the public edge defines no console service").doesNotContainKey("finapp-console");
        // The per-service counters are the proxy's default (addServicesLabels true); a static file switching them off blinds both rules.
        assertThat(map(map(yaml(OPERATOR_STATIC).get("metrics")).get("prometheus"))).doesNotContainKeys("addServicesLabels", "addEntryPointsLabels");
        assertThat(selectedValues(expr(MISSING), "code")).containsOnly("404");
        assertThat(expr(ERRORS)).contains("code=~\"5..\"");
    }

    @Test
    @DisplayName("the watcher reads the console's namespace through a Role the console's release grants: list and watch on its receiver's"
            + " kinds - finapp's less events - bound to the watcher's account alone")
    @SuppressWarnings("unchecked")
    void theWatcherReadsTheConsolesNamespace() {
        String namespace = String.valueOf(yaml(CONSOLE_KUSTOMIZATION).get("namespace"));
        Map<String, Object> receiver = map(map(yaml(WATCHER).get("receivers")).get("k8s_cluster"));
        assertThat((List<Object>) receiver.get("namespaces")).contains(namespace);
        assertThat(RepositoryPaths.read(CONSOLE_KUSTOMIZATION).replace("\r\n", "\n")).contains("\n  - rbac-cluster-watch.yaml\n");
        List<Map<String, Object>> rbac = yamlAll(CONSOLE_RBAC);
        assertThat(rbac).extracting(document -> document.get("kind")).containsExactly("Role", "RoleBinding");
        Set<String> granted = grants(ofKind(rbac, "Role"));
        Set<String> finapps = grants(ofKind(yamlAll(FINAPP_RBAC), "Role"));
        finapps.remove("/events");
        assertThat(granted).as("the receiver's kinds as finapp's Role grants them, and not events: no record of the console's is shipped")
                .isEqualTo(finapps).contains("apps/deployments").doesNotContain("/events", "/secrets", "/configmaps", "/pods/log");
        Map<String, Object> binding = ofKind(rbac, "RoleBinding");
        assertThat(binding.get("subjects")).isEqualTo(List.of(Map.of("kind", "ServiceAccount", "name", "finapp-cluster-watch",
                "namespace", "finapp-observability")));
        assertThat(map(binding.get("roleRef")).get("name")).isEqualTo(map(ofKind(rbac, "Role").get("metadata")).get("name"));
        assertThat(RepositoryPaths.read("infra/scripts/manifest-policy/finapp.rego"))
                .contains("cluster_watch_namespaces := {\"finapp\", \"finapp-console\"}");
    }

    @Test
    @DisplayName("every series the rules read is in the capture or the documented list, and every label they select or group by is that"
            + " series' own or one the scrape adds; the documented list says it is not a capture")
    void everySeriesAndLabelIsPublishedOrDocumented() {
        Map<String, Set<String>> published = new TreeMap<>(series(CAPTURE));
        published.putAll(series(SERIES));
        Pattern selector = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\{([^}]*)}");
        Pattern matcher = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\s*(=~|!=|=)");
        Pattern grouping = Pattern.compile("\\bby\\s*\\(([a-z0-9_, ]*)\\)");
        int read = 0;
        for (String name : rules().keySet()) {
            String expr = expr(name);
            Matcher series = selector.matcher(expr);
            while (series.find()) {
                String metric = series.group(1);
                Set<String> labels = "up".equals(metric) ? SCRAPE_LABELS : published.get(metric);
                assertThat(labels).as("%s reads %s, which is captured or documented", name, metric).isNotNull();
                Matcher label = matcher.matcher(series.group(2));
                while (label.find()) {
                    assertThat(union(labels, SCRAPE_LABELS)).as("%s selects %s on %s", name, label.group(1), metric).contains(label.group(1));
                }
                read++;
            }
            Matcher by = grouping.matcher(expr);
            while (by.find()) {
                for (String label : by.group(1).split(",\\s*")) {
                    assertThat(published.get("k8s_deployment_available")).as("%s groups by %s", name, label).contains(label.strip());
                }
            }
        }
        assertThat(read).as("not vacuous: every selector of the four rules").isEqualTo(8);
        assertThat(RepositoryPaths.read(SERIES)).contains("NOT A CAPTURE").contains("owed with the probe's run on kind")
                .contains("(documentation, not captured)");
    }

    @Test
    @DisplayName("no CSP report is pretended: the console's policy names no report endpoint and no rule reads a CSP series, as section 11.68"
            + " decides")
    void cspReportsAreNotCollected() {
        String policy = RepositoryPaths.read(OPERATOR_EDGE);
        Matcher csp = Pattern.compile("(?s)console-headers:.*?contentSecurityPolicy: \"([^\"]*)\"").matcher(policy);
        assertThat(csp.find()).isTrue();
        assertThat(csp.group(1)).as("a report-to or report-uri would send operators' page addresses where nothing collects them")
                .doesNotContain("report-to").doesNotContain("report-uri");
        for (String name : rules().keySet()) {
            assertThat(expr(name)).as("%s reads no CSP series", name).doesNotContainIgnoringCase("csp");
        }
        assertThat(RepositoryPaths.read("docs/architecture/OPERATIONS_RUNBOOK.md")).contains("CSP violation reports are not collected");
    }

    // ------------------------------------------------------------------------------------ the evaluator's additions

    @Test
    @DisplayName("the evaluator's grammar as widened here: =~ is anchored, count counts every element, and on() keeps the left whole or"
            + " nothing, a bare and is refused, and a parenthesis holds a whole expression while what parsed before parses the same")
    void theEvaluatorsAdditionsHoldPrometheusSemantics() {
        List<RuleEvaluator.Sample> scrape = List.of(
                new RuleEvaluator.Sample("x", Map.of("code", "502"), 1), new RuleEvaluator.Sample("x", Map.of("code", "1502"), 1),
                new RuleEvaluator.Sample("x", Map.of("code", "50"), 1), new RuleEvaluator.Sample("y", Map.of("a", "1"), Double.NaN),
                new RuleEvaluator.Sample("y", Map.of("a", "2"), 3));
        assertThat(RuleEvaluator.series("x{code=~\"5..\"}", scrape, scrape)).as("anchored at both ends").containsExactly(Map.of("code", "502"));
        assertThat(RuleEvaluator.series("count(y) == 2", scrape, scrape)).as("NaN counted: count asks how many").hasSize(1);
        assertThat(RuleEvaluator.series("max(y) == 3", scrape, scrape)).as("max still skips NaN").hasSize(1);
        assertThat(RuleEvaluator.series("x{code=\"502\"} and on() (count(y) > 1)", scrape, scrape)).containsExactly(Map.of("code", "502"));
        assertThat(RuleEvaluator.series("x{code=\"502\"} and on() (count(y) > 5)", scrape, scrape)).as("the right empty: nothing").isEmpty();
        assertThat(RuleEvaluator.series("absent(z) and on() (count(y) > 1)", scrape, scrape)).hasSize(1);
        assertThatThrownBy(() -> RuleEvaluator.series("x and y", scrape, scrape)).as("a bare and matches by every label: refused")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleEvaluator.series("x{code=~\"5..\"} unless y", scrape, scrape)).isInstanceOf(IllegalArgumentException.class);
        assertThat(RuleEvaluator.series("(max(x) / max(x)) > 0.5", scrape, scrape)).as("arithmetic in a parenthesis, as before").hasSize(1);
        assertThat(RuleEvaluator.series("max(x) < 1 or (absent(z) and on() (count(x) > 2))", scrape, scrape))
                .as("a comparison and a conjunction inside a parenthesis, under or").hasSize(1);
        assertThat(RuleEvaluator.seriesOf(expr(PROBING))).as("and and on are never series").containsExactlyInAnyOrder("httpcheck_status", "up");
        assertThat(RuleEvaluator.selectorsOf(expr(ERRORS))).allSatisfy(spelled -> assertThat(spelled).contains("code=~\"5..\""));
    }

    // ------------------------------------------------------------------------------------ samples

    private static RuleEvaluator.Sample status(String instance, String code, double value) {
        return status(instance, code, value, "2xx");
    }

    private static RuleEvaluator.Sample status(String instance, String code, double value, String statusClass) {
        return new RuleEvaluator.Sample("httpcheck_status", Map.of("job", "finapp-console-probe", "instance", instance, "env", "kind",
                "http_url", URL, "http_method", "GET", "http_status_code", code, "http_status_class", statusClass), value);
    }

    private static RuleEvaluator.Sample up(String job, String env) {
        return new RuleEvaluator.Sample("up", Map.of("job", job, "instance", job + ":1", "env", env), 1);
    }

    private static RuleEvaluator.Sample down(String job) {
        return new RuleEvaluator.Sample("up", Map.of("job", job, "instance", job + ":1", "env", "kind"), 0);
    }

    private static RuleEvaluator.Sample available(String instance, double value) {
        return new RuleEvaluator.Sample("k8s_deployment_available", Map.of("job", "finapp-cluster-watch", "instance", instance, "env", "kind",
                "k8s_namespace_name", "finapp-console", "k8s_deployment_name", "finapp-console", "k8s_deployment_uid", "c1"), value);
    }

    private static RuleEvaluator.Sample deployment(String namespace, String name, double value) {
        return new RuleEvaluator.Sample("k8s_deployment_available", Map.of("job", "finapp-cluster-watch", "instance", "w:8889", "env", "kind",
                "k8s_namespace_name", namespace, "k8s_deployment_name", name, "k8s_deployment_uid", "a1"), value);
    }

    private static RuleEvaluator.Sample requests(String instance, String service, String code, double value) {
        return new RuleEvaluator.Sample("traefik_service_requests_total", Map.of("job", "finapp-edge", "instance", instance, "edge", "operator",
                "env", "kind", "service", service, "code", code, "method", "GET", "protocol", "http"), value);
    }

    // ------------------------------------------------------------------------------------ reading

    private static Map<String, Object> group() {
        List<?> groups = (List<?>) yaml(RULES).get("groups");
        assertThat(groups).as("one group").hasSize(1);
        Map<String, Object> group = map(groups.get(0));
        assertThat(group.get("name")).isEqualTo("console");
        return group;
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
        Matcher value = Pattern.compile("\\b" + label + "=~?\"([^\"]*)\"").matcher(expr);
        while (value.find()) {
            values.add(value.group(1));
        }
        assertThat(values).as("%s is selected", label).isNotEmpty();
        return values;
    }

    /** A probe's own budget: how long it may take to pass, its period times its failure threshold. */
    private static Duration probeBudget(Map<String, Object> probe) {
        return Duration.ofSeconds(((Number) probe.get("periodSeconds")).longValue() * ((Number) probe.get("failureThreshold")).longValue());
    }

    private static Duration collection(String config, String receiver) {
        return DurationStyle.detectAndParse(String.valueOf(map(map(yaml(config).get("receivers")).get(receiver)).get("collection_interval")));
    }

    private static Duration scrapeInterval() {
        return DurationStyle.detectAndParse(String.valueOf(map(yaml(SCRAPE).get("global")).get("scrape_interval")));
    }

    private static Duration evaluationInterval() {
        return DurationStyle.detectAndParse(String.valueOf(map(yaml(SCRAPE).get("global")).get("evaluation_interval")));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> grants(Map<String, Object> role) {
        Set<String> granted = new TreeSet<>();
        for (Object node : (List<Object>) role.get("rules")) {
            Map<String, Object> rule = map(node);
            assertThat((List<Object>) rule.get("verbs")).as("list and watch, nothing else").containsExactly("list", "watch");
            for (Object group : (List<Object>) rule.get("apiGroups")) {
                for (Object resource : (List<Object>) rule.get("resources")) {
                    granted.add(group + "/" + resource);
                }
            }
        }
        return granted;
    }

    /** Each listed name with its labels. */
    private static Map<String, Set<String>> series(String path) {
        Map<String, Set<String>> listed = new TreeMap<>();
        Matcher line = Pattern.compile("(?m)^([a-z_][a-z0-9_]*)\\{([^}]*)}\\s*$").matcher(RepositoryPaths.read(path));
        while (line.find()) {
            Set<String> labels = new TreeSet<>();
            for (String pair : line.group(2).split(",")) {
                labels.add(pair.substring(0, pair.indexOf('=')));
            }
            listed.put(line.group(1), labels);
        }
        assertThat(listed).as("%s was read", path).isNotEmpty();
        return listed;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new TreeSet<>(a);
        all.addAll(b);
        return all;
    }

    private static Map<String, Object> ofKind(List<Map<String, Object>> documents, String kind) {
        return documents.stream().filter(document -> kind.equals(document.get("kind"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind));
    }

    private static Map<String, Object> yaml(String path) {
        List<Map<String, Object>> documents = yamlAll(path);
        assertThat(documents).as("%s is one document", path).hasSize(1);
        return documents.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> yamlAll(String path) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(RepositoryPaths.read(path))) {
            if (document != null) {
                documents.add((Map<String, Object>) document);
            }
        }
        return documents;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertThat(node).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }
}
