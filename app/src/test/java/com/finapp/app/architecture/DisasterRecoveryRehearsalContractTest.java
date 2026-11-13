package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the disaster-recovery exercise holds about itself ({@code P16-TSK-025}; ADR-0149; {@code OPERATIONS_RUNBOOK.md} section 11.64): the
 * {@code dr} scope of the kind rehearsal, which loses the environment whole and rebuilds the platform from the repository and a vault -
 * the backup, the key-ring escrow, the release record, the image set, the configuration fingerprint - with every credential generated
 * again, the recovered database held until the platform's own verification passes and a person releases it, the nodes' registry cut
 * throughout the rebuild (E87, E88, E89; {@code THREAT_MODEL.md} T32 and T42).
 *
 * <p>It has not run: the owner postponed Phase 16's {@code kind} runs ({@code POSTPONED_ISSUES.md} PI-025), and a run's numbers go in the
 * runbook's record when one is made. What a hermetic test holds is that a later edit does not quietly release before the verification,
 * restore a credential, generate a ring's version in a recovery, let an image come from a registry, write a backup in plaintext, or judge
 * what it never read.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the disaster-recovery exercise holds what it says it holds (P16-TSK-025)")
class DisasterRecoveryRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String DR = "infra/scripts/kind-rehearsal/dr.sh";
    private static final String SECRETS = "infra/scripts/kind-secrets.sh";
    private static final String TELEMETRY = "infra/scripts/kind-rehearsal/telemetry.sh";
    private static final String RESTORE = "infra/scripts/restore-rehearsal.sh";

    private static Path root;
    private static String harness;
    private static String dr;
    private static String secrets;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = read(HARNESS);
        dr = read(DR);
        secrets = read(SECRETS);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    /** The body of one shell function, so an assertion cannot be satisfied by another function's text. */
    private static String function(String text, String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(text);
        assertThat(start.find()).as("the script defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(text);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return text.substring(start.end(), end.start());
    }

    /** The harness's main sequence from one step to the next step after it. */
    private static String step(String name) {
        int start = harness.indexOf("\nstep " + name + "\n");
        assertThat(start).as("the harness has a step %s", name).isPositive();
        int end = harness.indexOf("\nstep ", start + 1);
        return harness.substring(start, end < 0 ? harness.length() : end);
    }

    private static void inOrder(String text, String... marks) {
        int at = -1;
        for (String mark : marks) {
            int next = text.indexOf(mark, at + 1);
            assertThat(next).as("'%s' after the marks before it", mark).isGreaterThan(at);
            at = next;
        }
    }

    @Test
    @DisplayName("the scope is release N alone at a scale-out's count, its phase, vault and person resolved before anything is built, and no"
            + " other scope may name them")
    void theScopeIsResolvedBeforeAnythingIsBuilt() {
        assertThat(harness).contains("rolls) ;; load) ;; experiments) ;; dr) ;; *)").contains("[ \"$SCOPE\" != dr ] || N_ONLY=1")
                .contains("[ \"$SCOPE\" != dr ] || { FLEET_REPLICAS=${REHEARSAL_DR_SCALE:-7}; FLEET_AUTOSCALE=1; }")
                .contains(". \"$DIR/dr.sh\"").contains("[ \"$SCOPE\" != dr ] || : > \"$ART/dr-marks.tsv\"");
        assertThat(function(harness, "fleet_resolve")).contains("        dr) ;;\n");
        inOrder(harness, "dpath() {", "\ndr_resolve\n", "mkdir -p \"$ART\"", "step build-images");
        String resolve = function(dr, "dr_resolve");
        assertThat(resolve).as("no other scope names a phase or a vault")
                .contains("[ -z \"$DR_PHASE\" ] && [ -z \"$DR_VAULT\" ] || { echo \"?? REHEARSAL_DR_PHASE and REHEARSAL_DR_VAULT belong to REHEARSAL_SCOPE=dr\"");
        assertThat(resolve).as("a person named, a whole run, a cluster of its own, a scale-out that scales")
                .contains("'' | *'\t'* | *'|'*) echo \"?? REHEARSAL_DR_RELEASED_BY names the person")
                .contains("[ -z \"${REHEARSAL_STEPS:-}\" ] ||").contains("[ -z \"${REHEARSAL_REUSE_CLUSTER:-}\" ] ||")
                .contains("[ \"$FLEET_REPLICAS\" != 3 ] ||").contains("case $PROBE in '' | skip-verification | config-drift) ;;");
        assertThat(resolve).as("the rebuild: every part of the vault, outside the repository; never a build; the repository at the lost environment's commit")
                .contains("backup.enc backup.sha256 manifest.enc manifest.sha256 client.enc client.sha256 rings.enc rings.sha256 rings.checkvalues")
                .contains("backup.key escrow.key images.tar images.sha256 images.tsv release.txt fingerprint.txt expected.env instants.tsv repository.ref")
                .contains("\"$ROOT\"/*) echo \"?? the vault is inside the repository\"")
                .contains("[ -n \"${REHEARSAL_SKIP_BUILD:-}\" ] ||")
                .contains("[ \"$(git -C \"$(dpath \"$ROOT\")\" rev-parse HEAD)\" = \"$(cat \"$DR_VAULT/repository.ref\")\" ]");
    }

    @Test
    @DisplayName("the backup is one snapshot read by the restore rehearsal's own statements, sealed as it is written, refused by its digest"
            + " before it is opened, and nothing in the vault is a plaintext dump")
    void theBackupIsOneSnapshotAndNeverPlainAtRest() {
        String inner = dr.substring(dr.indexOf("DR_INNER='"), dr.indexOf("echo done > /tmp/dr-backup-status'"));
        inOrder(inner, "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;", "pg_export_snapshot()", "$COUNTS_SQL;", "$SEQUENCES_SQL;",
                "$HISTORY_SQL;", "extract(epoch FROM now())::bigint", "pg_dump --snapshot=\"$snapshot\" --format=custom", "\"COMMIT;\"");
        assertThat(function(dr, "dr_backup")).contains("sh -c \"$DR_INNER\" < /dev/null 2> \"$ART/dr-backup.err\" | dr_seal backup backup")
                .contains("tar -C \"$WORK\" -cf - dr-manifest | dr_seal manifest backup").contains("rm -rf \"$WORK/dr-manifest\"")
                .contains("dr_no_plaintext");
        String statements = function(dr, "dr_statements");
        assertThat(statements).as("read from the restore rehearsal, a plain assignment each, never copied")
                .contains("for dr_statement in COUNTS_SQL SEQUENCES_SQL HISTORY_SQL XID8_SQL; do")
                .contains("\"$ROOT/infra/scripts/restore-rehearsal.sh\"").contains("grep -Eqx \"$dr_statement=\\\"[^\\\"\\$\\`]+\\\"\"");
        assertThat(dr).doesNotContain("COUNTS_SQL=\"SELECT").doesNotContain("XID8_SQL=\"SELECT");
        String restore = read(RESTORE);
        for (String name : List.of("COUNTS_SQL", "SEQUENCES_SQL", "HISTORY_SQL", "XID8_SQL")) {
            assertThat(restore).as("the restore rehearsal holds %s as one plain assignment the exercise can read", name)
                    .containsPattern("(?m)^" + name + "=\"[^\"$`]+\"$");
        }
        assertThat(function(dr, "dr_seal")).contains("openssl enc -e -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass \"file:$(dpath \"$DR_VAULT/$2.key\")\"")
                .contains("openssl dgst -sha256 -r");
        assertThat(function(dr, "dr_holds")).contains("|| fail \"the vault's $dr_part does not match its digest: refused before it is read\"");
        assertThat(function(dr, "dr_no_plaintext")).contains("PGDMP").contains("ustar");
        assertThat(function(dr, "dr_rings_ready")).as("the vault in the run's own work directory, which the run removes at its end")
                .contains("DR_VAULT=\"$WORK/vault\"").contains("chmod 700 \"$DR_VAULT\"");
        assertThat(function(harness, "finish")).contains("rm -rf \"$WORK\"");
    }

    @Test
    @DisplayName("a recovery carries the key rings and nothing else: kind-secrets.sh reads every ring version from the escrow, refuses any"
            + " other name, refuses before writing an object when a version is missing, and generates every credential again")
    void theRingsComeFromTheEscrowAndNothingElseDoes() {
        assertThat(secrets).contains("RINGS=${KIND_SECRETS_RINGS:-}").contains("'' | stdin) ;;");
        assertThat(function(secrets, "is_ring")).as("the inventory's rule, never a list").contains("*_V[0-9]_KEY) return 0 ;;")
                .contains("printf '%s\\n' \"$app_keys\" | grep -qx \"${1%_KEY}_V2_KEY\"");
        int read = secrets.indexOf("if [ \"$RINGS\" = stdin ]; then\n    while IFS= read -r line");
        assertThat(read).as("the escrow read whole").isPositive();
        String block = secrets.substring(read, secrets.indexOf("\nfi\n", read));
        assertThat(block).contains("'' | *[!A-Z0-9_]*) echo \"a line on stdin names no key")
                .contains("printf '%s\\n' \"$app_keys\" | grep -qx \"$name\" || {")
                .contains("is_ring \"$name\" || { echo \"$name, on stdin, is not a key ring's version: only a ring is recovered, every other secret is generated\"")
                .contains("echo \"no value for $name on stdin: a key ring's version is never generated in its place\"");
        assertThat(block.indexOf("grep -qx \"$name\"")).as("a name checked against the inventory before it is used").isLessThan(block.indexOf("eval "));
        assertThat(read).as("before any object is written").isLessThan(secrets.indexOf("named finapp-app-secrets $app_keys"));
        assertThat(function(secrets, "remember")).contains("if [ \"$RINGS\" = stdin ] && is_ring \"$1\"; then\n                eval \"fresh=\\${ring_$1}\"")
                .contains("fresh=$(head -c 32 /dev/urandom | base64 | tr -d '\\n')");
        assertThat(secrets).as("the generator still names no inventoried key").doesNotContain("FINAPP_MFA_KEY");
        assertThat(harness).as("the harness gives it the escrow in the dr scope alone")
                .contains("{ [ -z \"$DR_RINGS\" ] || dr_open rings; } | PATH=\"$WORK/bin:$PATH\" KIND_SECRETS_LEDGER=\"$WORK/ledger\" KIND_SECRETS_RINGS=\"$DR_RINGS\"")
                .contains("if [ \"$SCOPE\" = dr ]; then\n    dr_rings_ready\n    DR_RINGS=stdin\nfi");
        String ready = function(dr, "dr_rings_ready");
        assertThat(ready).as("the rings made once, sealed under a key apart from the backup's, each ring's version 1 check value beside it")
                .contains("openssl rand -hex 32 > \"$DR_VAULT/backup.key\"").contains("openssl rand -hex 32 > \"$DR_VAULT/escrow.key\"")
                .contains("done < \"$WORK/dr-ring-names\" | dr_seal rings escrow").contains("_RING_CHECKVALUES=1:%s\\n")
                .contains("grep -c '^FINAPP_[A-Z0-9_]*_RING_CHECKVALUES=1:[0-9a-f]\\{16\\}$'");
        assertThat(function(dr, "dr_open")).contains("[ \"$1\" != rings ] || dr_key=escrow");
        assertThat(function(dr, "dr_ring_names")).as("the same rule as kind-secrets.sh, from the ExternalSecret")
                .contains("deploy/components/secret-delivery/externalsecret-app.yaml").contains("*_V[0-9]_KEY)")
                .contains("grep -qx \"${dr_name%_KEY}_V2_KEY\"");
        assertThat(function(dr, "dr_check_value")).contains("/src/KeyCheckValue.java");
    }

    @Test
    @DisplayName("the recovered database is held from before the restore until a person's recorded release, which follows a comparison and"
            + " a verification with no FAIL, all before the migrator, the fleet or the edge")
    void theRestoreIsHeldUntilThePersonsRecordedRelease() {
        String restore = function(dr, "dr_restore");
        inOrder(restore, "ALTER ROLE finapp_app NOLOGIN; ALTER ROLE finapp_migrator NOLOGIN; ALTER ROLE finapp_reporting NOLOGIN",
                "dr_role_refused \"$dr_role\" || fail", "pg_restore -U finapp -d finapp --single-transaction --exit-on-error", "dr_epoch ",
                "dr_role_refused \"$dr_role\" || fail \"$dr_role can log in after the server's restart", "dr_compare", "dr_verify planted",
                "dr_verify recovered", "step dr-window", "if ! dr_releasable; then", "\n    dr_release\n");
        assertThat(function(dr, "dr_role_refused")).contains("grep -q 'is not permitted to log in' \"$WORK/dr-login.out\"");
        inOrder(harness, "wait_job finapp finapp-role-bootstrap 300 role-bootstrap", "[ \"$DR_PHASE\" != rebuild ] || dr_restore",
                "\nstep migrator-nminus1\n", "\nstep app-nminus1\n", "\nstep edge\n");
        String releasable = function(dr, "dr_releasable");
        assertThat(releasable).as("reports this run wrote, every verdict a pass, as many as the catalogue states")
                .contains("[ -s \"$ART/dr-verdicts.txt\" ] && [ -s \"$ART/dr-verdicts-compare.txt\" ] || return 1")
                .contains("! grep -q '^FAIL|' \"$ART/dr-verdicts.txt\" \"$ART/dr-verdicts-compare.txt\" || return 1")
                .contains("s/^CHECKS|\\([1-9][0-9]*\\)$/\\1/p");
        String release = function(dr, "dr_release");
        assertThat(release).as("one transaction with the instant read; the person, the reason, the instant, the backup and the reports it rests on")
                .contains("BEGIN; ALTER ROLE finapp_app LOGIN; ALTER ROLE finapp_migrator LOGIN; ALTER ROLE finapp_reporting LOGIN;\n        SELECT now(); COMMIT;")
                .contains("released_by\\t%s\\nreason\\t%s\\nreleased_at\\t%s\\nbackup\\t%s\\nrests_on\\t%s").contains("\"$DR_RELEASED_BY\"")
                .contains("for dr_report in dr-verdicts-compare.txt dr-verdicts.txt dr-window.txt; do").contains("> \"$ART/release-dr.tsv\"")
                .contains("dr_mark released");
        assertThat(release.indexOf("dr_mark released")).as("marked once every role logs in").isGreaterThan(release.indexOf("after the release, $dr_role still cannot log in"));
        String verify = function(dr, "dr_verify");
        assertThat(verify).as("a FAIL keeps it held; a short report or none is not a pass")
                .contains("|| fail \"the recovered database is not financially whole, so it stays held").contains("|| cannot \"the verification printed a short report\"")
                .contains("grep -q \"^EXIT|$dr_code\\$\"");
        String compare = function(dr, "dr_compare");
        assertThat(compare).contains("cmp -s \"$WORK/dr-manifest/counts\" \"$WORK/dr-counts\"").contains("function at_or_past(r, s)")
                .contains("cmp -s \"$WORK/dr-manifest/history\" \"$WORK/dr-history\"")
                .contains("[ \"$(grep -c '^PASS|' \"$ART/dr-verdicts-compare.txt\")\" = 3 ]").contains("rm -rf \"$WORK/dr-manifest\"");
    }

    @Test
    @DisplayName("the counter moves past every restored xid8 before any check, the server stopped cleanly and its epoch set on its own volume")
    void theCounterMovesBeforeTheChecks() {
        String epoch = function(dr, "dr_epoch");
        inOrder(epoch, "scale statefulset finapp-postgres --replicas=0", "wait --for=delete \"pod/$PG_POD\"", "pg_resetwal -e",
                "claimName: data-$PG_POD", "[ \"$dr_phase\" = Succeeded ] || cannot", "scale statefulset finapp-postgres --replicas=1");
        assertThat(epoch).as("the restricted profile, the server's own image").contains("runAsNonRoot: true").contains("readOnlyRootFilesystem: true")
                .contains("capabilities: {drop: [\"ALL\"]}").contains("seccompProfile:\n      type: RuntimeDefault").contains("image: $dr_image");
        String restore = function(dr, "dr_restore");
        assertThat(restore).contains("dr_highest=$(psql_query -c \"$XID8_SQL\"").contains("floor($dr_highest::numeric / 4294967296)::bigint + 1")
                .contains("pg_snapshot_xmin(pg_current_snapshot())::text::numeric > $dr_highest::numeric");
    }

    @Test
    @DisplayName("the verification reaches the database as an administrator does, through the API server's port-forward on this machine's"
            + " loopback, read-only, over the run's CA, with the escrow's rings and their published check values; no policy is opened")
    void theVerificationReachesTheDatabaseAsAnAdministrator() {
        String tunnel = function(dr, "dr_tunnel");
        assertThat(tunnel).contains("--network kind --publish 127.0.0.1::15432").contains("port-forward --address 0.0.0.0 \"pod/$PG_POD\" 15432:5432")
                .contains("--entrypoint kubectl \"$KIND_NODE_IMAGE\"").contains("CONTAINERS=\"$CONTAINERS $dr_tunnel_name\"");
        assertThat(dr).as("no policy is opened for the exercise").doesNotContain("kind: NetworkPolicy").doesNotContain("networkpolicy");
        String verify = function(dr, "dr_verify");
        assertThat(verify).contains("sslmode=verify-ca&sslrootcert=$dr_ca&options=-c%20default_transaction_read_only%3Don")
                .contains("dr_open rings | (").contains("done < \"$DR_VAULT/rings.checkvalues\"").contains("< /dev/null > \"$ART/dr-verification-$1.log\"")
                .contains("com.finapp.app.restore.RestoreVerification").doesNotContain("--drill");
    }

    @Test
    @DisplayName("E89: an escrow short of a ring's version is refused before any object is written, and a value that is not the escrow's is"
            + " refused at the start by the check value both environments publish - the words the platform itself says")
    void aKeyVersionLostFromTheEscrowIsRefused() {
        assertThat(dr).contains("DR_PLANTED_RING=FINAPP_MFA_KEY");
        String shortEscrow = function(dr, "dr_escrow_short");
        assertThat(shortEscrow).contains("dr_open rings | grep -v \"^$DR_PLANTED_RING=\" | KIND_SECRETS_RINGS=stdin")
                .contains("sh \"$ROOT/infra/scripts/kind-secrets.sh\" render \"$WORK/dr-short\"").contains("[ \"$dr_code\" = 1 ] || fail")
                .contains("grep -q \"no value for $DR_PLANTED_RING on stdin\"").contains("[ -z \"$(ls -A \"$WORK/dr-short\")\" ] || fail");
        assertThat(secrets).as("the refusal the plant greps is the one kind-secrets.sh makes").contains("echo \"no value for $name on stdin:");
        assertThat(function(dr, "dr_rings_ready")).contains("dr_holds rings\n        dr_escrow_short");
        String verify = function(dr, "dr_verify");
        assertThat(verify).contains("eval \"export $DR_PLANTED_RING=\\$(head -c 32 /dev/urandom | base64 | tr -d '\\\\n')\"")
                .contains("[ \"$dr_code\" = 2 ] && grep -q 'does not give its published check value' \"$ART/dr-verification-$1.log\"");
        assertThat(read("platform/src/main/java/com/finapp/platform/security/KeyRing.java")).as("the words the plant greps are the ring's own")
                .contains("\" does not give its published check\"\n                                + \" value: the value under that version was replaced");
        String render = function(dr, "dr_render");
        assertThat(render).as("every ring's check value in the run's own ConfigMap and its all.yaml, read back")
                .contains("s/^\\(FINAPP_[A-Z0-9_]*_RING_CHECKVALUES\\)=\\(1:[0-9a-f]*\\)$/  \\1: \"\\2\"/")
                .contains("sed -i \"/^  FINAPP_DB_INSTANCES: /r $WORK/dr-config-lines\" \"$dr_config\" \"$1/all.yaml\"")
                .contains("grep -qxF -e \"$dr_line\" \"$dr_config\" && grep -qxF -e \"$dr_line\" \"$1/all.yaml\" || cannot");
        inOrder(harness, "brokers_render \"$A\"\n", "[ \"$SCOPE\" != dr ] || dr_render \"$A\"");
    }

    @Test
    @DisplayName("E88: every node of the rebuild refuses a registry from the moment it exists, the cut seen at the start and the end, every"
            + " image imported from the vault under its digest, the loaders skipping what it brought, and no pull in the whole journal")
    void theRegistryIsCutAndNothingIsPulled() {
        String made = function(dr, "dr_cluster_made");
        assertThat(made).as("the cluster's own networks read, not copied, and everything else refused")
                .contains("--cluster-cidr=").contains("--service-cluster-ip-range=").contains("dock network inspect kind")
                .contains("-d 127.0.0.0/8 -j RETURN").contains("iptables -A FINAPP-DR-NO-REGISTRY -p tcp -j REJECT --reject-with tcp-reset")
                .contains("iptables -I OUTPUT 1 -j FINAPP-DR-NO-REGISTRY").contains("dr_registry_refused start");
        inOrder(harness, "CLUSTER_UP=1\n", "[ \"$SCOPE\" != dr ] || dr_cluster_made", "\nstep images-loaded\n");
        String refused = function(dr, "dr_registry_refused");
        assertThat(refused).as("a pull of an image no vault holds refused on every node, after the store is read")
                .contains("dr_control=\"$(normalized \"$TOXIPROXY_REPOSITORY\")@$TOXIPROXY_DIGEST\"")
                .contains("grep -q '^registry.k8s.io/' \"$WORK/dr-store\" || cannot").contains("fail \"E88 ($1): the node $node pulled $dr_control");
        assertThat(step("images-loaded")).contains("elif [ \"$DR_PHASE\" = rebuild ]; then").contains("    dr_images_loaded\n");
        String loaded = function(dr, "dr_images_loaded");
        assertThat(loaded).contains("dr_holds images").contains("ctr -n k8s.io images import --platform linux/amd64 - < \"$DR_VAULT/images.tar\"")
                .contains("= \"$dr_digest\" ]").contains("finapp-app@sha256:*)").contains("finapp-migrator@sha256:*)")
                .contains("dr_held \"$IMAGE_APP_N\" && dr_held \"$IMAGE_MIGRATOR_N\" || fail").doesNotContain("load_tagged").doesNotContain("load_built");
        assertThat(function(harness, "load_tagged")).contains("if [ -n \"${3:-}\" ] && dr_held \"$1@$3\"; then\n        return 0");
        assertThat(function(read(TELEMETRY), "telemetry_image")).contains("! dr_held \"$1@$3\" || return 0");
        assertThat(harness).contains("! dr_held \"quay.io/jetstack/$component:$CERT_MANAGER_VERSION\" \"$node\" || continue");
        assertThat(function(dr, "dr_held")).as("only in the rebuild, once the set is imported").contains("[ -n \"$DR_IMPORTED\" ] || return 1");
        String set = function(dr, "dr_image_set");
        assertThat(set).as("everything the lost node held but its own node image's, by name and digest")
                .contains("$1 !~ /^registry\\.k8s\\.io\\//").contains("$1 !~ /^docker\\.io\\/kindest\\//").contains("images export --platform linux/amd64");
        String after = function(dr, "dr_after_rebuild");
        inOrder(after, "minReplicas\\\":$FLEET_REPLICAS", "dr_mark scaled", "journalctl -u containerd --no-pager",
                "grep -q 'RunPodSandbox'", "grep -c 'PullImage'", "grep -c '^Pulling|'", "dr_registry_refused end");
    }

    @Test
    @DisplayName("the disaster destroys the cluster and its data, and the rebuild is a run of its own on another cluster, from the vault's"
            + " images and never a build")
    void theDisasterDestroysTheClusterAndTheRebuildStartsFromTheVault() {
        String before = function(dr, "dr_before");
        inOrder(before, "step dr-launcher", "step dr-workload", "census dr-before", "step dr-backup", "dr_backup", "dr_image_set",
                "step dr-lost-window", "step dr-disaster", "\"$KIND_BIN\" delete cluster --name \"$CLUSTER\"", "CLUSTER_UP=\"\"",
                "label=io.x-k8s.kind.cluster=$CLUSTER", "step dr-rebuild", "step dr-record");
        assertThat(before).contains("REHEARSAL_SCOPE=dr REHEARSAL_DR_PHASE=rebuild REHEARSAL_DR_VAULT=\"$DR_VAULT\" REHEARSAL_CLUSTER=\"$CLUSTER-recovered\"")
                .contains("REHEARSAL_ARTEFACTS=\"$ART/rebuild\" REHEARSAL_SKIP_BUILD=1 sh \"$ROOT/infra/scripts/kind-rehearsal.sh\" || dr_code=$?")
                .contains("rm -rf \"$ART/rebuild\"").contains("1) fail \"the rebuild went red").contains("*) cannot \"the rebuild could not run");
        assertThat(before.indexOf("printf 'disaster-host-epoch")).as("the recovery's clock starts as the cluster goes")
                .isLessThan(before.indexOf("\"$KIND_BIN\" delete cluster"));
        assertThat(before).as("the client's memory, never its account of what it moved, and never the lost edge's CA")
                .contains("--exclude='workload-*' --exclude='stop-*' --exclude='edge-ca.crt' . | dr_seal client backup");
    }

    @Test
    @DisplayName("the rebuilt fleet runs the configuration the lost fleet ran, read from every replica, after the recovery and at the"
            + " scale-out; the config-drift probe changes one FINANCIAL value in the rebuild alone")
    void theConfigurationIsTheLostFleets() {
        String judged = function(dr, "dr_fingerprint_judged");
        assertThat(judged).contains("[ \"$(grep -c . \"$ART/dr-fingerprints-$1.tsv\")\" -ge \"$2\" ] || fail").contains("'\tunread$'")
                .contains("awk -F'\\t' -v want=\"$dr_recorded\" '$2 != want'");
        assertThat(function(dr, "dr_fingerprints")).contains("finapp_config_fingerprint{.*fingerprint=").contains("np-scraper");
        assertThat(function(dr, "dr_before")).as("one fingerprint, read from every replica, before the disaster")
                .contains("[ \"$(cut -f2 \"$ART/dr-fingerprints-before.tsv\" | sort -u | grep -c .)\" = 1 ]");
        String after = function(dr, "dr_after_rebuild");
        assertThat(after).contains("dr_fingerprint_judged recovered \"$FLEET_START\"").contains("dr_fingerprint_judged scaled \"$FLEET_REPLICAS\"");
        assertThat(dr).contains("DR_DRIFT_LINE='  FINAPP_LENDING_CAPITAL_HEADROOM_FLOOR_EUR: \"1\"'");
        assertThat(function(dr, "dr_render")).contains("if [ \"$DR_PHASE\" = rebuild ] && [ \"$PROBE\" = config-drift ]; then");
        assertThat(read("docs/architecture/CONFIGURATION_REGISTER.md")).as("the value the probe changes is FINANCIAL, so the fingerprint carries it")
                .contains("| `FINAPP_LENDING_CAPITAL_HEADROOM_FLOOR_EUR` | FINANCIAL |");
    }

    @Test
    @DisplayName("what the backup lost is absent and everything before it is counted: the window's own transfers held before the disaster and"
            + " none after it; the census's expectations at T carried, the rebuilt simulator counted for what it saw")
    void whatTheBackupLostIsAbsentAndEverythingBeforeItIsCounted() {
        String before = function(dr, "dr_before");
        assertThat(before).contains("start_workload dr-lost").contains("reference LIKE 'wl-dr-lost-%'")
                .contains("[ \"${dr_lost:-0}\" -gt 0 ] && [ \"$dr_lost\" = \"$dr_acknowledged\" ]")
                .contains("cp \"$ART/census-dr-before.expected\" \"$DR_VAULT/expected.env\"");
        assertThat(before.indexOf("census dr-before")).as("the expectations are the census's before the backup").isLessThan(before.indexOf("dr_backup"));
        assertThat(function(dr, "dr_after_rebuild")).contains("reference LIKE 'wl-dr-lost-%'").contains("[ \"$dr_lost\" = 0 ] || fail");
        String census = function(harness, "census");
        assertThat(census).contains("expected_transfers=$((expected_transfers + DR_TRANSFERS))").contains("expected_wallets=$((expected_wallets + DR_WALLET_MINOR))")
                .contains("provider_captures=$((expected_topups + expected_checkouts - DR_TOPUPS - DR_CHECKOUTS))")
                .contains("provider_payouts=$((expected_payouts - DR_PAYOUTS))").contains("> \"$ART/census-$label.expected\"")
                .contains("if [ \"${captures:-0}\" = \"$provider_captures\" ]").contains("if [ \"${payouts:-0}\" = \"$provider_payouts\" ]");
        assertThat(dr).as("zero in every other scope").contains("DR_TRANSFERS=0\nDR_TOPUPS=0\nDR_CHECKOUTS=0\nDR_PAYOUTS=0\nDR_WALLET_MINOR=0\n");
        assertThat(function(dr, "dr_resolve")).contains("DR_TRANSFERS=$(dr_expected transfers)");
    }

    @Test
    @DisplayName("the skip-verification probe: the release refuses, the roles stay NOLOGIN, the database refuses the fleet deployed anyway"
            + " and the journal does not grow, and the run is red")
    void theSkipVerificationProbeLeavesTheMoneyHeld() {
        String restore = function(dr, "dr_restore");
        assertThat(restore).contains("if [ \"$PROBE\" = skip-verification ]; then\n        log \"probe skip-verification: the verification is not run\"");
        inOrder(restore, "if ! dr_releasable; then", "dr_held_fleet", "fail \"probe skip-verification: the release refused",
                "[ \"$PROBE\" != skip-verification ] || fail \"probe skip-verification: the release went ahead", "\n    dr_release\n");
        String held = function(dr, "dr_held_fleet");
        assertThat(held).contains("[ \"${dr_ready:-0}\" = 0 ] || cannot").contains("grep -q 'is not permitted to log in'")
                .contains("|| cannot \"probe skip-verification: the journal grew on a held database\"")
                .contains("count(*) FILTER (WHERE rolcanlogin)");
    }

    @Test
    @DisplayName("after the release the window before the restore point is replayed from the restored outbox - 24 hours when the lag at T"
            + " cannot be read, never before the first publication - before money moves")
    void theWindowBeforeTheRestorePointIsReplayed() {
        String replay = function(dr, "dr_replay_window");
        assertThat(replay).contains("dr_from=$((dr_to - DR_REPLAY_HOURS * 3600))").contains("[ \"$dr_from\" -ge \"$dr_first\" ] || dr_from=$dr_first")
                .contains("client replay \"$dr_type\"").contains("dr_end=$((dr_t + 300))");
        assertThat(dr).contains("DR_REPLAY_HOURS=24");
        String smoke = step("smoke-nminus1");
        inOrder(smoke, "if [ \"$DR_PHASE\" != rebuild ]; then\nclient prepare", "client map-gl", "fi\n", "[ \"$DR_PHASE\" != rebuild ] || dr_replay_window",
                "smoke nminus1", "census nminus1", "[ \"$DR_PHASE\" != rebuild ] || dr_mark money-moved");
    }

    @Test
    @DisplayName("every step of both runs is timed, and the record holds the window lost and four recovery times read from this run's marks")
    void everyStepIsTimedAndTheRecordReadsThisRunsMarks() {
        String record = function(dr, "dr_record");
        assertThat(record).contains("for dr_mark_name in released fleet-ready money-moved scaled; do").contains("|| cannot \"the rebuild's record has no")
                .contains("\"$ART/timings.tsv\"").contains("\"$ART/rebuild/timings.tsv\"").contains("\"$ART/rebuild/dr-rates.tsv\"")
                .contains("disaster-db-epoch").contains("backup-epoch");
        assertThat(harness).contains("[ \"$DR_PHASE\" != rebuild ] || dr_mark fleet-ready");
        assertThat(function(dr, "dr_restore")).contains("printf 'logical\\t%s\\t%s\\t%s\\n' \"$CLUSTER\" \"$dr_bytes\" \"$dr_took\" > \"$ART/dr-rates.tsv\"");
        assertThat(function(harness, "step")).contains("printf '%s\\t%s\\n' \"$STEP_NAME\" \"$((now - STEP_START))\" >> \"$ART/timings.tsv\"");
        inOrder(harness, "if [ \"$SCOPE\" = experiments ]; then\n    experiments_scope", "if [ \"$SCOPE\" = dr ]; then\n    dr_scope\n    step end\n    finish\nfi");
    }

    @Test
    @DisplayName("the register holds E87, E88 and E89 for the dr scope, the runbook its section, and the postponed issue its owed runs")
    void theExerciseIsRegisteredAndItsRunsOwed() {
        String register = read("docs/architecture/RESILIENCE_EXPERIMENTS.md");
        for (String id : List.of("E87", "E88", "E89")) {
            Matcher row = Pattern.compile("(?m)^\\| " + id + " \\|.*$").matcher(register);
            assertThat(row.find()).as("%s registered", id).isTrue();
            assertThat(row.group()).as("%s runs in the dr scope and is not run", id).contains("REHEARSAL_SCOPE=dr").contains("`P16-TSK-025`")
                    .contains("N/A: not run");
        }
        assertThat(read("docs/architecture/OPERATIONS_RUNBOOK.md")).contains("\n### 11.64 ");
        assertThat(read("docs/project/POSTPONED_ISSUES.md")).contains("**`P16-TSK-025`**");
    }
}
