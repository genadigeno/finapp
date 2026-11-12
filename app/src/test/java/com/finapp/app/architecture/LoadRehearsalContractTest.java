package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the load harness holds about itself ({@code P16-TSK-006}, ADR-0126; {@code OPERATIONS_RUNBOOK.md} section 11.43): the kind
 * rehearsal's {@code load} scope, its k6 scenarios, its sampler and its probes.
 *
 * <p>A load run's result is a cluster's behaviour, recorded in the runbook and the resilience register, and nothing here claims it.
 * What a hermetic test can hold is that a later edit does not quietly take away what a run proves. A load harness fails by passing a
 * run nobody judged, so each member refuses one way of doing that:
 *
 * <ol>
 *   <li>the generator is pinned by digest, watched and outside the build graph;
 *   <li>the model is open, and what k6 will run is read before any cluster exists;
 *   <li>every correctness page of {@code SLOS.md} section 3 and every census clause marked as a committed fact is read every 30 s in
 *       one read-only repeatable-read snapshot, a sample that cannot read fails, and a failing sample stops the load;
 *   <li>the marked clauses are committed facts: none needs a count from the client or reads a server statistic;
 *   <li>the samples must cover the load, and the generator's dropped iterations, arrivals and CPU are judged;
 *   <li>one effect per key is tried (same-key retries) and counted (the census takes the generator's counts);
 *   <li>the generator reaches only the run's own cluster, through the edge, with only the run's fixture and the edge's CA;
 *   <li>the demonstration profile is the capacity model's, and every operation it leaves undriven says why and who drives it;
 *   <li>each of the entry's probes is wired to do what it says;
 *   <li>the platform's own signals ({@code THREAT_MODEL.md} section 6 item 47 (c)) are rehearsed in the same run: a migrator Job planted
 *       to fail pages twice and is routed, its lines reach Loki scrubbed, and every platform stream is swept for every needle.
 * </ol>
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the load harness holds what it says it holds (P16-TSK-006)")
class LoadRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String SCENARIOS = "infra/scripts/kind-rehearsal/load/scenarios.js";
    private static final String MODEL = "infra/scripts/kind-rehearsal/load/model.js";
    private static final String PROFILE = "infra/scripts/kind-rehearsal/load/demonstration.json";
    private static final String CENSUS = "infra/scripts/kind-rehearsal/census.sql";
    private static final String CLIENT = "infra/scripts/kind-rehearsal/Rehearsal.java";
    private static final String PINS = "infra/scanner-pins.sh";

    /** A census clause marked as read during load: the marker line, then the statement it marks. */
    private static final Pattern MARKED = Pattern.compile("(?m)^-- \\[sampled during load]\\n((?:--[^\\n]*\\n)*)(SELECT CASE[\\s\\S]*?;)\\s*$");

    /** An operation of the profile, one per line. */
    private static final Pattern OPERATION = Pattern.compile("(?m)^\\s*\\{\"name\": \"([a-z]+)\", \"generator\": \"([a-z0-9]+)\"(.*)}");

    private static Path root;
    private static String harness;
    private static String load;
    private static String scenarios;
    private static String census;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = read(HARNESS);
        load = read(LOAD);
        scenarios = read(SCENARIOS);
        census = read(CENSUS);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    /** The body of one shell function of load.sh, so an assertion cannot be satisfied by another function's text. */
    private static String function(String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(load);
        assertThat(start.find()).as("load.sh defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(load);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return load.substring(start.end(), end.start());
    }

    /** The k6 instances' own command, from their docker run to its last continued line. */
    private static String generatorCommand() {
        int start = load.indexOf("dock run -d --name \"$CLUSTER-load-k6-$i\"");
        assertThat(start).as("load.sh starts the generator's container").isNotNegative();
        int end = load.indexOf("|| cannot \"k6 could not start\"", start);
        assertThat(end).as("the generator's command ends").isGreaterThan(start);
        return load.substring(start, end);
    }

    private static Map<String, String> operations() {
        Map<String, String> operations = new LinkedHashMap<>();
        Matcher operation = OPERATION.matcher(read(PROFILE));
        while (operation.find()) {
            operations.put(operation.group(1), operation.group(0));
        }
        return operations;
    }

    /** One field of an operation's line: a quoted string whole, commas included, or a bare value up to its comma. */
    private static String field(String line, String name) {
        Matcher value = Pattern.compile("\"" + name + "\": (?:\"([^\"]*)\"|([^,}]*))").matcher(line);
        if (!value.find()) {
            return null;
        }
        return value.group(1) != null ? value.group(1) : value.group(2).trim();
    }

    @Test
    @DisplayName("the generator is k6, pinned by digest, watched for a moved tag or a newer release, and outside the build graph")
    void theGeneratorIsPinnedAndWatched() {
        String pins = read(PINS);
        assertThat(pins).containsPattern("(?m)^K6_REPOSITORY=grafana/k6$").containsPattern("(?m)^K6_VERSION=[0-9]+\\.[0-9]+\\.[0-9]+$")
                .containsPattern("(?m)^K6_DIGEST=sha256:[0-9a-f]{64}$");
        assertThat(read("infra/scripts/check-pinned-images.sh")).contains("check k6 \"$K6_REPOSITORY\" \"$K6_VERSION\" \"$K6_DIGEST\"");
        assertThat(load).as("run by the digest, never by a tag").contains("K6_IMAGE=\"$K6_REPOSITORY@$K6_DIGEST\"")
                .doesNotContain("grafana/k6:");
        assertThat(read("gradle/libs.versions.toml") + read("gradle/verification-metadata.xml")).as("nothing of k6 in the build graph")
                .doesNotContain("k6");
    }

    @Test
    @DisplayName("the model is open: every scenario an arrival-rate executor, read from what k6 will run before anything is built")
    void theModelIsOpenAndReadBeforeAnyCluster() {
        assertThat(read(MODEL)).contains("executor: 'constant-arrival-rate'").doesNotContain("constant-vus");
        assertThat(scenarios).contains("import { openModel } from './model.js';")
                .contains("scenarios: Object.fromEntries(driven.map((operation) => [operation.name, openModel(operation, DURATION)])),");
        assertThat(harness.indexOf("    load_preflight\n")).as("the pre-flight runs before the images are built")
                .isPositive().isLessThan(harness.indexOf("step build-images"));
        String preflight = function("load_preflight");
        assertThat(preflight).as("what k6 will run, read from k6 itself").contains("\"$K6_IMAGE\" inspect")
                .contains("grep -v '^\\(constant\\|ramping\\)-arrival-rate$'")
                .contains("fail \"the generator dropped its open model")
                .contains("[ \"$scenarios\" = \"$wanted\" ] || fail")
                .contains("'\"insecureSkipTLSVerify\": true'").contains("'\"rps\": null'");
    }

    @Test
    @DisplayName("every correctness page and every marked census clause is read every 30 s in one read-only repeatable-read snapshot; unread is a failure")
    void theSamplerReadsEveryPageAndClauseAndNeverPassesBlind() {
        String setup = function("load_setup");
        assertThat(setup).as("the pages are SLOS.md section 3's, each a rule the cluster loaded")
                .contains("sed -n '/^## 3\\. /,/^## 4\\. /s/^| `\\([A-Za-z]*\\)` |.*/\\1/p' \"$ROOT/docs/architecture/SLOS.md\")")
                .contains("fail \"the correctness page $page is not a rule the cluster's Prometheus loaded\"")
                .contains("- alert: CorrectnessUnverifiable$")
                .contains("printf 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\\n'")
                .contains("awk '/^-- \\[sampled during load\\]$/")
                .contains("load_sample 0");
        String sample = function("load_sample");
        assertThat(sample).as("a page firing fails the sample")
                .contains("ALERTS{alertstate=\\\"firing\\\",alertname=~\\\"$LOAD_PAGE_REGEX\\\"}").contains("s_verdict=FAIL");
        assertThat(sample).as("a sample that cannot read is not a pass: Prometheus silent, a replica unscraped, a gauge unreadable, a clause unprinted")
                .contains("s_detail=\"Prometheus did not answer\"").contains("[ \"$s_up\" != \"$s_ready\" ]")
                .contains("grep -q '\"result\":\\[\\]' \"$s_out.gauges.json\"").contains("[ \"$s_verdicts\" != \"$LOAD_CLAUSES\" ]")
                .contains(">> \"$WORK/load-sample-failed\"");
        assertThat(function("load_sampler")).contains("started + 30");
        assertThat(function("load_run")).as("a failing sample stops the load and fails the run")
                .contains("if [ -s \"$WORK/load-sample-failed\" ]; then").contains("load_generators stop")
                .contains("fail \"a sample failed during the load, which stopped it");
        String slos = read("docs/architecture/SLOS.md");
        Matcher page = Pattern.compile("(?m)^\\| `([A-Za-z]+)` \\|").matcher(slos.substring(slos.indexOf("## 3. "), slos.indexOf("## 4. ")));
        int pages = 0;
        while (page.find()) {
            pages++;
        }
        assertThat(pages).as("the pages the sampler reads, as SLOS.md section 3 lists them").isGreaterThanOrEqualTo(20);
    }

    @Test
    @DisplayName("the clauses read during load are census.sql's committed facts: trial balance, entries, one effect per key, dead letters, the projection")
    void theMarkedClausesAreCommittedFacts() {
        Matcher marked = MARKED.matcher(census);
        List<String> names = new ArrayList<>();
        while (marked.find()) {
            String statement = marked.group(2);
            Matcher name = Pattern.compile("'([a-z][^']+)'").matcher(statement.substring(statement.indexOf("END,")));
            assertThat(name.find()).as("a marked clause names itself: %s", statement).isTrue();
            names.add(name.group(1));
            assertThat(statement).as("'%s' needs no count from the client and reads no server statistic", name.group(1))
                    .doesNotContain(":expected").doesNotContainPattern(":'[a-z_]+'").doesNotContain("pg_stat_");
        }
        assertThat(names).as("the marked clauses").contains("trial balance zero per currency", "every entry balances",
                "one journal entry per posting scope", "one transfer per rehearsal reference", "one capture posting per captured card attempt",
                "no event dead-lettered", "nothing parked", "balances explain the journal");
        assertThat(census.split("-- \\[sampled during load]", -1)).as("every marker found its clause").hasSize(names.size() + 1);
        assertThat(census).as("the clauses that need the client's counts or a quiet platform stay unmarked")
                .contains("rows = :expected_transfers").contains("count(*) = :expected_topups + :expected_checkouts");
    }

    @Test
    @DisplayName("the samples must cover the load, and the generator must hold its rate: dropped iterations, arrivals and its CPU judged")
    void theRunIsJudgedOnCoverageAndTheGeneratorsOwnSaturation() {
        String judge = function("load_judge");
        assertThat(judge).as("covered: none missing at either end, no gap over 45 s, every sample PASS")
                .contains("first - start > 45").contains("end - last > 45").contains("gap > 45")
                .contains("fail \"the samples do not cover the load");
        assertThat(judge).as("the generator, never trusted to have kept up")
                .contains("[ \"$dropped\" = 0 ] || fail \"the generator dropped $dropped iterations")
                .contains("$3 < want * 0.98 || $3 > want * 1.02 + 1").contains("(c < 90)")
                .as("errors judged against each operation's objective, never left unread")
                .contains("chance=$(load_errors_chance \"$failed\" \"$attempts\" \"$target\")").contains("(c >= 0.01)");
        assertThat(generatorCommand()).as("each instance's CPU is bounded, so its saturation can be read").contains("--cpus 1");
        assertThat(generatorCommand()).as("the generator arrives from several addresses, each running its exact share of every rate")
                .contains("--execution-segment \"$i/$LOAD_GENERATORS:$((i + 1))/$LOAD_GENERATORS\"")
                .contains("--execution-segment-sequence \"$sequence\"");
        assertThat(function("load_report")).as("the instances' percentiles taken from their samples together, never averaged")
                .contains("gzip -dc \"$series\"").contains("-v m=\"latency_$op\"").contains("sort -k1,1 -k2,2g");
        assertThat(function("load_statistics")).as("PostgreSQL's statistics and the containers every 10 s")
                .contains("statistics.sql").contains("statements.sql").contains("dock stats --no-stream").contains("started + 10");
        assertThat(function("load_census")).as("the saturation signals exported, and absent ones refused")
                .contains("/api/v1/query_range").contains("fail \"saturation signals absent from Prometheus");
        assertThat(read("deploy/overlays/kind/postgres.yaml")).contains("shared_preload_libraries=pg_stat_statements");
        assertThat(function("load_setup")).as("the extension in the maintenance database alone")
                .contains("pg_admin -c \"CREATE EXTENSION IF NOT EXISTS pg_stat_statements\"");
        assertThat(function("pg_admin")).contains("-d postgres");
    }

    @Test
    @DisplayName("one effect per key: the same-key retries are sent before and after the first answer, judged, and the census takes the generator's counts")
    void oneEffectPerKeyIsTriedAndCounted() {
        assertThat(read(PROFILE)).contains("\"retryShare\": 0.05");
        assertThat(scenarios).contains("const RETRY_EVERY = Math.round(1 / profile.retryShare);")
                .as("half beside the first, before any answer; half after it").contains("http.batch([req, req])")
                .contains("const first = resolve(op, req, accept, null, primary);")
                .as("an answer that is not definitive is asked again under the same key, bounded, never under a new one")
                .contains("response = send(op, req, primary);").contains("const RESOLVE_MS = 60000;");
        String judge = function("load_judge");
        assertThat(judge).contains("[ \"$unresolved\" = 0 ] || fail").contains("[ \"$agreed\" = \"$retried\" ] || fail")
                .contains("[ \"$retried\" -ge 1 ] || fail");
        assertThat(harness).as("the census counts the load's movements as the generator was told they succeeded")
                .contains("SMOKE_TRANSFERS + ${LOAD_TRANSFERS:-0}").contains("${LOAD_TOPUPS:-0}").contains("${LOAD_CHECKOUTS:-0}")
                .contains("${LOAD_WALLET_MINOR:-0}");
        assertThat(function("load_census")).contains("census load").contains("load_sample final");
    }

    @Test
    @DisplayName("the generator reaches only the run's own cluster, through its edge, holding the fixture and the edge's CA and nothing else")
    void theGeneratorReachesOnlyTheRunsOwnClusterThroughItsEdge() {
        String generator = generatorCommand();
        assertThat(generator).contains("--network kind").contains("--add-host \"public.finapp.test:$NODE_IP\"")
                .contains("SSL_CERT_FILE=/generator/edge-ca.crt").contains("-v \"$(dpath \"$LOAD_GEN\"):/generator:ro\"")
                .doesNotContain("$CLIENT").doesNotContain("$WORK\"");
        assertThat(function("load_setup")).contains("cp \"$CLIENT/load-fixture.json\" \"$CLIENT/edge-ca.crt\" \"$LOAD_GEN/\"");
        assertThat(scenarios).contains("insecureSkipTLSVerify: false").contains("const BASE = fixture.publicBase;");
        assertThat(function("load_target")).as("refused unless the node is the run's own cluster's")
                .contains("{{index .Config.Labels \"io.x-k8s.kind.cluster\"}}").contains("[ \"$owner\" = \"$CLUSTER\" ] || fail");
        assertThat(harness).as("kubectl runs inside the node, with its own admin.conf: no host kubeconfig, no context to pick")
                .contains("dock exec -i \"$NODE\" kubectl --kubeconfig=/etc/kubernetes/admin.conf \"$@\"");
        assertThat(load).as("the load scope speaks to the cluster only through kube").doesNotContain("kubectl ");
        for (String line : load.split("\n")) {
            if (line.contains("cp ") && line.contains("$LOAD_ART")) {
                assertThat(line).as("nothing generated is copied into the artefacts")
                        .doesNotContain("load-fixture.json").doesNotContain("provider-key").doesNotContain("state.properties");
            }
        }
        assertThat(read(CLIENT)).as("the client prints counts, never a credential")
                .contains("System.out.println(\"load fixture customers=\" + count + \" funded=\" + count + \" funded_minor=\" + fundedMinor);")
                .contains("System.out.println(\"load customers registered=\" + count);");
    }

    @Test
    @DisplayName("the demonstration profile is the capacity model's mix, and every operation it does not drive says why and names a live task")
    void theDemonstrationProfileIsTheCapacityModels() {
        Map<String, String> operations = operations();
        assertThat(operations.keySet()).as("every operation of the section 4.3 mix the platform carries")
                .containsExactlyInAnyOrder("transfer", "checkout", "topup", "read", "signin", "merchant", "callbacks", "settlement", "bnpl",
                        "lending", "credit");
        String model = read("docs/architecture/CAPACITY_MODEL.md");
        Matcher share = Pattern.compile("transfers ([0-9.]+)%, card or checkout payments ([0-9.]+)%, top-ups and withdrawals ([0-9.]+)%")
                .matcher(model);
        assertThat(share.find()).as("the demonstration's split, read from CAPACITY_MODEL.md section 1").isTrue();
        Map<String, Double> perSecond = Map.of("transfer", 5 * Double.parseDouble(share.group(1)) / 100,
                "checkout", 5 * Double.parseDouble(share.group(2)) / 100, "topup", 5 * Double.parseDouble(share.group(3)) / 100,
                "read", 50.0);
        perSecond.forEach((name, wanted) -> assertThat(Double.parseDouble(field(operations.get(name), "perSecond")))
                .as("%s at the capacity model's rate", name).isCloseTo(wanted, org.assertj.core.data.Offset.offset(0.001)));
        for (Map.Entry<String, String> operation : operations.entrySet()) {
            String line = operation.getValue();
            if (line.contains("\"generator\": \"k6\"")) {
                double rate = Double.parseDouble(field(line, "rate"));
                String per = field(line, "per");
                double seconds = Double.parseDouble(per.substring(0, per.length() - 1)) * (per.endsWith("m") ? 60 : 1);
                assertThat(rate / seconds).as("%s: its rate and its per-second figure agree", operation.getKey())
                        .isCloseTo(Double.parseDouble(field(line, "perSecond")), org.assertj.core.data.Offset.offset(0.005));
            }
        }
        Map<String, String> statuses = EvidenceCitations.backlogStatuses(root.resolve("docs/project/BACKLOG.md"));
        TreeSet<String> undriven = new TreeSet<>();
        for (Map.Entry<String, String> operation : operations.entrySet()) {
            if (!operation.getValue().contains("\"generator\": \"none\"")) {
                continue;
            }
            undriven.add(operation.getKey());
            String reason = field(operation.getValue(), "reason");
            assertThat(reason).as("%s says why it is not driven", operation.getKey()).isNotBlank();
            Matcher task = Pattern.compile("P\\d+-(?:TSK|TST)-\\d{3}").matcher(reason);
            assertThat(task.find()).as("%s names the task that drives it", operation.getKey()).isTrue();
            assertThat(statuses.get(task.group())).as("%s's task %s is live", operation.getKey(), task.group())
                    .isIn(EvidenceCitations.LIVE_STATUSES);
        }
        assertThat(undriven).as("what this harness leaves undriven, and nothing else").containsExactly("bnpl", "credit", "lending");
    }

    @Test
    @DisplayName("the platform's own signals are rehearsed on the full cluster: a migrator Job planted to fail pages twice and is routed, its lines in Loki scrubbed, every platform stream swept for every needle beside its control")
    void thePlatformsOwnSignalsAreRehearsed() {
        String telemetry = read("infra/scripts/kind-rehearsal/telemetry.sh");
        String scope = function("load_scope");
        assertThat(scope).as("the load scope plants before its load and reads after its census")
                .contains("step platform-signals-plant\n    platform_signals_plant").contains("step platform-signals\n    platform_signals\n");
        assertThat(scope.indexOf("platform_signals_plant")).isLessThan(scope.indexOf("load_run"));
        assertThat(scope.indexOf("    platform_signals\n")).isGreaterThan(scope.indexOf("load_census"));
        String plant = shellFunction(telemetry, "platform_signals_plant");
        assertThat(plant).as("the release's own Job, its image a shell printing migrator-shaped lines with a card number and an amount, exit 10")
                .contains("\"$A/Job.finapp-migrator.yaml\"").contains("card $SIGNALS_MIGRATOR_PAN for $SIGNALS_AMOUNT EUR")
                .contains("exit 10").contains("kube -n finapp delete job finapp-migrator");
        assertThat(plant).as("the edge's needles: a routed and an unrouted path, a query and a header, through the real public edge")
                .contains("\"/v1/me/accounts/$SIGNALS_PAN/balance\" \"/v1/nowhere/$SIGNALS_PAN\"").contains("-H \"X-Card: $SIGNALS_PAN_SPACED\"")
                .contains("--cacert /work/edge-ca.crt");
        String signals = shellFunction(telemetry, "platform_signals");
        assertThat(signals).as("both pages fire through the committed rules and reach the committed routing")
                .contains("signals_await MigratorJobFailed firing").contains("signals_routed MigratorJobFailed")
                .contains("signals_await MigratorJobOverdue firing").contains("signals_routed MigratorJobOverdue");
        assertThat(shellFunction(telemetry, "signals_routed")).contains("\"name\":\"placeholder-page\"");
        assertThat(signals).as("the failed run's lines in Loki, scrubbed")
                .contains("'{service_name=\"finapp-migrator\"}'").contains("MIGRATION_FAILED").contains("k8s_container_exit_code")
                .contains("grep -qF '[scrubbed]' \"$ART/signals-loki-migrator.json\"");
        assertThat(signals).as("every platform stream swept for every needle, in its lines and the edge's kept path, beside what was read")
                .contains("for stream in finapp-edge finapp-migrator finapp-collector finapp-cluster-watch; do")
                .contains("|~ \\\"$pattern\\\"").contains("RequestPath=~\\\".*($pattern).*\\\"")
                .contains("fail \"signals: the sweep read no record of $stream").contains("fail \"signals: a needle in $stream");
        assertThat(signals).as("a planted control in each stack stream found scrubbed, and the edge's planted path found scrubbed")
                .contains("for stream in finapp-collector finapp-cluster-watch; do").contains(":4319/v1/logs")
                .contains("RequestPath=~\".*scrubbed.*\"");
        assertThat(shellFunction(telemetry, "signals_loki")).as("a query Loki refused is not a sweep that found nothing")
                .contains("\"status\":\"success\"").contains("echo refused");
        assertThat(signals).as("put back: the release's Job run again, both pages cleared")
                .contains("apply_files \"$A/Job.finapp-migrator.yaml\"").contains("signals_await MigratorJobFailed none")
                .contains("signals_await MigratorJobOverdue none");
    }

    /** The body of one shell function of a sourced harness file. */
    private static String shellFunction(String text, String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(text);
        assertThat(start.find()).as("the file defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(text);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return text.substring(start.end(), end.start());
    }

    @Test
    @DisplayName("each probe the entry names is wired: the open model dropped, the sampler disabled, an unbalanced entry planted mid-run")
    void everyProbeIsWired() {
        assertThat(function("load_preflight")).contains("if [ \"$PROBE\" = closed-model ]; then").contains("executor: 'constant-vus'");
        assertThat(function("load_run")).contains("if [ \"$PROBE\" = sampler-disabled ]; then")
                .contains("if [ \"$PROBE\" = unbalanced-entry ] && [ -z \"$planted\" ] && [ \"$elapsed\" -ge 60 ]; then");
        String plant = function("load_plant_unbalanced");
        assertThat(plant).as("one debit line and no credit, by the superuser around the ledger's trigger")
                .contains("SET LOCAL session_replication_role = replica;").contains("'DEBIT', 1, 'EUR', 2, 0 FROM entry;")
                .doesNotContain("'CREDIT'");
        assertThat(function("load_run").indexOf("load_sampler &")).as("the sampler starts unless the probe disables it")
                .isGreaterThan(function("load_run").indexOf("sampler-disabled"));
    }
}
