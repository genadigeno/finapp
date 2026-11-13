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
 *       to fail pages twice and is routed, its lines reach Loki scrubbed, and every platform stream is swept for every needle;
 *   <li>the staged profiles of {@code PHASE_16_PLAN.md} section 5.1 (normal, peak, sustained, stress, isolation) are the target's mix at
 *       its peak, scaled per stage, still an open model, and judged on the sum of their stages; only the stress runs past the break;
 *   <li>the fleet runs at three to seven replicas in the run's own rendered overlay, at the connection budget's formula;
 *   <li>the three operations resting on a credit approval (BNPL, lending repayments, loan decisions) stay undriven, each saying that no
 *       kind deployment can give one;
 *   <li>every run cuts its measures by stage in the host's seconds: the client's per operation, the pods' cgroups, the JVMs' and the
 *       pools' sums, the database's time;
 *   <li>the slow-statement probe drops the relay's index on the run's own server and puts it back as its migration defines it.
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
                .contains("scenarios: Object.fromEntries(driven.map((operation) => [operation.name, openModel(operation, DURATION, GRACEFUL_STOP, STAGES)])),");
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
                .contains("n = got + lost; if (n < want * 0.98 || n > want * 1.02 + 1)").contains("(c < 90)")
                .as("each operation's 5xx recorded beside the chance its objective explains them, never left unread")
                .contains("chance=$(load_errors_chance \"$server\" \"$attempts\" \"$target\")").contains("log \"recorded $op: 5xx $server");
        assertThat(judge).as("the settlement file taken whole: its captures counted by type, every line an item, every capture allocated")
                .contains("count(DISTINCT l.id) FILTER (WHERE l.line_type = 'CAPTURE')")
                .contains("[ \"${items:-0}\" = \"${lines:-0}\" ] || fail")
                .contains("[ \"${allocated:-0}\" = \"$LOAD_SETTLEMENT_LINES\" ] || fail");
        assertThat(function("load_report")).as("every answer that was not a success counted per operation and status")
                .contains("k6/statuses.tsv").contains("$1 == \"http_reqs\" && $c[\"status\"] !~ /^2/");
        assertThat(generatorCommand()).as("each instance's CPU is bounded, so its saturation can be read").contains("--cpus 1");
        assertThat(generatorCommand()).as("the generator arrives from several addresses, each running its exact share of every rate")
                .contains("--execution-segment \"$i/$LOAD_GENERATORS:$((i + 1))/$LOAD_GENERATORS\"")
                .contains("--execution-segment-sequence \"$sequence\"");
        assertThat(function("load_report")).as("the instances' percentiles taken from their samples together, never averaged")
                .contains("gzip -dc \"$series\"").contains("-v m=\"latency_$op\"").contains("sort -k1,1 -k2,2g");
        assertThat(function("load_statistics")).as("PostgreSQL's statistics and the containers every 10 s")
                .contains("statistics.sql").contains("statements.sql").contains("dock stats --no-stream").contains("started + 10");
        assertThat(function("load_census")).as("a statistics read at least every 12 s of the load, or the run fails")
                .contains("/ 12 )) ] || fail \"PostgreSQL's statistics were read");
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
                .contains("response = send(op, req, primary);").contains("const RESOLVE_MS = RESOLVE_SECONDS * 1000;");
        assertThat(function("load_preflight")).as("the bound read from the code: twice the payment sweep's dispatched age and five minutes")
                .contains("finapp\\.payments\\.sweeper\\.dispatched-age:PT\\([0-9]*\\)M}")
                .contains("app/src/main/java/com/finapp/app/payments/PaymentSweeperSchedule.java")
                .contains("LOAD_RESOLVE_SECONDS=$((2 * age * 60 + 300))").contains("SWEEPER_DISPATCHED_?AGE");
        assertThat(generatorCommand()).contains("-e \"LOAD_RESOLVE_SECONDS=$LOAD_RESOLVE_SECONDS\"");
        assertThat(scenarios).as("two sessions under one key are never explained away; a checkout abandoned only when every answer was a"
                        + " replay without its token, and its retried key still judged alike")
                .contains("if (created.length === 2 && json(created[1], 'checkoutId') !== checkoutId) {\n        conclude(op, UNRESOLVED, started);")
                .contains("if (!token) {\n        if (created.length === 2) {\n            agreed(op, created, 'checkoutId');\n        }\n"
                        + "        conclude(op, ABANDONED, started);");
        assertThat(function("load_judge")).as("abandoned sessions recorded, the unresolved still failing the run")
                .contains("log \"recorded $op: $abandoned sessions abandoned");
        assertThat(scenarios).as("a payment in progress read until it concludes, within the bound; an iteration let finish within it")
                .contains("throw new Error('LOAD_RESOLVE_SECONDS is required").contains("Date.now() - since >= RESOLVE_MS")
                .contains("const GRACEFUL_STOP = `${RESOLVE_SECONDS + 120}s`;").contains("openModel(operation, DURATION, GRACEFUL_STOP, STAGES)")
                .doesNotContain("POLL_SECONDS");
        assertThat(read("infra/scripts/kind-rehearsal/load/model.js")).contains("openModel(operation, duration, gracefulStop, stages)")
                .contains("        gracefulStop,\n");
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
            // Each rests on a credit approval, and on kind no bureau can be configured, so none can be given (INV-CRD-09, INV-CRD-10).
            assertThat(reason).as("%s: the reason is the bureau no kind deployment can configure", operation.getKey())
                    .contains("bureau").contains("unresolved question").contains("INV-CRD-10");
        }
        assertThat(undriven).as("what this harness leaves undriven, and nothing else").containsExactly("bnpl", "credit", "lending");
    }

    @Test
    @DisplayName("the staged profiles are the target's mix at its peak, scaled per stage as PHASE_16_PLAN.md section 5.1 shapes them, and judged on the sum of their stages")
    void theStagedProfilesAreTheTargetsMixScaledByStage() {
        Map<String, String> demonstration = operations();
        for (String name : List.of("normal", "peak", "sustained", "stress", "isolation")) {
            String profile = read("infra/scripts/kind-rehearsal/load/" + name + ".json");
            Map<String, String> staged = new LinkedHashMap<>();
            Matcher operation = OPERATION.matcher(profile);
            while (operation.find()) {
                staged.put(operation.group(1), operation.group(0));
            }
            assertThat(staged.keySet()).as("%s drives what the demonstration drives", name).isEqualTo(demonstration.keySet());
            for (Map.Entry<String, String> entry : staged.entrySet()) {
                String line = entry.getValue();
                assertThat(field(line, "generator")).as("%s: %s by the same generator", name, entry.getKey())
                        .isEqualTo(field(demonstration.get(entry.getKey()), "generator"));
                if (line.contains("\"generator\": \"k6\"")) {
                    assertThat(field(line, "per")).as("%s: %s counted per 1000 s", name, entry.getKey()).isEqualTo("1000s");
                    assertThat(Double.parseDouble(field(line, "rate")) / 1000).as("%s: %s's rate and per-second figure", name, entry.getKey())
                            .isCloseTo(Double.parseDouble(field(line, "perSecond")), org.assertj.core.data.Offset.offset(0.001));
                    assertThat(field(line, "preAllocatedVUs")).as("%s: %s's users all allocated before the load", name, entry.getKey())
                            .isEqualTo(field(line, "maxVUs"));
                }
            }
            assertThat(profile).as("%s's stages", name).containsPattern("(?m)^  \"stages\": \\[\\[.*]],$");
        }
        // The target's peak, CAPACITY_MODEL.md section 3.1: a day's volume over 36,000 s.
        Map<String, Double> peak = Map.of("transfer", 100_000 / 36_000.0, "checkout", 200_000 / 36_000.0, "topup", 40_000 / 36_000.0,
                "read", 2_000_000 / 36_000.0, "signin", 240_000 / 36_000.0, "merchant", 400_000 / 36_000.0);
        Matcher sustained = OPERATION.matcher(read("infra/scripts/kind-rehearsal/load/sustained.json"));
        while (sustained.find()) {
            if (peak.containsKey(sustained.group(1))) {
                assertThat(Double.parseDouble(field(sustained.group(0), "perSecond"))).as("%s at the target's peak", sustained.group(1))
                        .isCloseTo(peak.get(sustained.group(1)), org.assertj.core.data.Offset.offset(0.001));
            }
        }
        assertThat(read("infra/scripts/kind-rehearsal/load/normal.json")).as("normal: the average, 36,000 / 86,400 of the peak, 30 minutes")
                .contains("\"stages\": [[1800, 0.4167]],");
        assertThat(read("infra/scripts/kind-rehearsal/load/peak.json")).as("peak: a step to the peak from the normal profile")
                .contains("\"stages\": [[1800, 0.4167], [1800, 1.0]],");
        assertThat(read("infra/scripts/kind-rehearsal/load/sustained.json")).as("sustained: the peak held two hours")
                .contains("\"stages\": [[1800, 0.4167], [7200, 1.0]],");
        String stress = read("infra/scripts/kind-rehearsal/load/stress.json");
        Matcher steps = Pattern.compile("\\[300, ([0-9.]+)]").matcher(stress.substring(stress.indexOf("\"stages\"")));
        List<Double> factors = new ArrayList<>();
        while (steps.find()) {
            factors.add(Double.parseDouble(steps.group(1)));
        }
        assertThat(factors).as("stress: nine 5-minute steps from a quarter of the peak to one and a half times it").hasSize(9).first().isEqualTo(0.25);
        for (int i = 1; i < factors.size(); i++) {
            assertThat(factors.get(i) / factors.get(i - 1)).as("stress: step %d is 25%% above the one before", i)
                    .isCloseTo(1.25, org.assertj.core.data.Offset.offset(0.001));
        }
        assertThat(stress).contains("  \"stress\": true,");
        for (String name : List.of("demonstration", "normal", "peak", "sustained", "isolation")) {
            assertThat(read("infra/scripts/kind-rehearsal/load/" + name + ".json")).as("only the stress profile runs past the break")
                    .doesNotContain("\"stress\"");
        }
        assertThat(read(MODEL)).as("a staged profile is still an open model: ramping arrivals, a one-second step, the rate rounded per time unit")
                .contains("executor: 'ramping-arrival-rate',").contains("steps.push({ target, duration: '1s' });")
                .contains("return Math.round(operation.rate * stage[1]);");
        String judge = function("load_judge");
        assertThat(judge).as("each operation's arrivals held to the sum over the stages, the stress's dropped iterations counted as arrivals")
                .contains("want=$(load_expected \"$op\")").contains("[ \"$stress\" != true ] || lost=")
                .as("only the stress profile's dropped iterations are recorded rather than failed")
                .contains("if [ \"$stress\" = true ]; then\n        [ \"$dropped\" = 0 ] || log \"recorded stress: the generator dropped")
                .contains("else\n        [ \"$dropped\" = 0 ] || fail \"the generator dropped $dropped iterations");
        assertThat(function("load_expected")).as("as model.js rounds: whole iterations per time unit times the stage's seconds")
                .contains("int(rate * $2 + 0.5)").contains("n += target * $1 / unit");
        assertThat(scenarios).as("a shortened run shortens every stage in proportion, as the shell does")
                .contains("Math.max(2, Math.round(stage[0] * scale))");
        assertThat(function("load_stages")).contains("t = int(s[i] * scale + 0.5); if (t < 2) t = 2");
    }

    @Test
    @DisplayName("the fleet runs at REHEARSAL_LOAD_REPLICAS, three to seven, in the run's own rendered overlay at the connection budget's formula")
    void theFleetRunsAtTheBudgetsFormulaInTheRunsOwnCopy() {
        assertThat(function("load_preflight")).as("today's minimum to today's ceiling, nothing else")
                .contains("case $LOAD_REPLICAS in 3 | 4 | 5 | 6 | 7) ;; *) cannot");
        String patch = function("load_replicas_patch");
        assertThat(patch).as("N + 2 x maxSurge + 1 instances, each 7 + 1 connections, 12 reserved")
                .contains("instances=$((LOAD_REPLICAS + 3))").contains("connections=$((instances * 8 + 12))")
                .contains("FINAPP_DB_INSTANCES: \\\"$instances\\\"").contains("max_connections=$connections")
                .contains("minReplicas: $LOAD_REPLICAS").contains("maxReplicas: $LOAD_REPLICAS")
                .contains("|| cannot \"the rendered kind overlay could not be set");
        assertThat(harness.indexOf("[ \"$SCOPE\" != load ] || load_replicas_patch \"$A\"")).as("patched before anything is applied")
                .isPositive().isLessThan(harness.indexOf("apply_files \"$file\""));
        assertThat(function("load_target")).as("the load waits for the fleet at its count")
                .contains("= \"$LOAD_REPLICAS\" ]").contains("fail \"the fleet did not reach $LOAD_REPLICAS ready replicas");
        assertThat(read("deploy/overlays/kind/kustomization.yaml")).as("the committed overlay stays the three-replica rehearsal")
                .contains("FINAPP_DB_INSTANCES=6").contains("value: 3");
    }

    @Test
    @DisplayName("every run cuts its measures by stage, in the host's seconds: the client's per operation, the platform's per signal, the pods' cgroups and the database's time")
    void everyRunCutsItsMeasuresByStage() {
        String report = function("load_report");
        assertThat(report).as("the client's side per operation and stage, from the series, on the clock the samples carry")
                .contains("-f \"$LOAD_SRC/stages.awk\" > \"$WORK/stage-counts.tsv\"").contains("> \"$LOAD_ART/k6/stages.tsv\"")
                .contains("-v speed=\"$LOAD_SPEED\"").contains("\"$LOAD_ART/clock.tsv\"");
        assertThat(function("load_run")).as("the clocks read either side of the load").contains("load_clock start").contains("load_clock end");
        assertThat(function("load_statistics")).as("every pod's cgroup and the database's time every 10 s")
                .contains("dock exec \"$NODE\" sh -c \"$LOAD_CGROUPS\"").contains("-v epoch=\"$started\"");
        assertThat(load).as("the cgroups read: CPU, throttling against the limit, memory")
                .contains("cpuacct/$r/cpuacct.usage").contains("throttled_time").contains("memory/$r/memory.usage_in_bytes");
        assertThat(read("infra/scripts/kind-rehearsal/load/statements.sql")).contains("SELECT 'statements_total', coalesce(sum(s.calls), 0)");
        String resources = function("load_resources");
        assertThat(resources).as("the platform's side per stage, failing when it holds no replica CPU or no database time")
                .contains("-f \"$LOAD_SRC/resources.awk\"").contains("cpu_app_cores_per_s").contains("db_statement_seconds_per_s")
                .contains("fail \"the per-stage resources hold no replica CPU or no database time");
        assertThat(read("infra/scripts/kind-rehearsal/load/resources.awk")).contains("secs[n] = f[1] / speed");
        assertThat(read("infra/scripts/kind-rehearsal/load/stages.awk")).contains("upto[n] = upto[n - 1] + f[1] / speed");
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
        assertThat(plant).as("the control before the plant: neither page active, so what fires afterwards is the plant's")
                .contains("for alert in MigratorJobFailed MigratorJobOverdue; do").contains("signals_await \"$alert\" none");
        assertThat(plant.indexOf("signals_await \"$alert\" none")).isLessThan(plant.indexOf("SIGNALS_FROM=$(date +%s)"));
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
        // P16-TSK-007's: the relay's index dropped on the run's own server before the load and put back, as V006 defines it, before the census.
        String run = function("load_run");
        assertThat(run.indexOf("[ \"$PROBE\" != slow-statement ] || load_plant_slow_statement")).as("planted before the load starts")
                .isPositive().isLessThan(run.indexOf("LOAD_STARTED=$(date +%s)"));
        assertThat(run.indexOf("[ \"$PROBE\" != slow-statement ] || load_restore_slow_statement")).as("restored once the load has ended")
                .isGreaterThan(run.indexOf("LOAD_ENDED=$(date +%s)"));
        assertThat(function("load_plant_slow_statement")).contains("DROP INDEX platform.outbox_event_pending");
        String v006 = read("platform/src/main/resources/db/migration/platform/V006__outbox_relay_scheduling.sql").replaceAll("\\s+", " ");
        assertThat(v006).as("the index the probe recreates is V006's").contains(
                "CREATE INDEX outbox_event_pending ON platform.outbox_event (aggregate_id, event_id) WHERE published_at IS NULL;");
        assertThat(function("load_restore_slow_statement")).contains(
                "CREATE INDEX IF NOT EXISTS outbox_event_pending ON platform.outbox_event (aggregate_id, event_id) WHERE published_at IS NULL");
        assertThat(read("deploy/overlays/kind/postgres.yaml") + read("platform/src/main/resources/db/migration/platform/V006__outbox_relay_scheduling.sql"))
                .as("never by a migration or an overlay").doesNotContain("slow-statement");
    }
}
