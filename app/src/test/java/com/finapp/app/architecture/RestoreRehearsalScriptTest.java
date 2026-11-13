package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.restore.RestoreVerdicts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the restore rehearsal holds about itself (`P15-TSK-030`; {@code OPERATIONS_RUNBOOK.md} section 11.35): the order of its steps
 * that makes the first rule true, how the backup is handled, and that every plant is refused by its own check alone.
 *
 * <p>The rehearsal's result is a run's, recorded in section 11.35, and nothing here claims it. What a hermetic test can hold is that a
 * later edit does not quietly reorder the release before the verification, write the dump to a disk, drop the counter step or let a
 * plant pass on any check that happens to catch it.
 */
@Tag("architecture")
@DisplayName("the restore rehearsal holds what it says it holds (P15-TSK-030)")
class RestoreRehearsalScriptTest {

    private static final String HARNESS = "infra/scripts/restore-rehearsal.sh";

    private static Path root;
    private static String harness;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = EvidenceCitations.read(root.resolve(HARNESS));
    }

    @Test
    @DisplayName("the harness can run where it will run: it is committed executable")
    void theHarnessIsExecutable() throws Exception {
        Process git = new ProcessBuilder("git", "ls-files", "-s", HARNESS).directory(root.toFile()).redirectErrorStream(true).start();
        String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git answered: %s", index).isZero();
        assertThat(index).as("the mode the index holds").startsWith("100755 ");
    }

    @Test
    @DisplayName("the first rule: the platform's three roles are NOLOGIN in the role step's own transaction, refused at log-in before"
            + " pg_restore, and released once, after a verification with no FAIL")
    void theRolesAreHeldUntilTheVerificationPasses() {
        String roles = function("roles");
        assertThat(roles).contains("ALTER ROLE finapp_app NOLOGIN;").contains("ALTER ROLE finapp_migrator NOLOGIN;")
                .contains("ALTER ROLE finapp_reporting NOLOGIN;").contains("| sql_file \"$1\"");
        assertThat(function("sql_file")).as("the role step is one transaction").contains("--single-transaction");

        String restore = function("restore");
        int held = restore.indexOf("roles \"$target\" held");
        int refused = restore.indexOf("not permitted to log in");
        int restored = restore.indexOf("pg_restore");
        assertThat(held).as("the roles held").isNotNegative();
        assertThat(refused).as("each role refused at log-in, after the hold").isGreaterThan(held);
        assertThat(restored).as("and only then the restore").isGreaterThan(refused);
        assertThat(restore).contains("--single-transaction --exit-on-error");

        // P16-TSK-024: the one release is the gate's, its last step, and the gate is reached only past a verification with no FAIL.
        List<Integer> releases = occurrences(harness, "ALTER ROLE finapp_app LOGIN");
        assertThat(releases).as("one release in the whole script").hasSize(1);
        String gate = function("gate");
        assertThat(gate).as("the release is the gate's").contains("ALTER ROLE finapp_app LOGIN");
        int verified = harness.indexOf("verify \"$NAME-restore\" restore");
        int refusedOnFail = harness.indexOf("grep -q '^FAIL|' \"$ART/verdicts-restore.txt\"");
        int gated = harness.indexOf("gate \"$NAME-restore\" restore \"$INSTANT\"");
        assertThat(verified).as("the verification of the restore").isNotNegative();
        assertThat(refusedOnFail).as("a FAIL refuses the release").isGreaterThan(verified);
        assertThat(gated).as("the gate, and with it the release, comes after both").isGreaterThan(refusedOnFail);
        int recovered = harness.indexOf("verify \"$NAME-pitr\" pitr");
        int recoveryRefusedOnFail = harness.indexOf("grep -q '^FAIL|' \"$ART/verdicts-pitr.txt\"");
        assertThat(recoveryRefusedOnFail).as("the recovery's FAIL refuses its release").isGreaterThan(recovered).isPositive();
        assertThat(harness.indexOf("gate \"$NAME-pitr\" pitr \"$T\"")).isGreaterThan(recoveryRefusedOnFail);
        assertThat(harness).as("a planted restore is never released, and that is asserted")
                .contains("the refused restore let finapp_app log in");
    }

    @Test
    @DisplayName("the post-recovery gate (P16-TSK-024, ADR-0128 decision 3): the window reconciled by a command acting as finapp_app while"
            + " the three roles cannot log in, the statement accepted with no break, the books verified again, and only then a recorded"
            + " release")
    void theGateHoldsMoneyUntilThePersonsRecordedRelease() {
        String gate = function("gate");
        int window = gate.indexOf("command_run RestoreReconciliation");
        int held = gate.indexOf("grep -q '^HOLD|acting as finapp_app'");
        int accepted = gate.indexOf("grep -q '^FILE|ACCEPTED|'");
        int noBreak = gate.indexOf("grep -q '^BREAKS|0|'");
        int again = gate.indexOf("verify \"$1\" \"$2-window\"");
        int againRefused = gate.indexOf("grep -q '^FAIL|' \"$ART/verdicts-$2-window.txt\"");
        int release = gate.indexOf("ALTER ROLE finapp_app LOGIN");
        int recorded = gate.indexOf("> \"$ART/release-$2.tsv\"");
        int logins = gate.indexOf("login \"$1\" \"$role\"");
        assertThat(window).as("the window first").isPositive();
        assertThat(List.of(held, accepted, noBreak, again, againRefused, release, recorded, logins))
                .as("the hold, the file, no break, the books again, then the release, its record and the log-ins").isSorted()
                .allMatch(at -> at > window);
        assertThat(gate).as("the command acts as the application's role, never as the administrator")
                .contains("finapp?options=-c%20role%3Dfinapp_app").contains("digests_hold statement").contains("decrypt statement |");
        assertThat(gate).as("the record names who released it, why, when, and the reports it rests on")
                .contains("released_by=${RESTORE_RELEASED_BY:-").contains("released_by\\t").contains("rests_on")
                .contains("SELECT now(); COMMIT;");
        String command = EvidenceCitations.read(root.resolve("app/src/test/java/com/finapp/app/restore/RestoreReconciliation.java"));
        assertThat(command).as("the command refuses to deliver anything unless money is held")
                .contains("AND rolcanlogin").contains("money is not held, and it refuses to run")
                .contains("APPLICATION_ROLE.equals(session)").contains("return 2;");
    }

    @Test
    @DisplayName("point-in-time recovery (P16-TSK-024): the archive encrypted segment by segment with its digest, never overwritten; a"
            + " segment read back only when its digest holds; the base backup into openssl; the recovery held from its first start")
    void thePointInTimeRecoveryIsEncryptedAndHeldFromItsFirstStart() {
        String archive = constant("ARCHIVE_COMMAND");
        assertThat(archive).startsWith("test ! -f /archive/%f.enc && openssl enc -e -aes-256-cbc")
                .contains("sha256sum /archive/%f.part").contains("mv /archive/%f.part /archive/%f.enc")
                .contains("-pass file:/run/finapp-archive-key");
        String restore = constant("RESTORE_COMMAND");
        int digest = restore.indexOf("= \"$(cat /archive/%f.sha256)\"");
        assertThat(digest).as("the digest checked").isPositive().isLessThan(restore.indexOf("openssl enc -d"));
        assertThat(function("server")).contains("-c wal_level=replica -c archive_mode=on -c \"archive_command=$ARCHIVE_COMMAND\"")
                .contains("target=/run/finapp-archive-key,readonly");
        assertThat(harness).as("the base backup is written into openssl, before the seed")
                .containsPattern("pg_basebackup --format=tar --wal-method=fetch[^\\n]*\\R[^\\n]*\\| MSYS_NO_PATHCONV=1 openssl enc -e")
                .contains("ustar ]").contains("a plaintext base backup is at rest");
        assertThat(harness.indexOf("step base-backup")).isLessThan(harness.indexOf("step seed"));
        assertThat(harness).as("the archive holds ciphertext and digests only")
                .contains("grep -Ev '^[0-9A-F]{24}(\\.[0-9A-F]{8}\\.backup|\\.history)?\\.(enc|sha256)$'");

        String pitr = function("pitr");
        int hold = pitr.indexOf("printf \"%s\\n\" \"$HBA_HOLD\"");
        int start = pitr.indexOf("dock start \"$target\"");
        int nologin = pitr.indexOf("ALTER ROLE finapp_app NOLOGIN; ALTER ROLE finapp_migrator NOLOGIN; ALTER ROLE finapp_reporting NOLOGIN");
        int opened = pitr.indexOf("pg_reload_conf()");
        int refused = pitr.indexOf("not permitted to log in");
        assertThat(hold).as("the roles refused by pg_hba.conf before the server first starts").isPositive().isLessThan(start);
        assertThat(nologin).as("NOLOGIN before the refusal lines are removed").isGreaterThan(start).isLessThan(opened);
        assertThat(refused).as("each role's refusal asserted").isGreaterThan(opened);
        assertThat(harness).contains("HBA_HOLD='host all finapp_app,finapp_migrator,finapp_reporting all reject\n"
                + "local all finapp_app,finapp_migrator,finapp_reporting reject'");
        assertThat(pitr).contains("recovery_target_time").contains("recovery_target_inclusive = off")
                .contains("recovery_target_action").contains("touch recovery.signal").contains("digests_hold base");
        assertThat(harness).as("the recovery to T holds the bad write's line, then is compared and verified")
                .contains("pitr pitr \"$ARCHIVE_VOLUME\" \"$T\"").contains("WHERE id = '$BAD_LINE'").contains("compare \"$NAME-pitr\" pitr");
        int point = harness.indexOf("T=$(sql \"$SOURCE\" 'SELECT clock_timestamp()')");
        assertThat(point).as("T read after the backup").isGreaterThan(harness.indexOf("step backup"));
        assertThat(harness.indexOf("--statement")).as("the statement after T").isGreaterThan(point);
        assertThat(harness.indexOf("BAD_LINE=$(sql \"$SOURCE\"")).as("the bad write after the statement")
                .isGreaterThan(harness.indexOf("--statement"));
        assertThat(function("cleanup")).as("the archive's volumes removed on every way out").contains("dock volume rm -f");
    }

    @Test
    @DisplayName("the recovery's plants (P16-TSK-024): the first segment after the base backup withheld, or damaged under its kept digest,"
            + " each in a copy of its own; PostgreSQL refusing to end short of the target is required, a promotion fails the run")
    void theRecoveryRefusesAGapAndADamagedSegment() {
        Matcher defaults = Pattern.compile("PITR_PLANTS=\\$\\{RESTORE_PITR_PLANTS:-([a-z -]+)}").matcher(harness);
        assertThat(defaults.find()).isTrue();
        assertThat(defaults.group(1).trim().split(" +")).containsExactly("wal-gap", "wal-damaged");
        String copy = function("archive_copy");
        assertThat(copy).contains("--env SEGMENT=\"$FIRST_AFTER_BASE\"").contains("wal-gap) rm \"/to/$SEGMENT.enc\"")
                .contains("wal-damaged) printf").contains("source=$ARCHIVE_VOLUME,target=/from,readonly");
        assertThat(constant("NOT_REACHED")).isEqualTo("recovery ended before configured recovery target was reached");
        assertThat(harness).contains("fail \"plant $name: the recovery passed the archive's $name and was promoted\"")
                .contains("grep -q \"$NOT_REACHED\" \"$ART/recovery-$name.log\"");
        assertThat(harness).as("a damaged backup is refused by its digest").contains("step damaged-backup")
                .contains("a backup with one byte changed still matches the backup's digest");
    }

    @Test
    @DisplayName("the key ring (P16-TSK-024, ADR-0128 decision 5): the seed and every verification under two versions, version 2 active;"
            + " the key-version-missing plant verified with a ring that lost version 2; no key on a command line")
    void aRestoreWithAVersionMissingIsVerifiedWithoutIt() {
        String ringed = function("ringed");
        assertThat(ringed).contains("FINAPP_CREDIT_EVIDENCE_RING_VERSIONS=1,2").contains("FINAPP_CREDIT_EVIDENCE_RING_ACTIVEVERSION=2")
                .contains("FINAPP_CREDIT_EVIDENCE_RING_VERSIONS=1 FINAPP_CREDIT_EVIDENCE_RING_ACTIVEVERSION=1");
        assertThat(ringed.substring(ringed.indexOf("else"))).as("without version 2, its key is not given")
                .doesNotContain("FINAPP_CREDIT_EVIDENCE_V2_KEY");
        assertThat(harness).contains("ringed whole command_run RestoreRehearsalSeed")
                .contains("ringed \"${VERIFY_RING:-whole}\" command_run RestoreVerification")
                .contains("VERIFY_RING=without-v2\n        verify \"$NAME-$name\" \"$name\"\n        VERIFY_RING=whole");
        assertThat(function("check_value")).as("a check value from a pipe, as section 11.29 says")
                .contains("printf '%s' \"$1\" | \"$JAVA\" --source 21");
        assertThat(function("verify")).as("a seeded source's verification is a drill's").contains("$DRILL_ARG || code=$?");
        assertThat(harness).contains("DRILL_ARG=--drill").contains("        DRILL_ARG=\"\"");
    }

    @Test
    @DisplayName("RESTORE_STOP_AFTER=source stops before anything is backed up or restored; every restore's rate is recorded")
    void theSourceCheckRestoresNothingAndEveryRestoreIsTimed() {
        int stop = harness.indexOf("if [ \"${RESTORE_STOP_AFTER:-}\" = source ]; then");
        assertThat(stop).isPositive().isLessThan(harness.indexOf("step backup"));
        assertThat(function("rate")).contains("pg_database_size('finapp')").contains(">> \"$ART/rates.tsv\"");
        assertThat(function("restore")).contains("rate \"$target\" \"$label\" logical $(($(millis) - started))");
        assertThat(function("pitr")).contains("rate \"$target\" \"$label\" point-in-time $(($(millis) - started))");
    }

    /** A one-line constant's single-quoted value: {@code NAME='...'}. */
    private static String constant(String name) {
        Matcher value = Pattern.compile("(?m)^" + Pattern.quote(name) + "='([^'\\n]*)'$").matcher(harness);
        assertThat(value.find()).as("the harness defines %s", name).isTrue();
        return value.group(1);
    }

    @Test
    @DisplayName("the backup is the ledger: pg_dump writes into openssl, pg_restore reads from openssl, the work directory is a"
            + " temporary one the exit trap removes, and nothing at rest may begin as a dump does")
    void theBackupIsNeverPlainAtRest() {
        assertThat(harness).contains("WORK=$(mktemp -d)").contains("trap cleanup EXIT").contains("rm -rf \"$WORK\"");
        assertThat(harness).containsPattern("--format=custom\\R(?s).*\\| MSYS_NO_PATHCONV=1 openssl enc -e -aes-256-cbc");
        assertThat(function("restore")).contains("decrypt backup | admin_dock exec -i");
        assertThat(harness).contains("= PGDMP ]").contains("a plaintext dump is at rest");
        assertThat(harness).as("the key is generated for the run").contains("openssl rand -hex 32 > \"$WORK/backup.key\"");
        assertThat(harness).as("the artefacts never hold the backup").doesNotContainPattern("\\$ART/[^\" ]*\\.enc");
        assertThat(function("digests_hold")).as("a damaged file is refused before it is decrypted").contains("does not match its digest");
        assertThat(function("restore")).as("pg_restore's output, which can quote a row, stays in the work directory")
                .contains("> \"$WORK/restore-$label.log\" 2>&1").doesNotContain("$ART/restore-");
        assertThat(harness).as("the manifest's plaintext is removed from the source as it is copied out")
                .contains("cat /tmp/manifest-$part && rm -f /tmp/manifest-$part").contains("rm -rf \"$WORK/manifest\"");
    }

    @Test
    @DisplayName("no password reaches a command line or docker inspect: Docker copies each from the environment, the servers read a file")
    void noPasswordIsOnACommandLine() {
        assertThat(harness).as("docker is never handed a password's value as an argument")
                .doesNotContainPattern("--env [A-Z_]*PASSWORD=").doesNotContainPattern("-e [A-Z_]*PASSWORD=");
        assertThat(function("admin_dock")).contains("PGPASSWORD=\"${generated_for_admin}\" MSYS_NO_PATHCONV=1 docker \"$@\"");
        assertThat(function("server")).contains("--env POSTGRES_PASSWORD_FILE=/run/finapp-admin").doesNotContain("POSTGRES_PASSWORD=");
        assertThat(function("login")).contains("PGPASSWORD=\"${3}\" MSYS_NO_PATHCONV=1 docker exec --env PGPASSWORD ");
        assertThat(harness).as("an interrupted or orphaned run still removes the key and the containers")
                .contains("trap cleanup EXIT").contains("trap 'exit 2' INT TERM HUP PIPE");
        assertThat(harness).as("the PostgreSQL the rehearsal runs is the kind overlay's, by digest")
                .contains("IMAGE=\"postgres:$POSTGRES_VERSION@$POSTGRES_DIGEST\"").contains("deploy/overlays/kind/postgres.yaml");
    }

    @Test
    @DisplayName("the counter: a restore moves the transaction epoch past every restored xid8 before any check reads it, and asserts it")
    void theCounterMovesBeforeTheChecks() {
        String restore = function("restore");
        assertThat(restore).contains("highest=$(sql \"$target\" \"$XID8_SQL\")").contains("epoch \"$target\"")
                .contains("the counter is still at or below the highest restored xid8");
        assertThat(harness).as("the columns are read from the catalogue, not listed").contains("a.atttypid = 'xid8'::regtype");
        int restored = harness.indexOf("restore restore counter");
        assertThat(restored).isNotNegative();
        assertThat(harness.indexOf("compare \"$NAME-restore\" restore")).isGreaterThan(restored);
    }

    @Test
    @DisplayName("every sequence is read in the backup's session and compared with the restore's, at or past it, so one restored behind"
            + " is refused rather than left for an insert to find")
    void everySequenceIsComparedWithTheBackups() {
        assertThat(harness).as("read in the snapshot's session, into the manifest")
                .contains("\"\\\\o /tmp/manifest-sequences\" \"$SEQUENCES_SQL;\"");
        assertThat(function("compare")).contains("sql \"$1\" \"$SEQUENCES_SQL\" > \"$WORK/sequences-$2\"")
                .as("each restored value against the manifest's").contains("' \"$WORK/sequences-$2\" \"$WORK/manifest/sequences\")")
                .contains("if [ -z \"$behind\" ]; then");
    }

    @Test
    @DisplayName("every plant runs by default and is refused by exactly the checks it names and by no other: ten checks each alone,"
            + " a dropped grant by the catalogue and the schema, and the phase gate's removed journal line by every check that reads the lines")
    void everyPlantIsRefusedByItsOwnCheckAlone() {
        Matcher defaults = Pattern.compile("PLANTS=\\$\\{RESTORE_PLANTS:-([a-z -]+)}").matcher(harness);
        assertThat(defaults.find()).as("the default plants were read").isTrue();
        Set<String> plants = new TreeSet<>(List.of(defaults.group(1).trim().split(" +")));
        assertThat(plants).containsExactlyInAnyOrder("unbalanced-entry", "balance-disagrees", "missing-row", "grant-dropped",
                "role-altered", "trigger-dropped", "sequence-behind", "history-checksum", "gl-rule-repointed", "counter-not-moved",
                "journal-line-removed", "key-version-missing");
        // The checks a run answers: the verification's catalogue, and the ones the script's compare() reports itself, read from it.
        Set<String> checks = new TreeSet<>(RestoreVerdicts.CHECKS);
        Matcher scripted = Pattern.compile("echo \"PASS\\|([^|\"]+)\\|").matcher(function("compare"));
        int own = 0;
        while (scripted.find()) {
            checks.add(scripted.group(1));
            own++;
        }
        Matcher compared = Pattern.compile("(?m)^COMPARED=([0-9]+)$").matcher(harness);
        assertThat(compared.find()).as("the harness states how many verdicts compare() writes").isTrue();
        assertThat(own).as("compare() reports flywayValidate, the counts, the catalogue, the schema and the sequences")
                .isEqualTo(5).isEqualTo(Integer.parseInt(compared.group(1)));
        String expected = function("expected_check");
        Set<String> named = new TreeSet<>();
        Set<String> alone = new TreeSet<>();
        for (String plant : plants) {
            Matcher check = Pattern.compile("(?m)^\\s*" + Pattern.quote(plant) + "\\) echo \"([^\"]+)\" ;;").matcher(expected);
            assertThat(check.find()).as("plant %s names its checks", plant).isTrue();
            List<String> refusing = List.of(check.group(1).split(","));
            assertThat(checks).as("plant %s's checks are ones the run answers", plant).containsAll(refusing);
            named.add(check.group(1));
            if (refusing.size() == 1) {
                alone.add(refusing.get(0));
            }
            assertThat(function("plant")).as("plant %s is planted", plant).containsPattern("(?m)^\\s*" + Pattern.quote(plant) + "\\)");
        }
        assertThat(named).as("no two plants expect the same refusal").hasSize(plants.size());
        assertThat(alone).as("ten checks are each shown refusing a plant on their own")
                .containsExactlyInAnyOrder("trial balance per currency", "projection check", "row counts equal to the source's",
                        "catalogue equal to the source's", "schema equal to the source's", "sequences at or past the source's",
                        "flywayValidate", "GL = ledger per period", "source-join census", "evidence held by the key rings");
        assertThat(harness).as("refused by that check alone").contains("if [ \"$failing\" != \"$expected\" ]; then")
                .contains("where only [$expected] must refuse it");
        assertThat(harness).as("a plant that did not land is not a refusal").contains("did not land whole");
    }

    @Test
    @DisplayName("the source is whole by the same checks before it is backed up, and the verification's sessions are read-only")
    void theSourceIsWholeAndTheVerificationCannotWrite() {
        int source = harness.indexOf("verify \"$SOURCE\" source");
        int backup = harness.indexOf("step backup");
        assertThat(source).isNotNegative();
        assertThat(backup).isGreaterThan(source);
        assertThat(function("verify")).contains("options=-c%20default_transaction_read_only%3Don")
                .as("a report as long as the catalogue it states").contains("= \"$checks\" ]");
        String verify = function("verify");
        assertThat(verify.indexOf("rm -f \"$ART/verification-$2.txt\"")).as("an earlier run's report is removed before the command runs")
                .isNotNegative().isLessThan(verify.indexOf("command_run RestoreVerification"));
        assertThat(function("compare")).contains("default_transaction_read_only%3Don").contains("flywayValidate");
    }

    @Test
    @DisplayName("CI runs the rehearsal on master, nightly and when a migration changes, and keeps its artefacts")
    void ciRunsTheRestoreRehearsal() {
        String workflow = EvidenceCitations.read(root.resolve(".github/workflows/ci.yml"));
        assertThat(workflow).contains("  restore-rehearsal:").contains("run: ./infra/scripts/restore-rehearsal.sh")
                .contains("path: build/restore-rehearsal/").contains("src/main/resources/db/migration/");
        assertThat(workflow).as("and when what the rehearsal reads changes: the pins, the chart, the launcher, the code it calls")
                .contains("|^gradle/libs\\.versions\\.toml$").contains("|^infra/scripts/kind-rehearsal/gl-mapping\\.json$")
                .contains("|^app/build\\.gradle\\.kts$").contains("|^deploy/overlays/kind/postgres\\.yaml$")
                .contains("|^(accounting|ledger|accounts|transfers|lending|bnpl|fx|merchant)/src/main/java/");
        assertThat(harness).as("the artefacts CI keeps are the ones the run writes")
                .contains("ART=${RESTORE_ARTEFACTS:-$ROOT/build/restore-rehearsal}");
    }

    /** The body of the shell function {@code name}, from its line to the first closing brace at column one. */
    private static String function(String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(harness);
        assertThat(start.find()).as("the harness defines %s()", name).isTrue();
        int end = harness.indexOf("\n}\n", start.end());
        assertThat(end).as("%s() ends", name).isPositive();
        return harness.substring(start.start(), end);
    }

    private static List<Integer> occurrences(String text, String needle) {
        List<Integer> found = new ArrayList<>();
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found.add(at);
        }
        return found;
    }
}
