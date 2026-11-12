package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the failed-migration rehearsal holds about itself (`P15-TSK-032`; {@code OPERATIONS_RUNBOOK.md} section 11.4): each case the
 * procedure names is planted, the run is made the way the Job or the developer makes it, and the outcome the procedure states is
 * asserted, in the order the procedure takes.
 *
 * <p>The rehearsal's result is a run's, recorded in section 11.4, and nothing here claims it. What a hermetic test can hold is that a
 * later edit does not quietly drop an assertion, loosen an exit code, turn a failed assertion into a passing one, drop a leftover
 * after the run it should precede, or plant into the repository. So each held condition is held with its consequence: a false one is
 * followed by {@code || fail} (or is an {@code if} whose body is {@code fail}), and {@link #theHelpersFailTheRun} holds that
 * {@code fail}, {@code expect_exit}, {@code expect_log} and {@code expect_failure} end the run. That a migration outside a transaction
 * cannot reach the repository is {@code EveryMigrationRunsInATransactionTest}'s, in the migrator module, where the pinned Flyway's
 * parser is.
 */
@Tag("architecture")
@DisplayName("the failed-migration rehearsal holds what runbook section 11.4 says (P15-TSK-032)")
class MigrationFailureRehearsalTest {

    private static final String HARNESS = "infra/scripts/migration-failure-rehearsal.sh";

    private static Path root;
    private static String harness;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = EvidenceCitations.read(root.resolve(HARNESS)).replace("\r\n", "\n");
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
    @DisplayName("every assertion's consequence is a failed run: fail exits 1, cannot exits 2, and expect_exit, expect_log and"
            + " expect_failure call fail on what they do not find")
    void theHelpersFailTheRun() {
        assertThat(harness).as("an unhandled error stops the run too").contains("\nset -eu\n");
        assertThat(function("fail")).contains("echo \"!! $*\" >&2").contains("\n    exit 1");
        assertThat(function("cannot")).contains("\n    exit 2");
        assertThat(function("expect_exit")).contains("if [ \"$2\" != \"$1\" ]; then").contains("fail \"$3: exit $2, not $1\"");
        assertThat(function("expect_log")).contains("grep -q -- \"$2\" \"$WORK/$1.log\" || {").contains("fail \"$3\"; }");
        assertThat(function("expect_failure")).as("a failure is anything but 0, never a pass")
                .contains("if [ \"$1\" = 0 ]; then").contains("fail \"$2: exit 0, where it must fail\"");
    }

    @Test
    @DisplayName("release N: a migration failing on the data stops the run at its schema, exit 10 on the first attempt and DEFINITE;"
            + " the release's earlier migration kept, no row for the failed one and no failed row, the later schema never reached;"
            + " the rows' values looked for in the log, though a CHECK's error names none")
    void aFailedMigrationStopsTheReleaseAndLeavesNoRow() {
        assertThat(plant("V9004__a_rehearsal_limit_is_never_negative.sql")).as("a CHECK that depends on the rows")
                .contains("ADD CONSTRAINT rehearsal_limit_never_negative CHECK (ceiling_minor >= 0);");
        assertThat(step("release N - 1:")).as("rows the CHECK refuses, their value computed so that no statement quotes it")
                .contains("CASE WHEN g % 4 = 0 THEN -(2121 * 2) ELSE 500 END");
        assertThat(harness).as("release N: an expand step, the CHECK, and a migration of a later schema")
                .contains("RELEASE_N=\"$RELEASE_N1 ledger/V9002__a_rehearsal_limit_carries_a_note.sql"
                        + " ledger/V9004__a_rehearsal_limit_is_never_negative.sql transfers/V9001__a_later_schema_in_the_same_release.sql\"");

        String release = step("release N:");
        assertThat(release).contains("image finapp_release n $RELEASE_N || code=$?")
                .contains("expect_exit 10 \"$code\" \"release N's run with a CHECK the data violates\" \"$WORK/n.log\"")
                .contains("expect_log n 'schema=ledger attempts=1 failure=DEFINITE'")
                .contains("expect_log n 'result=FAILED schema=ledger'")
                .contains("expect_log n 'is violated by some row'");
        held(release, "if grep -q '4242' \"$WORK/n.log\"; then");
        held(release, "if grep -q 'schema=transfers' \"$WORK/n.log\"; then");
        held(release, "[ \"$(plants_in finapp_release transfers)\" = \"none\" ]");
        held(release, "[ \"$(plants_in finapp_release ledger)\" = \"9001:true 9002:true\" ]");
        held(release, "[ \"$(sql finapp_release \"SELECT count(*) FROM ledger.flyway_schema_history WHERE NOT success\")\" = 0 ]");
        held(release, "[ \"$(sql finapp_release \"SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ledger'"
                + " AND table_name = 'rehearsal_limit' AND column_name = 'note'\")\" = 1 ]");
        held(release, "[ \"$(sql finapp_release \"SELECT count(*) FROM pg_constraint WHERE conname = 'rehearsal_limit_never_negative'\")\" = 0 ]");
    }

    @Test
    @DisplayName("the readiness guard's own read, as the application's role, shows the schema short of the failed migration, so a replica"
            + " of N would stay unready, and that role still cannot read the history itself")
    void theReadinessReadShowsTheSchemaOlderThanTheRelease() {
        assertThat(function("guard_reads")).contains("app_sql \"$1\"").contains("FROM platform.applied_migration_versions()");
        assertThat(function("app_sql")).contains("psql_as finapp_app \"${FINAPP_DB_APP_PASSWORD}\"");
        String release = step("release N:");
        held(release, "[ \"$(guard_reads finapp_release ledger)\" = \"9001 9002\" ]");
        held(release, "[ \"$(app_sql finapp_release \"SELECT has_table_privilege('ledger.flyway_schema_history', 'SELECT')\")\" = f ]");
        held(step("outside a transaction, on the image:"), "[ \"$(guard_reads finapp_outside ledger)\" = \"9001 9201\" ]");
    }

    @Test
    @DisplayName("the Job run again with nothing fixed fails the same way: exit 10 on the first attempt, nothing applied, the history"
            + " unchanged")
    void theJobRunAgainFailsTheSameWay() {
        String again = step("the Job again");
        assertThat(again).contains("before=$(whole_history finapp_release ledger)")
                .contains("expect_exit 10 \"$code\" \"the same release run again\"")
                .contains("expect_log again 'schema=ledger attempts=1 failure=DEFINITE'");
        held(again, "if grep -q 'applied=[1-9]' \"$WORK/again.log\"; then");
        held(again, "[ \"$(whole_history finapp_release ledger)\" = \"$before\" ]");
        assertThat(function("whole_history")).as("every row, null-safe")
                .contains("concat_ws(':', installed_rank, version, checksum, success, installed_by)");
    }

    @Test
    @DisplayName("where the migration had applied, a fix below it is refused at validation: outOfOrder=false, staging's case whenever"
            + " production's data fails a migration staging passed")
    void aFixBelowAnAppliedVersionIsRefusedWhereTheMigrationApplied() {
        String nMinusOne = step("release N - 1:");
        int copied = nMinusOne.indexOf("sql postgres \"CREATE DATABASE $database TEMPLATE finapp_release\"");
        assertThat(nMinusOne).as("another environment, copied at N - 1").contains("for database in finapp_ahead finapp_outside; do");
        assertThat(copied).isNotNegative();
        assertThat(nMinusOne.indexOf("INSERT INTO ledger.rehearsal_limit")).as("before the rows the CHECK refuses").isGreaterThan(copied);
        String ahead = step("ahead:");
        assertThat(ahead).contains("expect_exit 0 \"$code\" \"release N where no row violates the CHECK\"")
                .contains("image finapp_ahead ahead-fix $FIXED || code=$?")
                .contains("expect_exit 10 \"$code\" \"a fix below a version already applied\"")
                .contains("expect_log ahead-fix 'Detected resolved migration not applied to database: 9003'");
        held(ahead, "[ \"$(plants_in finapp_ahead ledger)\" = \"9001:true 9002:true 9004:true\" ]");
        held(ahead.substring(ahead.indexOf("ahead-fix")), "[ \"$(plants_in finapp_ahead ledger)\" = \"9001:true 9002:true 9004:true\" ]");
    }

    @Test
    @DisplayName("the forward fix, a version below the failed one and above the last applied, lets the release through in order:"
            + " the fix, the CHECK validated, the later schema; then a run applies nothing")
    void aLowerVersionedFixLetsTheReleaseThrough() {
        assertThat(harness).contains("FIXED=\"$RELEASE_N ledger/V9003__rehearsal_limits_made_valid.sql\"");
        String fix = step("the fix:");
        assertThat(fix).contains("expect_exit 0 \"$code\" \"the release with its fix\"")
                .contains("expect_log fixed 'result=APPLIED schemas=[0-9]* applied=3$'")
                .contains("expect_log settled 'result=APPLIED schemas=[0-9]* applied=0$'");
        held(fix, "[ \"$(plants_in finapp_release ledger)\" = \"9001:true 9002:true 9003:true 9004:true\" ]");
        held(fix, "[ \"$(plants_in finapp_release transfers)\" = \"9001:true\" ]");
        held(fix, "[ \"$(sql finapp_release \"SELECT convalidated FROM pg_constraint WHERE conname = 'rehearsal_limit_never_negative'\")\" = t ]");
        held(fix, "[ \"$(guard_reads finapp_release ledger)\" = \"9001 9002 9003 9004\" ]");
        assertThat(function("plants_in")).as("read in the order the history installed them").contains("ORDER BY installed_rank");
    }

    @Test
    @DisplayName("a migration outside a transaction that fails leaves no history row, failed or not, and an INVALID index, on the image"
            + " and on Gradle; every later run trips over that leftover, validation calls the migration pending, and flywayRepair finds"
            + " nothing to remove")
    void aMigrationOutsideATransactionLeavesNoRowAndALeftoverEveryRunTripsOver() {
        assertThat(plant("V9230__a_rehearsal_code_is_unique.sql")).as("the form the build refuses in the repository")
                .contains("CREATE UNIQUE INDEX CONCURRENTLY rehearsal_code_unique ON ledger.rehearsal_code (code);");
        assertThat(plant("V9130__a_rehearsal_reference_is_unique.sql"))
                .contains("CREATE UNIQUE INDEX CONCURRENTLY rehearsal_reference_unique ON ledger.rehearsal_reference (reference);");

        String image = step("outside a transaction, on the image:");
        assertThat(image).contains("before=$(whole_history finapp_outside ledger)")
                .contains("expect_exit 10 \"$code\" \"a unique index built concurrently over duplicate values\"")
                .contains("expect_log outside 'schema=ledger attempts=1 failure=DEFINITE'")
                .contains("expect_log outside '\\[non-transactional\\] failed'")
                .contains("expect_log outside 'could not create unique index'");
        held(image, "[ \"$(whole_history finapp_outside ledger)\" = \"$before\" ]");
        held(image, "[ \"$(index_valid finapp_outside rehearsal_code_unique)\" = f ]");
        String again = step("outside, again:");
        assertThat(again).contains("expect_exit 10 \"$code\" \"the same migration run again over its leftover\"")
                .contains("expect_log outside-again 'relation \"rehearsal_code_unique\" already exists'");
        held(again, "[ \"$(whole_history finapp_outside ledger)\" = \"$before\" ]");
        held(again, "[ \"$(index_valid finapp_outside rehearsal_code_unique)\" = f ]");

        String gradle = step("fails, on Gradle:");
        assertThat(gradle).contains("kept=$(whole_history finapp_dev ledger)")
                .contains("gradle dev-concurrently -q :ledger:flywayMigrate \"$LOCATIONS\" || code=$?")
                .contains("expect_failure \"$code\"")
                .contains("expect_log dev-concurrently '\\[non-transactional\\] failed'");
        held(gradle, "[ \"$(whole_history finapp_dev ledger)\" = \"$kept\" ]");
        held(gradle, "[ \"$(index_valid finapp_dev rehearsal_reference_unique)\" = f ]");
        held(gradle, "[ \"$(guard_reads finapp_dev ledger)\" = \"9101\" ]");
        String nothing = step("nothing to repair:");
        assertThat(nothing).contains("expect_log dev-again 'relation \"rehearsal_reference_unique\" already exists'")
                .contains("expect_log dev-validate 'Detected resolved migration not applied to database: 9130'")
                .contains("gradle dev-repair :ledger:flywayRepair \"$LOCATIONS\" || code=$?")
                .contains("expect_exit 0 \"$code\" \"flywayRepair\"");
        held(nothing, "if grep -q 'Detected failed migration' \"$WORK/dev-validate.log\"; then");
        held(nothing, "[ \"$(whole_history finapp_dev ledger)\" = \"$kept\" ]");
        held(nothing, "[ \"$(index_valid finapp_dev rehearsal_reference_unique)\" = f ]");
        assertThat(function("whole_history")).as("every row, a failed one included").doesNotContain("success = true")
                .doesNotContain("WHERE success");
    }

    @Test
    @DisplayName("on rows that allow it, the same index cannot finish: on the image the runner's lock_timeout ends it while it waits,"
            + " exit 10 and INVALID again with no row; on Gradle it waits on Flyway's own lock-holding session with no bound")
    void anIndexBuiltConcurrentlyCannotFinishUnderFlywaysOwnLock() {
        String clean = step("outside, on clean rows:");
        assertThat(clean.indexOf("drop_leftover finapp_outside rehearsal_code_unique")).as("the leftover dropped first").isNotNegative()
                .isLessThan(clean.indexOf("image finapp_outside outside-clean $OUTSIDE_FIXED || code=$?"));
        assertThat(clean).contains("expect_exit 10 \"$code\" \"the index built concurrently over rows that allow it\"")
                .contains("expect_log outside-clean 'schema=ledger attempts=1 failure=DEFINITE'")
                .contains("expect_log outside-clean 'canceling statement due to lock timeout'");
        held(clean, "[ \"$(plants_in finapp_outside ledger)\" = \"9001:true 9201:true 9220:true\" ]");
        held(clean, "[ \"$(sql finapp_outside \"SELECT count(*) FROM (SELECT code FROM ledger.rehearsal_code GROUP BY code"
                + " HAVING count(*) > 1) d\")\" = 0 ]");
        held(clean, "[ \"$(index_valid finapp_outside rehearsal_code_unique)\" = f ]");
        assertThat(harness).as("the data fix is in that release, below the index")
                .contains("OUTSIDE_FIXED=\"$OUTSIDE_TABLE ledger/V9220__rehearsal_codes_made_unique.sql ledger/V9230__a_rehearsal_code_is_unique.sql\"");

        String waits = function("waiting_on_its_own_lock");
        assertThat(waits).as("the index waiting on a lock").contains("a.state = 'active' AND a.wait_event_type = 'Lock'")
                .contains("cardinality(pg_blocking_pids(a.pid)) > 0")
                .as("held only by sessions of its own client, idle in a transaction holding an advisory lock")
                .contains("WHERE s.application_name IS DISTINCT FROM a.application_name OR s.state <> 'idle in transaction'")
                .contains("l.locktype = 'advisory' AND l.granted");
        String hangs = step("hangs, on Gradle:");
        int found = hangs.indexOf("waiter=$(waiting_on_its_own_lock | head -1)");
        int later = hangs.indexOf("sleep 20");
        int still = hangs.indexOf("[ \"$(waiting_on_its_own_lock | head -1)\" = \"$waiter\" ]");
        int cancelled = hangs.indexOf("sql finapp_dev \"SELECT pg_cancel_backend($waiter)\"");
        assertThat(found).as("found waiting").isNotNegative();
        assertThat(later).as("then 20 s").isGreaterThan(found);
        assertThat(still).as("still the same session, still waiting").isGreaterThan(later);
        assertThat(cancelled).as("and only then cancelled by hand").isGreaterThan(still);
        String afterTheWait = hangs.substring(later);
        held(afterTheWait, "[ \"$(waiting_on_its_own_lock | head -1)\" = \"$waiter\" ]");
        held(afterTheWait, "[ ! -e \"$WORK/dev-hang.exit\" ]");
        String afterTheCancel = hangs.substring(cancelled);
        assertThat(afterTheCancel).contains("expect_log dev-hang 'canceling statement due to user request'");
        held(afterTheCancel, "[ \"$(plants_in finapp_dev ledger)\" = \"9101:true 9120:true\" ]");
        held(afterTheCancel, "[ \"$(index_valid finapp_dev rehearsal_reference_unique)\" = f ]");
    }

    @Test
    @DisplayName("the way out: the leftover dropped by the schema's owner within a lock bound, and the never-applied migration corrected"
            + " to build the index in its transaction; the image never repairs anything")
    void theWayOutIsTheOwnersDropAndAnIndexInATransaction() {
        String drop = function("drop_leftover");
        assertThat(drop).contains("owner_sql \"$1\" \"SET lock_timeout = '3s'; DROP INDEX ledger.$2\"");
        held(drop, "[ -z \"$(index_valid \"$1\" \"$2\")\" ]");
        assertThat(function("owner_sql")).contains("psql_as finapp_migrator \"${FINAPP_DB_MIGRATOR_PASSWORD}\"");
        assertThat(function("image")).as("the image has no repair: a Job can ask it for nothing but a migration")
                .doesNotContainIgnoringCase("repair");

        String image = step("outside, forward:");
        assertThat(image.indexOf("drop_leftover finapp_outside rehearsal_code_unique")).isNotNegative()
                .isLessThan(image.indexOf("image finapp_outside outside-forward $OUTSIDE_FIXED || code=$?"));
        assertThat(image).contains("CREATE UNIQUE INDEX rehearsal_code_unique ON ledger.rehearsal_code (code);")
                .doesNotContain("INDEX CONCURRENTLY")
                .contains("expect_exit 0 \"$code\" \"the corrected migration\"")
                .contains("expect_log outside-forward 'result=APPLIED schemas=[0-9]* applied=1$'");
        held(image, "[ \"$(plants_in finapp_outside ledger)\" = \"9001:true 9201:true 9220:true 9230:true\" ]");
        held(image, "[ \"$(index_valid finapp_outside rehearsal_code_unique)\" = t ]");

        String gradle = step("forward, on Gradle:");
        assertThat(gradle.indexOf("drop_leftover finapp_dev rehearsal_reference_unique")).isNotNegative()
                .isLessThan(gradle.indexOf("gradle dev-forward -q :ledger:flywayMigrate \"$LOCATIONS\" || code=$?"));
        assertThat(gradle).contains("CREATE UNIQUE INDEX rehearsal_reference_unique ON ledger.rehearsal_reference (reference);")
                .doesNotContain("INDEX CONCURRENTLY")
                .contains("expect_exit 0 \"$code\" \"flywayValidate after the correction\"");
        held(gradle, "[ \"$(plants_in finapp_dev ledger)\" = \"9101:true 9120:true 9130:true\" ]");
        held(gradle, "[ \"$(index_valid finapp_dev rehearsal_reference_unique)\" = t ]");
        held(gradle, "[ \"$(guard_reads finapp_dev ledger)\" = \"9101 9120 9130\" ]");

        assertThat(harness.indexOf("echo \"== fails, on Gradle:")).as("fails, nothing to repair, hangs, forward, in that order")
                .isNotNegative().isLessThan(harness.indexOf("echo \"== nothing to repair:"));
        assertThat(harness.indexOf("echo \"== nothing to repair:")).isLessThan(harness.indexOf("echo \"== hangs, on Gradle:"));
        assertThat(harness.indexOf("echo \"== hangs, on Gradle:")).isLessThan(harness.indexOf("echo \"== forward, on Gradle:"));
    }

    @Test
    @DisplayName("nothing is planted in the repository and nothing outlives the run, and no password reaches a command line")
    void theRunLeavesNothingAndHoldsNoCredentialInTheOpen() {
        assertThat(harness).contains("WORK=$(mktemp -d)").contains("PLANTS=\"$WORK/plants\"").contains("DEV_LOCATION=\"$WORK/dev-location\"")
                .contains("trap cleanup EXIT").contains("trap 'exit 2' INT TERM HUP PIPE").contains("rm -rf \"$WORK\"");
        assertThat(function("cleanup")).as("every container of the run").contains("dock ps -aq --filter \"name=$NAME\"");
        assertThat(function("plant")).contains("> \"$PLANTS/$1/$2\"");
        assertThat(harness).as("the developer's tasks read the module's own location and the run's").contains(
                "LOCATIONS=\"-Pflyway.locations=filesystem:$LEDGER_LOCATION,filesystem:$(native \"$DEV_LOCATION\")\"");
        assertThat(harness).as("docker is never handed a password's value as an argument")
                .doesNotContainPattern("--env [A-Z_]*PASSWORD=").doesNotContainPattern("-e [A-Z_]*PASSWORD=");
        assertThat(function("psql_as")).contains("PGPASSWORD=\"$2\" MSYS_NO_PATHCONV=1 docker exec --env PGPASSWORD \"$PG\"");
        assertThat(harness).as("the image is the one built for this revision unless one is named")
                .contains("IMAGE=\"finapp-migrator:$REVISION\"").contains("build it with infra/scripts/migrator-image-build.sh");
    }

    @Test
    @DisplayName("CI rehearses it in the migrator image's job, on the image that job built and verified")
    void ciRehearsesItOnTheImageItBuilt() {
        String workflow = EvidenceCitations.read(root.resolve(".github/workflows/ci.yml")).replace("\r\n", "\n");
        Matcher job = Pattern.compile("(?ms)^  migrator-image:\\n(.*?)(?=^  [a-z][a-z0-9-]*:\\n|\\z)").matcher(workflow);
        assertThat(job.find()).as("the migrator-image job").isTrue();
        String steps = job.group(1);
        int built = steps.indexOf("run: ./infra/scripts/migrator-image-build.sh");
        int verified = steps.indexOf("run: ./infra/scripts/migrator-verify.sh");
        int rehearsed = steps.indexOf("run: ./infra/scripts/migration-failure-rehearsal.sh");
        assertThat(built).isNotNegative();
        assertThat(verified).isGreaterThan(built);
        assertThat(rehearsed).as("after the image is built and verified").isGreaterThan(verified);
    }

    /**
     * {@code condition} is in {@code text}, and its consequence is a failed run: {@code || fail} after it, on its line or the next,
     * or, for an {@code if ...; then}, a body that is {@code fail}. Without this a condition held as text could be left in place
     * while its consequence was dropped, and the rehearsal would pass what it asserts against.
     */
    private static void held(String text, String condition) {
        int at = text.indexOf(condition);
        assertThat(at).as("the harness asserts %s", condition).isNotNegative();
        String after = text.substring(at + condition.length());
        if (condition.endsWith("; then")) {
            assertThat(after).as("a true %s fails the run", condition).matches("(?s)\\n[ \\t]+fail \".*");
        } else {
            assertThat(after).as("a false %s fails the run", condition).matches("(?s)[ \\t]*(\\\\\\n[ \\t]*)?\\|\\| fail \".*");
        }
    }

    /** The text of one step, from its {@code echo "== <name>} line to the next step's, or to the next section's rule. */
    private static String step(String name) {
        String opening = "echo \"== " + name;
        int start = harness.indexOf(opening);
        assertThat(start).as("the harness has a step %s", name).isNotNegative();
        assertThat(harness.indexOf(opening, start + 1)).as("one step named %s", name).isNegative();
        int end = harness.length();
        for (String next : new String[] {"\necho \"== ", "\n# ====="}) {
            int at = harness.indexOf(next, start + opening.length());
            if (at >= 0) {
                end = Math.min(end, at);
            }
        }
        return harness.substring(start, end);
    }

    /** The body of the shell function {@code name}, from its line to the first closing brace at column one. */
    private static String function(String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(harness);
        assertThat(start.find()).as("the harness defines %s()", name).isTrue();
        int end = harness.indexOf("\n}\n", start.end());
        assertThat(end).as("%s() ends", name).isPositive();
        return harness.substring(start.start(), end);
    }

    /** The text of one plant, from its {@code plant <schema> <file>} line to the next plant's. */
    private static String plant(String file) {
        Matcher start = Pattern.compile("(?m)^plant [a-z]+ " + Pattern.quote(file) + " \\\\$").matcher(harness);
        assertThat(start.find()).as("the harness plants %s", file).isTrue();
        int end = harness.indexOf("\nplant ", start.end());
        int stop = harness.indexOf("\n\n", start.end());
        return harness.substring(start.start(), Math.min(end < 0 ? harness.length() : end, stop < 0 ? harness.length() : stop));
    }
}
