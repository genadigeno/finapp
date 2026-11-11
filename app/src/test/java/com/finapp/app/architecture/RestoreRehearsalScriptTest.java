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

        List<Integer> releases = occurrences(harness, "ALTER ROLE finapp_app LOGIN");
        assertThat(releases).as("one release in the whole script").hasSize(1);
        int release = releases.get(0);
        int verified = harness.indexOf("verify \"$NAME-restore\" restore");
        int refusedOnFail = harness.indexOf("grep -q '^FAIL|' \"$ART/verdicts-restore.txt\"");
        assertThat(verified).as("the verification of the restore").isNotNegative();
        assertThat(refusedOnFail).as("a FAIL refuses the release").isGreaterThan(verified);
        assertThat(release).as("the release comes after both").isGreaterThan(refusedOnFail);
        assertThat(harness).as("a planted restore is never released, and that is asserted")
                .contains("the refused restore let finapp_app log in");
    }

    @Test
    @DisplayName("the backup is the ledger: pg_dump writes into openssl, pg_restore reads from openssl, the work directory is a"
            + " temporary one the exit trap removes, and nothing at rest may begin as a dump does")
    void theBackupIsNeverPlainAtRest() {
        assertThat(harness).contains("WORK=$(mktemp -d)").contains("trap cleanup EXIT").contains("rm -rf \"$WORK\"");
        assertThat(harness).containsPattern("--format=custom\\R(?s).*\\| MSYS_NO_PATHCONV=1 openssl enc -e -aes-256-cbc");
        assertThat(function("restore")).contains("decrypt backup | dock exec -i");
        assertThat(harness).contains("= PGDMP ]").contains("a plaintext dump is at rest");
        assertThat(harness).as("the key is generated for the run").contains("openssl rand -hex 32 > \"$WORK/backup.key\"");
        assertThat(harness).as("the artefacts never hold the backup").doesNotContainPattern("\\$ART/[^\" ]*\\.enc");
        assertThat(function("digests_hold")).as("a damaged file is refused before it is decrypted").contains("does not match its digest");
        assertThat(function("restore")).as("pg_restore's output, which can quote a row, stays in the work directory")
                .contains("> \"$WORK/restore-$label.log\" 2>&1").doesNotContain("$ART/restore-");
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
    @DisplayName("every plant runs by default and is refused by its own check and by no other, each a check the run answers")
    void everyPlantIsRefusedByItsOwnCheckAlone() {
        Matcher defaults = Pattern.compile("PLANTS=\\$\\{RESTORE_PLANTS:-([a-z -]+)}").matcher(harness);
        assertThat(defaults.find()).as("the default plants were read").isTrue();
        Set<String> plants = new TreeSet<>(List.of(defaults.group(1).trim().split(" +")));
        assertThat(plants).containsExactlyInAnyOrder("unbalanced-entry", "balance-disagrees", "missing-row", "grant-dropped",
                "counter-not-moved");
        // The checks a run answers: the verification's catalogue, and the three the script's compare() reports itself, read from it.
        Set<String> checks = new TreeSet<>(RestoreVerdicts.CHECKS);
        Matcher scripted = Pattern.compile("echo \"PASS\\|([^|\"]+)\\|").matcher(function("compare"));
        int own = 0;
        while (scripted.find()) {
            checks.add(scripted.group(1));
            own++;
        }
        assertThat(own).as("compare() reports flywayValidate, the counts and the catalogue").isEqualTo(3);
        String expected = function("expected_check");
        Set<String> named = new TreeSet<>();
        for (String plant : plants) {
            Matcher check = Pattern.compile("(?m)^\\s*" + Pattern.quote(plant) + "\\) echo \"([^\"]+)\" ;;").matcher(expected);
            assertThat(check.find()).as("plant %s names its check", plant).isTrue();
            assertThat(checks).as("plant %s's check is one the run answers", plant).contains(check.group(1));
            named.add(check.group(1));
            assertThat(function("plant")).as("plant %s is planted", plant).containsPattern("(?m)^\\s*" + Pattern.quote(plant) + "\\)");
        }
        assertThat(named).as("each plant shows a different check working").hasSize(plants.size());
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
        assertThat(function("compare")).contains("default_transaction_read_only%3Don").contains("flywayValidate");
    }

    @Test
    @DisplayName("CI runs the rehearsal on master, nightly and when a migration changes, and keeps its artefacts")
    void ciRunsTheRestoreRehearsal() {
        String workflow = EvidenceCitations.read(root.resolve(".github/workflows/ci.yml"));
        assertThat(workflow).contains("  restore-rehearsal:").contains("run: ./infra/scripts/restore-rehearsal.sh")
                .contains("path: build/restore-rehearsal/").contains("src/main/resources/db/migration/");
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
