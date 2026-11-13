package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the posting scope's runs hold about themselves ({@code P16-TSK-011}; ADR-0141; {@code OPERATIONS_RUNBOOK.md} section 11.56;
 * {@code RESILIENCE_EXPERIMENTS.md} E62, E63 and E64).
 *
 * <p>The runs are a cluster's, owed under {@code POSTPONED_ISSUES.md} PI-025, and nothing here claims what they measure. What a hermetic
 * test holds is that a later edit does not quietly take away what a run would prove, each member refusing one way a run could pass
 * without proving it:
 *
 * <ol>
 *   <li>E62 reads the lookup and the conditional insert from the run's own statements after a contention profile, and fails when
 *       either is absent, so a run that drove another posting path cannot report its cost;
 *   <li>E63's age is one transaction with the freeze trigger enabled again inside it, goes through the safety boundary first, and the
 *       sweep it waits for is the replicas' own, read from the code; it fails when a record is left, when the counter did not move, when
 *       a younger record went, or when the journal took a second entry under one scope;
 *   <li>E64 times the census and both builds as the migrator would run them, under its own lock_timeout, each twin dropped, after the
 *       journal_line build, and fails on a scope held twice.
 * </ol>
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the posting scope's runs hold what they say they hold (P16-TSK-011)")
class PostingScopeRehearsalContractTest {

    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";

    private static Path root;
    private static String load;
    private static String experiments;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        load = read(LOAD);
        experiments = read(EXPERIMENTS);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    /** The body of one shell function, so an assertion cannot be satisfied by another function's text. */
    private static String function(String text, String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(text);
        assertThat(start.find()).as("the harness defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(text);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return text.substring(start.end(), end.start());
    }

    @Test
    @DisplayName("E62 reads the posting scope's statements and indexes after every contention profile, and fails when the path is not this tree's")
    void e62ReadsThePostingPathItDrove() {
        assertThat(function(load, "load_judge")).as("after the contention profile's load")
                .contains("[ \"$LOAD_CONTENTION_ON\" != true ] || load_posting_scope");
        String reader = function(load, "load_posting_scope");
        assertThat(reader)
                .contains("pg_admin -f - < \"$LOAD_SRC/posting-scope.sql\" > \"$LOAD_ART/posting-scope.tsv\"")
                .contains("WHERE s.indexrelname IN ('journal_entry_one_per_idempotency_scope', 'idempotency_record_sweepable')")
                .contains("for step in scope_lookup entry_insert; do")
                .contains("|| fail \"E62: no $step statement in pg_stat_statements after the load")
                .contains("|| fail \"E62: the posting scope's unique index is not on the run's journal");
        String sql = read("infra/scripts/kind-rehearsal/load/posting-scope.sql");
        assertThat(sql)
                .contains("WHEN s.query LIKE 'SELECT id FROM ledger.journal_entry WHERE idempotency_scope = %' THEN 'scope_lookup'")
                .contains("WHEN s.query LIKE 'INSERT INTO ledger.journal_entry %ON CONFLICT (idempotency_scope) DO NOTHING%' THEN 'entry_insert'")
                .contains("WHERE d.datname = 'finapp'");
        // The statements it names are the code's own.
        assertThat(read("ledger/src/main/java/com/finapp/ledger/JdbcJournalEntryStore.java"))
                .contains("\"SELECT id FROM \" + ENTRY_TABLE + \" WHERE idempotency_scope = ?\"")
                .contains("\" ON CONFLICT (idempotency_scope) DO NOTHING\"");
    }

    @Test
    @DisplayName("E63 ages the records in one transaction behind the boundary, waits for the replicas' own sweep, and fails on anything it did not prove")
    void e63SweepsByTheReplicasOwnSchedules() {
        String age = read("infra/scripts/kind-rehearsal/experiments/e63-age.sql");
        int disable = age.indexOf("ALTER TABLE platform.idempotency_record DISABLE TRIGGER idempotency_record_guard_transitions;");
        int enable = age.indexOf("ALTER TABLE platform.idempotency_record ENABLE TRIGGER idempotency_record_guard_transitions;");
        assertThat(age.indexOf("BEGIN;")).as("one transaction around the trigger set aside").isNotNegative().isLessThan(disable);
        assertThat(disable).isLessThan(enable);
        assertThat(age.indexOf("COMMIT;")).isGreaterThan(enable);
        assertThat(age).contains("\\set ON_ERROR_STOP on")
                .contains("WHERE state IN ('COMPLETED', 'FAILED') AND completed_at < transaction_timestamp() - interval '30 seconds'")
                .contains("SELECT 'trigger', tgenabled FROM pg_trigger WHERE tgname = 'idempotency_record_guard_transitions';")
                .doesNotContain("DELETE");

        String fault = function(experiments, "E63_fault");
        assertThat(fault.indexOf("experiments_refuses || fail")).as("the boundary before the age")
                .isNotNegative().isLessThan(fault.indexOf("e63-age.sql"));
        assertThat(fault).contains("= O ] || fail \"E63: the records' freeze trigger is not enabled after the age\"")
                .contains("|| cannot \"E63: no terminal record was there to age");
        String steady = function(experiments, "E63_steady");
        assertThat(steady).as("the sweep's interval read from the code, the sweep present in the tree")
                .contains("finapp\\.retention\\.sweep\\.interval:PT\\([0-9]*\\)M}")
                .contains("grep -q 'idempotencyRetentionSweepSchedule'")
                .contains("experiments_start_workload e63");
        String observe = function(experiments, "E63_observe");
        assertThat(observe).contains("experiments_pages_tick || return 0")
                .contains("|| fail \"E63: $left of $E63_AGED aged records still kept")
                .contains("|| fail \"E63: the aged records are gone and the fleet's swept counter did not move");
        String recover = function(experiments, "E63_recover");
        assertThat(recover).contains("|| fail \"E63: $young of the $E63_YOUNG records the age left younger remain")
                .contains("*'duplicate key value violates unique constraint \"journal_entry_one_per_idempotency_scope\"'*) ;;")
                .contains("experiments_workload_end e63");
        String duplicate = read("infra/scripts/kind-rehearsal/experiments/e63-duplicate.sql");
        assertThat(duplicate).contains("\\set VERBOSITY terse").contains("BEGIN;").contains("ROLLBACK;").doesNotContain("COMMIT")
                .contains("SELECT gen_random_uuid(), e.posting_date").contains("e.idempotency_scope, e.created_at");
        assertThat(experiments).as("E63's functions are this toolkit's, run by experiment_run's step shape")
                .contains("\nE63_steady() {").contains("\nE63_fault() {").contains("\nE63_observe() {").contains("\nE63_recover() {");
        assertThat(read("infra/scripts/kind-rehearsal/census.sql")).as("the census reads one journal entry per posting scope")
                .contains("'one journal entry per posting scope'");
    }

    @Test
    @DisplayName("E64 times the census and both builds as the migrator would, after the journal_line build, each twin dropped, a scope held twice a failure")
    void e64TimesTheBuildsAsTheMigratorWould() {
        String tick = function(load, "load_soak_tick");
        assertThat(tick).contains("[ \"$1\" -ge $(($2 * 92 / 100)) ] && ! kill -0 \"$LOAD_INDEX\" 2>/dev/null; then")
                .contains("load_scope_build &");
        assertThat(tick.indexOf("load_scope_build &")).isGreaterThan(tick.indexOf("load_index_build &"));
        String build = function(load, "load_scope_build");
        assertThat(build).as("the migrator's own lock_timeout, read from its code")
                .contains("Duration LOCK_TIMEOUT = Duration\\.ofSeconds(\\([0-9]*\\));")
                .contains("psql_query -v \"lock_timeout=${seconds}s\" -f - < \"$LOAD_SRC/scope-build.sql\"");
        String sql = read("infra/scripts/kind-rehearsal/load/scope-build.sql");
        assertThat(sql)
                .contains("CREATE UNIQUE INDEX rehearsal_scope_probe ON ledger.journal_entry (idempotency_scope);")
                .contains("CREATE INDEX rehearsal_sweepable_probe ON platform.idempotency_record (expires_at) WHERE state IN ('COMPLETED', 'FAILED');")
                .contains("DROP INDEX ledger.rehearsal_scope_probe;").contains("DROP INDEX platform.rehearsal_sweepable_probe;")
                .contains("SET LOCAL lock_timeout = :'lock_timeout';").contains("SET LOCAL statement_timeout = '600s';")
                .doesNotContain("CONCURRENTLY").doesNotContain("INSERT");
        String judge = function(load, "load_scope_judge");
        assertThat(judge).contains("|| fail \"the posting scope's builds never started")
                .contains("[ \"$dropped\" = 0 ] || fail")
                .contains("[ \"$4\" = 0 ] || fail \"the census found $4 posting scopes held by more than one journal entry");
        assertThat(function(load, "load_soak_judge")).contains("    load_index_judge\n    load_scope_judge\n");
        // The twins are the migrations' own shapes.
        assertThat(read("ledger/src/main/resources/db/migration/ledger/V037__one_journal_entry_per_posting_scope.sql"))
                .contains("ON ledger.journal_entry (idempotency_scope);");
        assertThat(read("platform/src/main/resources/db/migration/platform/V016__the_idempotency_record_sweep.sql"))
                .contains("ON platform.idempotency_record (expires_at)\n    WHERE state IN ('COMPLETED', 'FAILED');");
    }
}
