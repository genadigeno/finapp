package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.ledger.AccountPurpose;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * What the kind rehearsal holds about itself (`P15-TSK-012`, ADR-0120): the pins it runs on, the shape of the test-only overlay that
 * makes it a real TLS and SCRAM rehearsal, the assertions it refuses to run without, and that CI runs it.
 *
 * <p>The rehearsal's own result is a cluster's behaviour, recorded in {@code OPERATIONS_RUNBOOK.md} section 11.28, and nothing here
 * claims it. What a hermetic test can hold is that a later edit does not quietly remove what the run proves.
 */
@Tag("architecture")
@DisplayName("the kind rehearsal holds what it says it holds (P15-TSK-012)")
class KindRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String OVERLAY = "deploy/overlays/kind/";
    private static final String CENSUS = "infra/scripts/kind-rehearsal/census.sql";
    private static final String ROLLS = "infra/scripts/kind-rehearsal/rolls.sh";

    private static Path root;
    private static String harness;
    private static String pins;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = EvidenceCitations.read(root.resolve(HARNESS));
        pins = EvidenceCitations.read(root.resolve(PINS));
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path));
    }

    @Test
    @DisplayName("the node image is the Kubernetes minor the clusters and the schemas declare")
    void theNodeImageIsTheClustersMinor() {
        Matcher kubernetes = Pattern.compile("(?m)^KUBERNETES_VERSION=(1\\.[0-9]+)\\.[0-9]+$").matcher(pins);
        assertThat(kubernetes.find()).as("KUBERNETES_VERSION was read").isTrue();
        Matcher node = Pattern.compile("(?m)^KIND_NODE_VERSION=v(1\\.[0-9]+)\\.[0-9]+$").matcher(pins);
        assertThat(node.find()).as("KIND_NODE_VERSION was read").isTrue();
        assertThat(node.group(1)).as("the rehearsal runs the API the clusters run").isEqualTo(kubernetes.group(1));
        assertThat(pins).containsPattern("(?m)^KIND_NODE_DIGEST=sha256:[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("kind is at least the release whose kindnetd enforces NetworkPolicy")
    void kindEnforcesNetworkPolicy() {
        Matcher kind = Pattern.compile("(?m)^KIND_VERSION=v0\\.([0-9]+)\\.[0-9]+$").matcher(pins);
        assertThat(kind.find()).as("KIND_VERSION was read").isTrue();
        assertThat(Integer.parseInt(kind.group(1))).as("kindnetd enforces NetworkPolicy from v0.24.0").isGreaterThanOrEqualTo(24);
    }

    @Test
    @DisplayName("the harness can run where it will run: it is committed executable, as every script CI calls")
    void theHarnessIsExecutable() throws Exception {
        Process git = new ProcessBuilder("git", "ls-files", "-s", HARNESS).directory(root.toFile()).redirectErrorStream(true).start();
        String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git answered: %s", index).isZero();
        assertThat(index).as("the mode the index holds").startsWith("100755 ");
    }

    @Test
    @DisplayName("nothing the rehearsal generates is written under the repository: its working directory is a temporary one, removed at exit")
    void generatedMaterialStaysOutOfTheRepository() {
        assertThat(harness).contains("WORK=$(mktemp -d)").contains("CA_DIR=\"$WORK/ca\"").contains("rm -rf \"$WORK\"");
        assertThat(harness).as("artefacts are logs and counts; the keys never go there").doesNotContain("cp \"$CA_DIR");
    }

    @Test
    @DisplayName("the test-only PostgreSQL serves TLS from the local CA and admits nothing without it")
    void theTestOnlyPostgresServesTls() {
        String postgres = read(OVERLAY + "postgres.yaml");
        assertThat(postgres).contains("ssl=on").contains("ssl_cert_file=/etc/finapp-postgres-tls/tls.crt")
                .contains("hostssl all all all scram-sha-256").doesNotContain("\n    host ")
                .contains("max_connections=60");
    }

    @Test
    @DisplayName("the test-only broker serves SASL_SSL with SCRAM-SHA-512 and the application uses it, its truststore the local CA")
    void theTestOnlyBrokerServesSaslSsl() {
        assertThat(harness).contains("listeners=SASL_SSL://:9092").contains("listener.name.sasl_ssl.scram-sha-512.sasl.jaas.config")
                .contains("ssl.keystore.type=PEM");
        assertThat(read(OVERLAY + "kafka.yaml")).contains("SCRAM-SHA-512=[name=$KAFKA_APP_USER,password=$KAFKA_APP_PASSWORD]");
        assertThat(read(OVERLAY + "kustomization.yaml")).contains("FINAPP_KAFKA_SECURITYPROTOCOL=SASL_SSL")
                .contains("FINAPP_KAFKA_SSL_TRUSTSTORELOCATION=/etc/finapp-local-ca/ca.crt");
    }

    @Test
    @DisplayName("the census counts each thing the phase's rehearsals end in, read from the database")
    void theCensusCountsItsClauses() {
        String census = read(CENSUS);
        assertThat(census).contains("trial balance zero per currency").contains("one transfer per key").contains("one captured intent per key")
                .contains("one payout per key").contains("the outbox drained").contains("no orphaned idempotency lease")
                .contains("balances explain the journal").contains("one posting per card capture").contains("one posting per payout")
                .contains("the wallets hold what the client put in").contains("no payment intent left non-terminal");
        assertThat(census).as("on the account's own normal side, and a line with no balance row is a difference")
                .contains("l.direction = a.normal_balance").contains("FULL JOIN");
        assertThat(harness).as("the provider's side is compared, and a census that printed nothing is not a green one")
                .contains("one capture key at the provider per card payment").contains("one payout key at the provider per payout")
                .contains("census_lines=");
        assertThat(read("infra/scripts/kind-rehearsal/lost-events.sql")).contains("no event lost to a catalogued handler");
        assertThat(harness).as("GL = ledger is read at every census").contains("client gl-check");
        assertThat(read("infra/scripts/kind-rehearsal/Rehearsal.java")).as("from the application's own judgement of the month")
                .contains("/v1/operator/accounting/gl/balances").contains("the General Ledger does not agree with the journal");
    }

    @Test
    @DisplayName("every assertion the entry names is one the run cannot pass without")
    void theRunAssertsWhatTheEntryNames() {
        assertThat(harness).contains("rollout $label: zero failed requests")
                .contains("the peak of $peak connections exceeds instances x pool")
                .contains("an operator was admitted at the peak")
                .contains("handler window $label: every one of $total events applied exactly once");
        assertThat(harness).as("the window closes when no pod is terminating, and the whole run is checked past the windows")
                .contains("no_pod_terminating 300").contains("handler window, the whole run");
        assertThat(harness).as("a kill that found nothing in flight is not a kill that passed")
                .contains("the kill not exercised").contains("the claim the dead replica held")
                .contains("touch /tmp/hold-payouts");
        assertThat(harness).as("the budget is held per sample, with the reporting pool open, and tried at its ceiling")
                .contains("assert_within_pods").contains("assert_reporting_open").contains("an operator is admitted with the fleet held at its budget");
        assertThat(harness).as("the minReadySeconds probe raises the quota, so a seventh pod can exist").contains("the probe could not raise the quota");
        assertThat(harness).as("every way out tears down").contains("' EXIT");
        assertThat(harness).as("every probe the entry names")
                .contains("max-unavailable").contains("min-ready").contains("skip-replay").contains("no-default-deny");
    }

    @Test
    @DisplayName("an egress and an ingress control beside every denial: a refusal proves nothing while the network is down")
    void everyDenialHasAControl() {
        assertThat(harness).contains("must_not_reach \"1 ingress\" finapp-rehearsal np-outsider probe \"$APP_IP\" 8081")
                .contains("must_reach \"1 control\" finapp-observability np-scraper probe \"$APP_IP\" 8081")
                .contains("must_reach \"1 control\" finapp-edge np-edge probe \"$APP_IP\" 8080")
                .contains("must_not_reach \"2 egress\" finapp \"$TARGET_POD\" np-debug 1.1.1.1 443")
                .contains("must_reach \"2 control\" finapp-rehearsal np-outsider probe 1.1.1.1 443")
                .contains("must_not_reach \"2 egress\" finapp np-migrator probe \"$KAFKA_IP\" 9092");
    }

    @Test
    @DisplayName("the GL mapping the rehearsal activates is the reference chart, every purpose of the ledger mapped")
    @SuppressWarnings("unchecked")
    void theGlMappingIsTheReferenceChart() {
        Map<String, Object> proposal = new Yaml(new SafeConstructor(new LoaderOptions())).load(read("infra/scripts/kind-rehearsal/gl-mapping.json"));
        GlMappingVersion reference = ReferenceGlMapping.version();
        Set<String> rules = new TreeSet<>();
        for (Map<?, ?> rule : (List<Map<?, ?>>) proposal.get("rules")) {
            rules.add(rule.get("purpose") + "|" + rule.get("ownerKind") + "|" + rule.get("glAccountCode"));
        }
        Set<String> expected = new TreeSet<>();
        reference.rules().forEach(rule -> expected.add(rule.purpose() + "|" + rule.ownerKind() + "|" + rule.glAccountCode()));
        assertThat(rules).as("the file the rehearsal proposes is the fixture's chart").isEqualTo(expected);
        Set<String> purposes = new TreeSet<>();
        for (AccountPurpose purpose : AccountPurpose.values()) {
            purposes.add(purpose.name());
        }
        Set<String> mapped = new TreeSet<>();
        reference.rules().forEach(rule -> mapped.add(rule.purpose()));
        assertThat(mapped).as("an activation is refused unless the version is total over the ledger's purposes").isEqualTo(purposes);
    }

    @Test
    @DisplayName("the rotations scope rehearses each rotation the entry names under the workload, with its census and its rollback, and keeps nothing it generates (P15-TSK-020)")
    void theRotationsAssertWhatTheEntryNames() {
        String rotations = read("infra/scripts/kind-rehearsal/rotations.sh");
        assertThat(harness).as("a scope of the harness, after N is deployed and smoked")
                .contains("SCOPE=${REHEARSAL_SCOPE:-release}").contains(". \"$DIR/rotations.sh\"");
        assertThat(rotations).as("every roll is the rollout's, so zero failed requests, the budget and an operator at the peak are asserted on each")
                .contains("rollout \"$1\" \"generation=$GENERATION\" \"\" no").contains("rollout \"$1\" \"config=$CONFIGURATION\" \"\" no");
        assertThat(rotations).as("each rotation and its rollback followed by the census")
                .contains("census database-forward").contains("census database-retired").contains("census database-rollback")
                .contains("census kafka-forward").contains("census kafka-rollback")
                .contains("census callback-forward").contains("census callback-rollback")
                .contains("census key-listed").contains("census key-active").contains("census key-rollback")
                .contains("census certificate");
        assertThat(rotations).as("the database: each alternate holds exactly its carrier's rights, the half-rolled fleet on both, the old users retired")
                .contains("grants_census before").contains("signs in as finapp_app_a, every pod not yet rolled as finapp_app")
                .contains("ALTER ROLE finapp_app PASSWORD NULL");
        assertThat(read("infra/scripts/kind-rehearsal/grants.sql")).contains("holds exactly the rights of").contains("is named by no grant of its own")
                .contains("inheriting, without SET or ADMIN")
                .as("nobody else able to use an alternate's rights, or its carrier's, through any chain of roles (the review's I1)")
                .contains("through any chain of roles").contains("pg_catalog.pg_has_role(r.oid, :'alternate', 'SET')")
                .contains("pg_catalog.pg_has_role(r.oid, :'carrier', 'USAGE')");
        assertThat(rotations).as("four verdicts for each of the four alternates").contains("= 16 ] || fail \"the grant census printed");
        assertThat(rotations).as("the operator's chart pinned by its archive's sha256, a mismatch refused")
                .contains("ESO_CHART_SHA256=").contains("[ \"$eso_chart_sha\" = \"$ESO_CHART_SHA256\" ] || fail");
        assertThat(rotations).as("Kafka: the second user with exactly the first's ACLs before the roll, the first's credential and ACLs removed after it")
                .contains("cmp -s \"$WORK/acls-old.txt\" \"$WORK/acls-new.txt\"").contains("scram_remove \"$old_user\"").contains("kafka_acls remove \"$old_user\"");
        assertThat(rotations).as("the callback window: no delivery refused through the rolls, and the window closing itself with nothing restarted")
                .contains("assert_callbacks callback-new").contains("[ \"$after_old\" = \"0|30\" ]").contains("pods-before-close.txt");
        assertThat(rotations).as("the key: listed with check values, a replaced value refused at start, a v1 row read after, a v2 row after the pointer moved back")
                .contains("FINAPP_MFA_RING_CHECKVALUES").contains("does not give its published check value")
                .contains("client elevate operator 3").contains("client elevate rotated 3");
        assertThat(rotations).as("the certificate renewed early and reloaded with no controller restarted, the previous one restored the same way")
                .contains("ManuallyTriggered").contains("edge-pods-before.txt").contains("wait_served \"$previous\"");
        assertThat(rotations).as("the operator on a cluster: its five observations")
                .contains("eso 1:").contains("eso 2:").contains("eso 3:").contains("eso 4:").contains("eso 5:");
        assertThat(harness + rotations).as("the entry's probes").contains("hmac-previous-dropped").contains("grant-missing");
        assertThat(rotations).as("the store and every generated value stay in the run's temporary directory").contains("STORE=\"$WORK/store\"")
                .doesNotContain("cp \"$STORE").doesNotContain("$ART/store");
        String workflow = read(".github/workflows/ci.yml");
        assertThat(workflow).as("CI runs both scopes, each on its own runner, the rotations' with a bound of its own")
                .contains("          - scope: release\n            timeout: 150").contains("          - scope: rotations\n            timeout: 240")
                .contains("timeout-minutes: ${{ matrix.timeout }}").contains("REHEARSAL_SCOPE: ${{ matrix.scope }}");
    }

    @Test
    @DisplayName("the rolls scope rolls the edge's controllers, the test-only PostgreSQL and the test-only broker to the other image of each pair and back"
            + " under the workload, with the census after each leg, and keeps the data tier's data across a roll (P16-TSK-005)")
    void theRollsAssertWhatTheEntryNames() {
        String rolls = read(ROLLS);
        assertThat(harness).as("a scope of the harness, on release N alone, after N is deployed and smoked")
                .contains("delivery | rolls) ;;").contains("rotations | signals | incidents | delivery | rolls) N_ONLY=1")
                .contains("if [ \"$SCOPE\" = rolls ]; then\n    # shellcheck source=infra/scripts/kind-rehearsal/rolls.sh\n    . \"$DIR/rolls.sh\"");
        assertThat(harness).as("the other image of each pair loaded into the node by its digest")
                .contains("load_tagged \"$TRAEFIK_ROLL_REPOSITORY\" \"$TRAEFIK_ROLL_VERSION\" \"$TRAEFIK_ROLL_DIGEST\"")
                .contains("load_tagged \"$POSTGRES_ROLL_REPOSITORY\" \"$POSTGRES_ROLL_VERSION\" \"$POSTGRES_ROLL_DIGEST\"")
                .contains("load_tagged \"$KAFKA_ROLL_REPOSITORY\" \"$KAFKA_ROLL_VERSION\" \"$KAFKA_ROLL_DIGEST\"");
        assertThat(rolls).as("each component to the other image and back, every step a wanted one")
                .contains("edge_leg roll-edge-to \"$EDGE_TARGET\"").contains("edge_leg roll-edge-back \"$EDGE_PINNED\"")
                .contains("postgres_leg roll-postgres-to \"$POSTGRES_TARGET\"").contains("postgres_leg roll-postgres-back \"$POSTGRES_PINNED\"")
                .contains("kafka_leg roll-kafka-to \"$KAFKA_TARGET\"").contains("kafka_leg roll-kafka-back \"$KAFKA_PINNED\"")
                .contains("if wanted roll-edge; then roll_edge; fi").contains("if wanted roll-postgres; then roll_postgres; fi")
                .contains("if wanted roll-kafka; then roll_kafka; fi");
        assertThat(rolls.split("\n    census \"\\$label\"\n", -1)).as("the census after every leg of the three").hasSize(4);
        assertThat(rolls).as("the edge: zero failed requests through both hosts, counted until no controller terminates, every controller ready"
                        + " throughout, each running the version asked as its binary reports it")
                .contains("[ \"$failed\" = 0 ] || fail \"edge roll $label: $failed failed requests through the edge")
                .contains("start_workload \"$label\" \"\" operator-edge").contains("edge_none_terminating 300 || fail")
                .contains("no request reached the application through the operator host").contains("maxUnavailable 0 keeps every one")
                .contains("-c edge -- traefik version");
        assertThat(rolls).as("PostgreSQL: retried by key and none unresolved, the server restarted onto the version asked on one volume claim, no replica"
                        + " restarted for it")
                .contains("start_workload \"$label\" retry").contains("requests stayed unresolved").contains("the server's start time did not move")
                .contains("SHOW server_version").contains("a replica restarted for the database's restart")
                .contains("the server's volume claim was replaced");
        assertThat(rolls).as("the broker: zero failed requests, every replica ready, the outbox seen holding rows, the version asked, one volume claim")
                .contains("and the broker is on no request's path").contains("and a broker away is not a refusal")
                .contains("the outbox never held a row").contains("kafka-topics.sh --version").contains("the broker's volume claim was replaced");
        assertThat(rolls).as("the entry's probe, with the slow start maxUnavailable 0 exists for")
                .contains("PROBE edge-max-unavailable: zero failed requests").contains("/spec/strategy/rollingUpdate/maxUnavailable")
                .contains("readinessProbe/initialDelaySeconds\\\",\\\"value\\\":30");
        for (String overlay : List.of("postgres.yaml", "kafka.yaml")) {
            assertThat(read(OVERLAY + overlay)).as("%s keeps its data on a volume claim, which a roll that replaces the pod keeps", overlay)
                    .contains("  volumeClaimTemplates:\n    - metadata:\n        name: data\n").doesNotContain("- name: data\n          emptyDir");
        }
        assertThat(read("infra/scripts/kind-rehearsal/Rehearsal.java")).as("the operator host read under its controllers' roll")
                .contains("\"operator-edge\".equals(System.getenv(\"WORKLOAD_EDGE\"))").contains("get(operatorBase, \"/v1/operator/events/dead-letters\", null), 401");
        assertThat(read(".github/workflows/ci.yml")).as("CI runs the scope nightly, on a runner of its own")
                .contains("          - scope: rolls\n            timeout: 150\n            nightly: true");
    }

    @Test
    @DisplayName("each roll target is pinned by digest, the deployed pin's repository and release line with another patch, and its tag is watched (P16-TSK-005)")
    void eachRollTargetIsAnotherPatchOfItsDeployedPin() {
        Matcher postgres = Pattern.compile("image: postgres:(\\S+)@(sha256:[0-9a-f]{64})").matcher(read(OVERLAY + "postgres.yaml"));
        assertThat(postgres.find()).as("the kind overlay pins its PostgreSQL").isTrue();
        Matcher kafka = Pattern.compile("image: apache/kafka:(\\S+)@(sha256:[0-9a-f]{64})").matcher(read(OVERLAY + "kafka.yaml"));
        assertThat(kafka.find()).as("the kind overlay pins its broker").isTrue();
        String[][] pairs = {
            {"TRAEFIK", pin("TRAEFIK_REPOSITORY"), pin("TRAEFIK_VERSION"), pin("TRAEFIK_DIGEST")},
            {"POSTGRES", "library/postgres", postgres.group(1), postgres.group(2)},
            {"KAFKA", "apache/kafka", kafka.group(1), kafka.group(2)},
        };
        String check = read("infra/scripts/check-pinned-images.sh");
        for (String[] pair : pairs) {
            String target = pin(pair[0] + "_ROLL_VERSION");
            assertThat(pin(pair[0] + "_ROLL_REPOSITORY")).as("%s's target is its deployed pin's repository", pair[0]).isEqualTo(pair[1]);
            assertThat(pin(pair[0] + "_ROLL_DIGEST")).as("%s's target is pinned by a digest that is not the deployed one's", pair[0])
                    .matches("sha256:[0-9a-f]{64}").isNotEqualTo(pair[3]);
            assertThat(line(target)).as("%s's target %s is on the deployed %s's release line", pair[0], target, pair[2]).isEqualTo(line(pair[2]));
            assertThat(target).as("%s's target is another patch than the deployed one: a bump onto it needs a new target", pair[0]).isNotEqualTo(pair[2]);
            assertThat(check).as("the weekly pin job checks %s's target's tag has not moved", pair[0])
                    .containsPattern("(?m)^check \\S+-roll +\"\\$" + pair[0] + "_ROLL_REPOSITORY\" +\"\\$" + pair[0] + "_ROLL_VERSION\" +\"\\$" + pair[0]
                            + "_ROLL_DIGEST\" +roll-target$");
        }
    }

    private static String pin(String name) {
        Matcher value = Pattern.compile("(?m)^" + name + "=(\\S+)$").matcher(pins);
        assertThat(value.find()).as("%s is in %s", name, PINS).isTrue();
        return value.group(1);
    }

    /** A version's release line: everything before its last number, so v3.7.17 is on 3.7 and PostgreSQL's 18.6 on 18. */
    private static String line(String version) {
        String bare = version.startsWith("v") ? version.substring(1) : version;
        assertThat(bare).as("a version of numbers and dots").matches("[0-9]+(\\.[0-9]+)+");
        return bare.substring(0, bare.lastIndexOf('.'));
    }

    @Test
    @DisplayName("CI runs the rehearsal on master, nightly and when deploy/ changes, and keeps the artefacts")
    void ciRunsTheRehearsal() {
        String workflow = read(".github/workflows/ci.yml");
        assertThat(workflow).contains("  rehearsal:").contains("infra/scripts/kind-rehearsal.sh").contains("path: build/rehearsal/");
        assertThat(workflow).as("nightly").containsPattern("cron: '\\d+ \\d+ \\* \\* \\*'");
    }
}
