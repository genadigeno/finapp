package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The promotion's refusals (`P15-TSK-017`, ADR-0122 decision 4; {@code OPERATIONS_RUNBOOK.md} section 11.36), each beside the same
 * command accepting the good case, run against a git repository built for the test from the real overlays and release records:
 * {@code infra/scripts/Promotion.java} is the file {@code promote.sh} runs, compiled once here and run as a process, as the script
 * runs it. The signature and attestation refusals need a registry and cosign, and run in CI's admission job
 * ({@link #ciProvesTheSignatureRefusalsOnItsOwnRegistry} holds that they do).
 */
@Tag("architecture")
@DisplayName("the promotion refuses a tag, an unproven production pair and a rollback past a contract, and accepts the good case (P15-TSK-017)")
class PromotionTest {

    private static final String TOOL = "infra/scripts/Promotion.java";
    private static final String A1 = "sha256:" + "a1".repeat(32);
    private static final String M1 = "sha256:" + "b1".repeat(32);
    private static final String A2 = "sha256:" + "a2".repeat(32);
    private static final String M2 = "sha256:" + "b2".repeat(32);

    private static Path root;
    private static Path classes;

    @TempDir
    Path repository;

    @TempDir
    Path scratch;

    private record Run(int status, String out) {}

    @BeforeAll
    static void compileTheTool(@TempDir Path compiled) {
        root = EvidenceCitations.repositoryRoot();
        classes = compiled;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK runs the tests").isNotNull();
        assertThat(compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), root.resolve(TOOL).toString()))
                .as("Promotion.java compiles").isZero();
    }

    // ------------------------------------------------------------------------------------------- the refusals

    @Test
    @DisplayName("a tag, a name beside a digest, a short or upper-case digest and the placeholder are refused by name; a digest is accepted")
    void aTagIsRefused() throws Exception {
        String revision = start("1.2.0");
        for (String named : List.of("finapp-app:1.2.0", "latest", "finapp-app@" + A1, "sha256:" + "a1".repeat(31), A1.toUpperCase().replace("SHA256", "sha256"),
                "sha256:" + "0".repeat(64))) {
            Run run = tool("check", "staging", named, M1, revision);
            assertThat(run.status()).as("%s: %s", named, run.out()).isEqualTo(1);
            assertThat(run.out()).as(named).containsAnyOf("is not a digest", "is the placeholder digest");
            Run migrator = tool("check", "staging", A1, named, revision);
            assertThat(migrator.status()).as("the migrator named %s: %s", named, migrator.out()).isEqualTo(1);
        }
        Run accepted = tool("check", "staging", A1, M1, revision);
        assertThat(accepted.status()).as(accepted.out()).isZero();
        assertThat(accepted.out()).contains("ACCEPTED");
    }

    @Test
    @DisplayName("a revision that is not a full commit of this branch, and an environment that is not staging or production, are refused")
    void aRevisionOffTheBranchIsRefused() throws Exception {
        String revision = start("1.2.0");
        assertThat(tool("check", "staging", A1, M1, revision.substring(0, 12)).out()).contains("full forty-character commit");
        assertThat(tool("check", "staging", A1, M1, "f".repeat(40)).out()).contains("is not a commit of this repository");
        assertThat(tool("check", "kind", A1, M1, revision).out()).contains("is not an environment a promotion writes");
        git("checkout", "-q", "-b", "elsewhere");
        String elsewhere = commit("a commit on another branch", "notes.txt", "elsewhere\n");
        git("checkout", "-q", "master");
        Run off = tool("check", "staging", A1, M1, elsewhere);
        assertThat(off.status()).as(off.out()).isEqualTo(1);
        assertThat(off.out()).contains("is not on this branch's history");
    }

    @Test
    @DisplayName("production refuses a pair staging never ran, a pair it ran with no record, and a record that did not pass; the smoke's own record is accepted")
    void productionNeedsStagingEvidence() throws Exception {
        String revision = start("1.2.0");
        Run never = tool("check", "production", A1, M1, revision);
        assertThat(never.status()).as(never.out()).isEqualTo(1);
        assertThat(never.out()).contains("staging never ran this pair");

        assertThat(tool("write", "staging", A1, M1, revision).status()).isZero();
        commitAll("staging runs the pair");
        Run unrecorded = tool("check", "production", A1, M1, revision);
        assertThat(unrecorded.status()).as(unrecorded.out()).isEqualTo(1);
        assertThat(unrecorded.out()).contains("no staging evidence for this pair");

        // The smoke's record of a fleet on another pair is not this pair's evidence; a record that did not pass is none either.
        write("deploy/evidence/staging/20261112T100000Z-other.yaml", record("staging", A2, M2, revision, "PASS"));
        write("deploy/evidence/staging/20261112T100100Z-failed.yaml", record("staging", A1, M1, revision, "FAIL"));
        commitAll("two records that are not evidence");
        assertThat(tool("check", "production", A1, M1, revision).out()).contains("no staging evidence for this pair");

        // The positive control: the record the smoke itself writes, from a fleet that passed.
        Run smoke = smoke("staging", healthySnapshot(A1, M1, "1,2", "1"), zeroBalance(), "deploy/evidence/staging");
        assertThat(smoke.status()).as(smoke.out()).isZero();
        commitAll("staging's smoke record");
        Run accepted = tool("check", "production", A1, M1, revision);
        assertThat(accepted.status()).as(accepted.out()).isZero();
        assertThat(accepted.out()).contains("staging ran this pair and its smoke passed");
    }

    @Test
    @DisplayName("a rollback across a contract marker naming a newer release is refused; forward, and back to the release the marker names, are accepted")
    void aRollbackAcrossAContractMarkerIsRefused() throws Exception {
        String old = start("1.2.0");
        promoteAndCommit("staging", A1, M1, old);
        String contracted = release("1.3.0", "-- finapp-contract: first-conforming-release=1.3.0; waives=DROP; reason=the last reader went in 1.3.0");
        Run forward = tool("check", "staging", A2, M2, contracted);
        assertThat(forward.status()).as(forward.out()).isZero();
        assertThat(forward.out()).contains("forward:");
        promoteAndCommit("staging", A2, M2, contracted);

        Run back = tool("check", "staging", A1, M1, old);
        assertThat(back.status()).as(back.out()).isEqualTo(1);
        assertThat(back.out()).contains("crosses ledger/src/main/resources/db/migration/ledger/V002__contract.sql")
                .contains("names 1.3.0").contains("forward fix");

        // The release the marker names, built from an older commit, conforms: a rollback to it is accepted.
        Run again = tool("check", "staging", A2, M2, contracted);
        assertThat(again.status()).as(again.out()).isZero();
    }

    @Test
    @DisplayName("a marker of ANY never refuses a rollback, nor does one naming the release rolled back to; a migration with no marker refuses nothing")
    void aMarkerOfAnyNeverRefuses() throws Exception {
        for (String marker : new String[] {
                "-- finapp-contract: first-conforming-release=ANY; waives=CHECK; reason=a widened check refuses nothing",
                "-- finapp-contract: first-conforming-release=1.2.0; waives=DROP; reason=1.2.0 already stopped reading it",
                "-- an ordinary comment, and no marker"}) {
            Path fresh = Files.createTempDirectory(repository, "any");
            Path saved = repository;
            repository = fresh;
            try {
                String old = start("1.2.0");
                promoteAndCommit("staging", A1, M1, old);
                String newer = release("1.3.0", marker);
                promoteAndCommit("staging", A2, M2, newer);
                Run back = tool("check", "staging", A1, M1, old);
                assertThat(back.status()).as("%s: %s", marker, back.out()).isZero();
                assertThat(back.out()).contains("crosses no contract marker");
            } finally {
                repository = saved;
            }
        }
    }

    @Test
    @DisplayName("a marker the script cannot read, or two, refuses the rollback rather than passing it")
    void anUnreadableMarkerRefuses() throws Exception {
        for (String marker : new String[] {
                "-- finapp-contract: first-conforming-release=1.3; waives=DROP; reason=not a release name",
                "-- finapp-contract: first-conforming-release=ANY; waives=DROP; reason=one\n-- finapp-contract: first-conforming-release=2.0.0; waives=DROP; reason=two"}) {
            Path fresh = Files.createTempDirectory(repository, "unreadable");
            Path saved = repository;
            repository = fresh;
            try {
                String old = start("1.2.0");
                promoteAndCommit("staging", A1, M1, old);
                promoteAndCommit("staging", A2, M2, release("1.3.0", marker));
                Run back = tool("check", "staging", A1, M1, old);
                assertThat(back.status()).as("%s: %s", marker, back.out()).isEqualTo(1);
                assertThat(back.out()).containsAnyOf("cannot read", "which one binds cannot be told");
            } finally {
                repository = saved;
            }
        }
    }

    @Test
    @DisplayName("the write changes the overlay's two digests and its release record in one step, and nothing else of the overlay")
    void theWriteIsTheOverlaysTwoDigestsAndItsRecord() throws Exception {
        String revision = start("1.2.0");
        String before = read("deploy/overlays/staging/kustomization.yaml");
        Run written = tool("write", "staging", A1, M1, revision);
        assertThat(written.status()).as(written.out()).isZero();
        String after = read("deploy/overlays/staging/kustomization.yaml");
        assertThat(after).contains("  - name: finapp-app\n    digest: " + A1).contains("  - name: finapp-migrator\n    digest: " + M1);
        assertThat(after.replace(A1, "sha256:" + "0".repeat(64)).replace(M1, "sha256:" + "0".repeat(64))).isEqualTo(before);
        assertThat(read("deploy/overlays/staging/release.yaml")).contains("release: \"1.2.0\"").contains("revision: \"" + revision + "\"")
                .contains("app: \"" + A1 + "\"").contains("migrator: \"" + M1 + "\"").startsWith("# The release this environment runs");
    }

    // ------------------------------------------------------------------------------------------- the smoke

    @Test
    @DisplayName("the smoke refuses a replica on another digest, a migrator on another, and an image the kubelet named by its ID alone")
    void theSmokeRefusesAnotherBuild() throws Exception {
        promotedStaging();
        Run good = smoke("staging", healthySnapshot(A1, M1, "1", "1"), zeroBalance(), "-");
        assertThat(good.status()).as(good.out()).isZero();
        assertThat(good.out()).contains("PASS health").contains("PASS build-identity").contains("PASS trial-balance");

        Run other = smoke("staging", healthySnapshot(A1, M1, "1", "1").replaceFirst("finapp-app@" + A1 + "\\|", "finapp-app@" + A2 + "|"), zeroBalance(), "-");
        assertThat(other.status()).as(other.out()).isEqualTo(1);
        assertThat(other.out()).contains("FAIL build-identity").contains("asks for");

        Run resolvedElsewhere = smoke("staging", healthySnapshot(A1, M1, "1", "1").replaceFirst("\\|registry.invalid/finapp-staging-app@" + A1, "|registry.invalid/finapp-staging-app@" + A2), zeroBalance(), "-");
        assertThat(resolvedElsewhere.out()).contains("FAIL build-identity").contains(", not " + A1);

        Run byId = smoke("staging", healthySnapshot(A1, M1, "1", "1").replaceFirst("\\|registry.invalid/finapp-staging-app@" + A1, "|" + A1), zeroBalance(), "-");
        assertThat(byId.status()).as(byId.out()).isEqualTo(1);

        Run migrator = smoke("staging", healthySnapshot(A1, M1, "1", "1").replace("finapp-staging-migrator@" + M1, "finapp-staging-migrator@" + M2), zeroBalance(), "-");
        assertThat(migrator.status()).as(migrator.out()).isEqualTo(1);
        assertThat(migrator.out()).contains("FAIL build-identity");
    }

    @Test
    @DisplayName("the smoke refuses a rollout not finished: a terminating replica, two ReplicaSets, two configurations, a replica not ready, a missing Job")
    void theSmokeRefusesAnUnfinishedRollout() throws Exception {
        promotedStaging();
        String healthy = healthySnapshot(A1, M1, "1", "1");
        String terminating = healthy + "pod|finapp-app-old-zzzzz|terminating|finapp-app-old|True|finapp-app@" + A2 + "|registry.invalid/finapp-staging-app@" + A2 + "|finapp-app-config-old\n";
        assertThat(smoke("staging", terminating, zeroBalance(), "-").out()).contains("FAIL health").contains("is terminating");
        assertThat(smoke("staging", healthy.replaceFirst("\\|finapp-app-new\\|", "|finapp-app-old|"), zeroBalance(), "-").out())
                .contains("FAIL health").contains("ReplicaSets");
        assertThat(smoke("staging", healthy.replaceFirst("finapp-app-config-new\n", "finapp-app-config-old\n"), zeroBalance(), "-").out())
                .contains("FAIL health").contains("one configuration");
        assertThat(smoke("staging", healthy.replaceFirst("\\|True\\|", "|False|"), zeroBalance(), "-").out()).contains("FAIL health").contains("not ready");
        assertThat(smoke("staging", healthy.replaceFirst("deployment\\|4\\|4\\|3\\|3\\|3\\|3\\|3", "deployment|5|4|3|3|3|3|3"), zeroBalance(), "-").out())
                .contains("FAIL health").contains("has not observed generation 5");
        assertThat(smoke("staging", healthy.replaceAll("(?m)^job\\|.*\\n", ""), zeroBalance(), "-").out()).contains("FAIL health").contains("0 migrator Jobs");
    }

    @Test
    @DisplayName("the smoke refuses a trial balance that is not zero, one no currency answered, and an answer it cannot read")
    void theSmokeRefusesAnUnbalancedOrUnreadGauge() throws Exception {
        promotedStaging();
        String healthy = healthySnapshot(A1, M1, "1", "1");
        assertThat(smoke("staging", healthy, zeroBalance().replace("\"0\"]},{\"metric\":{\"currency\":\"USD\"}", "\"1\"]},{\"metric\":{\"currency\":\"USD\"}"), "-").out())
                .contains("FAIL trial-balance").contains("is not zero");
        assertThat(smoke("staging", healthy, "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}", "-").out())
                .contains("FAIL trial-balance").contains("no currency answered");
        assertThat(smoke("staging", healthy, "{\"status\":\"error\",\"errorType\":\"bad_data\"}", "-").out()).contains("FAIL trial-balance");
        assertThat(smoke("staging", healthy, zeroBalance().replace("\"0\"]}]", "\"NaN\"]}]"), "-").out()).contains("FAIL trial-balance");
    }

    // ------------------------------------------------------------------------------------------- item 32 (c)

    @Test
    @DisplayName("an activation waits for the environment's newest smoke record to show every replica holding the version")
    void anActivationWaitsForTheRolloutRecord() throws Exception {
        promotedStaging();
        Run none = tool("held", "staging", "FINAPP_MFA", "2");
        assertThat(none.status()).as(none.out()).isEqualTo(1);
        assertThat(none.out()).contains("no smoke record");

        assertThat(smoke("staging", healthySnapshot(A1, M1, "1", "1"), zeroBalance(), "deploy/evidence/staging").status()).isZero();
        assertThat(tool("held", "staging", "FINAPP_MFA", "2").out()).contains("REFUSED").contains("listing '1', not version 2");

        Thread.sleep(1100);
        assertThat(smoke("staging", healthySnapshot(A1, M1, "1,2", "1"), zeroBalance(), "deploy/evidence/staging").status()).isZero();
        Run held = tool("held", "staging", "FINAPP_MFA", "2");
        assertThat(held.status()).as(held.out()).isZero();
        assertThat(tool("held", "production", "FINAPP_MFA", "2").out()).as("another environment's record is not this one's").contains("no smoke record");
        assertThat(tool("held", "staging", "FINAPP_DOC", "2").status()).as("another ring").isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------- what the files hold

    @Test
    @DisplayName("the marker form the promotion reads is the lint's, character for character")
    void theMarkerFormIsTheLints() {
        assertThat(EvidenceCitations.read(root.resolve(TOOL))).contains("\"" + MigrationLint.MARKER_FORM.replace("\\", "\\\\") + "\";");
    }

    @Test
    @DisplayName("the smoke only reads: kubectl's get and one GET of Prometheus, the same query the judgement names")
    void theSmokeOnlyReads() {
        String smoke = EvidenceCitations.read(root.resolve("infra/scripts/post-deploy-smoke.sh"));
        List<String> verbs = new ArrayList<>();
        Matcher call = Pattern.compile("(?m)^[^#\\n]*\\$KUBECTL[^\\n]*?\\bget\\b").matcher(smoke);
        while (call.find()) {
            verbs.add(call.group());
        }
        assertThat(verbs).as("kubectl is called through get() alone").hasSize(1);
        assertThat(smoke).doesNotContainPattern("(?m)^[^#]*\\b(apply|create|delete|patch|edit|scale|exec|port-forward|proxy|rollout|annotate|label)\\b(?![-a-z])");
        List<String> curls = smoke.lines().filter(line -> line.contains("curl ") && !line.strip().startsWith("#")).toList();
        assertThat(curls).as("one call of Prometheus").hasSize(1);
        assertThat(curls.get(0)).contains("curl -fsS --max-time 20 -G ").doesNotContain(" -X").doesNotContain(" -d ").doesNotContain("--data ")
                .doesNotContain("--request").doesNotContain(" -T ").doesNotContain("--upload");
        assertThat(smoke).contains("query=" + "max by (currency) (finapp_ledger_trial_balance)");
        assertThat(EvidenceCitations.read(root.resolve(TOOL))).contains("\"max by (currency) (finapp_ledger_trial_balance)\"");
    }

    @Test
    @DisplayName("the scripts can run where they will run: committed executable")
    void theScriptsAreExecutable() throws Exception {
        for (String script : List.of("infra/scripts/promote.sh", "infra/scripts/post-deploy-smoke.sh")) {
            Process git = new ProcessBuilder("git", "ls-files", "-s", script).directory(root.toFile()).redirectErrorStream(true).start();
            String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(git.waitFor()).as("git answered: %s", index).isZero();
            assertThat(index).as("%s's mode in the index", script).startsWith("100755 ");
        }
    }

    @Test
    @DisplayName("CI proves the signature refusals on its own registry: promote.sh verify accepts the signed pair and refuses unsigned, other-key and unattested, by name")
    void ciProvesTheSignatureRefusalsOnItsOwnRegistry() {
        String check = EvidenceCitations.read(root.resolve("infra/scripts/admission-check.sh"));
        assertThat(check).contains("promoted \"$APP\" \"the application image\"").contains("promoted \"$MIGRATOR\" \"the migrator image\"")
                .contains("promotion_refused unsigned \"has no signature\" \"$APP_UNSIGNED\"")
                .contains("promotion_refused other-key \"has no signature\" \"$APP_OTHER\"")
                .contains("promotion_refused unattested \"has no slsaprovenance1 attestation\" \"$APP_UNATTESTED\"")
                .contains("promotion_refused tag \"a tag is never promoted\"")
                .contains("promotion_refused another-commit \"says it was built from\"");
        String promote = EvidenceCitations.read(root.resolve("infra/scripts/promote.sh"));
        assertThat(promote).contains("refused \"$reference has no signature the environment's authority accepts\"")
                .contains("refused \"$reference has no $type attestation the environment's authority accepts\"")
                .contains("for type in slsaprovenance1 cyclonedx; do")
                .contains("java --source 21 \"$(dpath \"$TOOL\")\" check \"$(dpath \"$ROOT\")\"");
        String workflow = EvidenceCitations.read(root.resolve(".github/workflows/ci.yml"));
        assertThat(workflow).contains("run: ./infra/scripts/admission-check.sh").contains("infra/scripts/(admission-check|promote|Promotion)");
    }

    // ------------------------------------------------------------------------------------------- the fixture

    /** A repository holding the real overlays and release records, a Gradle version and one ledger migration; its first commit. */
    private String start(String version) throws Exception {
        git("init", "-q", "-b", "master");
        for (String environment : List.of("staging", "production")) {
            for (String file : List.of("kustomization.yaml", "release.yaml")) {
                write("deploy/overlays/" + environment + "/" + file, EvidenceCitations.read(root.resolve("deploy/overlays/" + environment + "/" + file)));
            }
        }
        write("ledger/src/main/resources/db/migration/ledger/V001__initialise.sql", "CREATE TABLE ledger.entry (id bigint);\n");
        return commit("release " + version, "build-logic/src/main/kotlin/finapp.java-conventions.gradle.kts", "version = \"" + version + "-SNAPSHOT\"\n");
    }

    /** A later release: the Gradle version moved and one migration added carrying the header given. */
    private String release(String version, String header) throws Exception {
        write("ledger/src/main/resources/db/migration/ledger/V002__contract.sql", header + "\nALTER TABLE ledger.entry DROP COLUMN legacy;\n");
        return commit("release " + version, "build-logic/src/main/kotlin/finapp.java-conventions.gradle.kts", "version = \"" + version + "-SNAPSHOT\"\n");
    }

    private void promoteAndCommit(String environment, String app, String migrator, String revision) throws Exception {
        Run written = tool("write", environment, app, migrator, revision);
        assertThat(written.status()).as(written.out()).isZero();
        commitAll(environment + " runs " + revision);
    }

    private void promotedStaging() throws Exception {
        promoteAndCommit("staging", A1, M1, start("1.2.0"));
    }

    private static String record(String environment, String app, String migrator, String revision, String verdict) {
        return "environment: " + environment + "\nchecked: \"2026-11-12T10:00:00Z\"\nrelease: \"1.2.0\"\nrevision: \"" + revision + "\"\napp: \"" + app
                + "\"\nmigrator: \"" + migrator + "\"\nreplicas: 3\nconfiguration: \"c\"\nhealth: PASS\nbuild-identity: " + verdict
                + "\ntrial-balance: PASS\nheld:\n";
    }

    /** Three replicas of one ReplicaSet on one configuration, the Job succeeded, each on the promoted digests; the ring listing given. */
    private static String healthySnapshot(String app, String migrator, String mfaVersions, String mfaActive) {
        StringBuilder snapshot = new StringBuilder("deployment|4|4|3|3|3|3|3\n");
        for (String pod : List.of("aaaaa", "bbbbb", "ccccc")) {
            snapshot.append("pod|finapp-app-new-").append(pod).append("|running|finapp-app-new|True|finapp-app@").append(app)
                    .append("|registry.invalid/finapp-staging-app@").append(app).append("|finapp-app-config-new\n");
        }
        snapshot.append("job|finapp-migrator|1|finapp-migrator@").append(migrator).append('\n');
        snapshot.append("jobpod|finapp-migrator-xxxxx|registry.invalid/finapp-staging-migrator@").append(migrator).append('\n');
        snapshot.append("configmap|FINAPP_MFA_RING_VERSIONS|").append(mfaVersions).append('\n');
        snapshot.append("configmap|FINAPP_MFA_RING_ACTIVEVERSION|").append(mfaActive).append('\n');
        return snapshot.toString();
    }

    private static String zeroBalance() {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"currency\":\"EUR\"},\"value\":[1762941600.123,\"0\"]},"
                + "{\"metric\":{\"currency\":\"USD\"},\"value\":[1762941600.123,\"0\"]}]}}";
    }

    private Run smoke(String environment, String snapshot, String prometheus, String records) throws Exception {
        Path snapshotFile = Files.createTempFile(scratch, "snapshot", ".txt");
        Path answer = Files.createTempFile(scratch, "prometheus", ".json");
        Files.writeString(snapshotFile, snapshot, StandardCharsets.UTF_8);
        Files.writeString(answer, prometheus, StandardCharsets.UTF_8);
        return tool("smoke", environment, snapshotFile.toString(), answer.toString(), records.equals("-") ? "-" : repository.resolve(records).toString());
    }

    private Run tool(String command, String... args) throws Exception {
        List<String> line = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-cp", classes.toString(), "Promotion",
                command, repository.toString()));
        line.addAll(List.of(args));
        Process process = new ProcessBuilder(line).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("the tool finished").isTrue();
        return new Run(process.exitValue(), out);
    }

    private void write(String path, String text) throws IOException {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private String read(String path) throws IOException {
        return Files.readString(repository.resolve(path), StandardCharsets.UTF_8);
    }

    private String commit(String message, String path, String text) throws Exception {
        write(path, text);
        return commitAll(message);
    }

    private String commitAll(String message) throws Exception {
        git("add", "-A");
        git("-c", "user.name=promotion-test", "-c", "user.email=promotion-test@example.invalid", "-c", "commit.gpgsign=false", "commit", "-q",
                "--allow-empty", "-m", message);
        return git("rev-parse", "HEAD").strip();
    }

    private String git(String... args) throws Exception {
        List<String> line = new ArrayList<>(List.of("git", "-c", "core.autocrlf=false", "-C", repository.toString()));
        line.addAll(List.of(args));
        Process process = new ProcessBuilder(line).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("git %s: %s", String.join(" ", args), out).isZero();
        return out;
    }
}
