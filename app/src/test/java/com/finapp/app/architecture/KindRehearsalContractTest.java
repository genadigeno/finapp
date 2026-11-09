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
                .contains("balances explain the journal");
        assertThat(harness).as("GL = ledger is read on every census from the live judgement").contains("gl/balances");
    }

    @Test
    @DisplayName("every assertion the entry names is one the run cannot pass without")
    void theRunAssertsWhatTheEntryNames() {
        assertThat(harness).contains("rollout $label: zero failed requests")
                .contains("the peak of $peak connections exceeds instances x pool")
                .contains("an operator was admitted at the peak")
                .contains("handler window $label: every one of $total events applied exactly once");
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
    @DisplayName("CI runs the rehearsal on master, nightly and when deploy/ changes, and keeps the artefacts")
    void ciRunsTheRehearsal() {
        String workflow = read(".github/workflows/ci.yml");
        assertThat(workflow).contains("  rehearsal:").contains("infra/scripts/kind-rehearsal.sh").contains("path: build/rehearsal/");
        assertThat(workflow).as("nightly").containsPattern("cron: '\\d+ \\d+ \\* \\* \\*'");
    }
}
