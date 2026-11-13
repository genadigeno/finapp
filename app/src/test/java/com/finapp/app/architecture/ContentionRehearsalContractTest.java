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
 * What the contention, soak and storm runs hold about themselves ({@code P16-TSK-008}; {@code PHASE_16_PLAN.md} sections 6.3-6.5 and
 * 6.7; {@code OPERATIONS_RUNBOOK.md} section 11.48; {@code RESILIENCE_EXPERIMENTS.md} E38, E39 and E40).
 *
 * <p>The runs are a cluster's, owed under {@code POSTPONED_ISSUES.md} PI-025, and nothing here claims what they measure. What a hermetic
 * test can hold is that a later edit does not quietly take away what a run would prove. A measurement of contention fails by reporting
 * nothing as if nothing happened, so each member refuses one way of doing that:
 *
 * <ol>
 *   <li>the concurrent-financial profile is many clients on few accounts: every transfer between one pair, every checkout to the one
 *       merchant, at the engineering workload and at the target's burst;
 *   <li>before the load the waits' sampler is shown a wait it must place on its row, so a blind sampler cannot pass;
 *   <li>every wait sampled is resolved to its row, an advisory key or a table, every 250 ms, and the sampler must have read the whole load;
 *   <li>every deadlock the server counted has its record in the server's own log, and the log is shown to hold the control's wait;
 *   <li>the soak holds the engineering workload eight hours without the payable read, and reads the tables, autovacuum and checkpoints;
 *   <li>auto_explain is preloaded and inert, opened only by a run's window, never logging a parameter's value;
 *   <li>the index build is a migration's: in a transaction, under the migrator's own lock_timeout read from its code, bounded, dropped;
 *   <li>E40's storm goes through the fault registry, must reach the sweeps, and reads the listings with the tables empty and full.
 * </ol>
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the contention, soak and storm runs hold what they say they hold (P16-TSK-008)")
class ContentionRehearsalContractTest {

    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String DIR = "infra/scripts/kind-rehearsal/load/";

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

    /** One operation's line of a profile. */
    private static String operation(String profile, String name) {
        Matcher line = Pattern.compile("(?m)^\\s*\\{\"name\": \"" + name + "\", .*}").matcher(profile);
        assertThat(line.find()).as("the profile names %s", name).isTrue();
        return line.group();
    }

    @Test
    @DisplayName("the concurrent-financial profile is many clients on few accounts, at the engineering workload and at the target's burst")
    void theConcurrentProfileIsManyClientsOnFewAccounts() {
        String profile = read(DIR + "concurrent.json");
        assertThat(profile).as("its waits sampled, its transfers between one pair")
                .contains("  \"contention\": true,").contains("  \"transferPair\": true,")
                .as("the engineering workload (E30's step 3) and W2's burst, three times the peak for 60 s, twice")
                .contains("\"stages\": [[900, 0.3906], [60, 3.0], [540, 0.3906], [60, 3.0], [240, 0.3906]],");
        for (String driven : List.of("transfer", "checkout", "topup")) {
            assertThat(operation(profile, driven)).as("%s is driven, at the target's peak scaled by stage", driven)
                    .contains("\"generator\": \"k6\"").contains("\"per\": \"1000s\"").contains("\"money\": true");
        }
        for (String undriven : List.of("read", "signin", "merchant", "bnpl", "lending", "credit")) {
            assertThat(operation(profile, undriven)).as("%s is left undriven and says why", undriven)
                    .contains("\"generator\": \"none\"").containsPattern("\"reason\": \"[^\"]{40,}\"");
        }
        assertThat(operation(profile, "bnpl")).as("the capital row's contention cannot be driven on kind, and the profile says so")
                .contains("no kind deployment can configure a bureau").contains("LENDING_CAPITAL");
        assertThat(read(DIR + "scenarios.js")).as("the pair: the first two customers, one way and back by turns")
                .contains("const all = profile.transferPair ? fixture.customers.slice(0, 2) : fixture.customers;")
                .contains("const from = all[it % all.length];").contains("const to = all[(it + 1) % all.length];");
    }

    @Test
    @DisplayName("before the load the sampler is shown a wait on one wallet's balance row, and must place it on that row")
    void theSamplerSeesAWaitItWasShown() {
        String control = function(load, "load_contention_control");
        assertThat(control).as("a balance row held four seconds while a second session waits on it")
                .contains("FROM ledger.account_balance WHERE ledger_account_id = '$account' FOR UPDATE; SELECT pg_sleep(4); COMMIT;")
                .contains("psql_query -c \"SELECT 1 FROM ledger.account_balance WHERE ledger_account_id = '$account' FOR UPDATE\"")
                .as("placed on that very row in at least four samples, or the run fails")
                .contains("balance:CUSTOMER_WALLET:[A-Z]*:$account|")
                .contains("[ \"$seen\" -ge 4 ] || fail \"the contention sampler did not see the wait it was shown");
        String start = function(load, "load_contention_start");
        assertThat(start.indexOf("load_contention_control")).as("the control after the sampler starts")
                .isGreaterThan(start.indexOf("< \"$LOAD_SRC/contention.sql\""));
        String run = function(load, "load_run");
        assertThat(run.indexOf("[ \"$LOAD_CONTENTION_ON\" != true ] || load_contention_start")).as("the control before the generator starts")
                .isPositive().isLessThan(run.indexOf("dock run -d --name \"$CLUSTER-load-k6-$i\""));
        assertThat(function(load, "load_preflight")).contains("LOAD_CONTENTION_ON=$(load_profile_value contention)");
    }

    @Test
    @DisplayName("every wait is resolved to its row every 250 ms, and the sampler must have read the whole load")
    void everyWaitIsResolvedToItsRow() {
        String sql = read(DIR + "contention.sql");
        assertThat(sql).as("client backends of finapp waiting on a heavyweight lock")
                .contains("WHERE a.wait_event_type = 'Lock'").contains("AND a.datname = 'finapp'")
                .as("a balance row and an account row named by their account, by the row's position while the holder holds it")
                .contains("WHEN l.locktype = 'tuple' AND l.relation = 'ledger.account_balance'::regclass THEN")
                .contains("'balance:' || la.purpose || ':' || la.currency || ':' || la.id")
                .contains("WHEN l.locktype = 'tuple' AND l.relation = 'ledger.ledger_account'::regclass THEN")
                .contains("WHERE b.ctid = format('(%s,%s)', l.page, l.tuple)::tid")
                .as("an advisory key by its namespace, a table by its name")
                .contains("WHEN l.locktype = 'advisory' THEN 'advisory:' || l.classid || ':' || l.objid")
                .contains("ELSE 'table:' || l.relation::regclass::text")
                .as("a tick every sample, so a sample with no wait still shows the sampler alive; every 250 ms")
                .contains("SELECT 'tick', s.at, (SELECT count(*) FROM waiting)").contains("\\watch interval=0.25 count=:count")
                .as("no amount is read").doesNotContain("posted_minor").doesNotContain("amount_minor");
        String awk = read(DIR + "contention.awk");
        assertThat(awk).as("a wait is one transaction on one target across consecutive samples")
                .contains("k = $3 SUBSEP $4 SUBSEP $6").contains("for (k in last) if (seenat[k] < ticks - 1) close_wait(k)")
                .contains("print \"target\\tstage\\twaits\\twaiting_seconds\\tp50_ms\\tp95_ms\\tlongest_ms\\tmost_waiting\"");
        assertThat(function(load, "load_contention_judge")).as("the samples cover the load, three a second at least, no gap over 5 s")
                .contains("[ \"${samples:-0}\" -ge $((secs * 3)) ] || fail").contains("[ \"${gap:-99999}\" -le 5000 ] || fail");
    }

    @Test
    @DisplayName("every deadlock the server counted has its record in the server's own log, which holds the control's wait too")
    void everyDeadlockHasItsRecord() {
        assertThat(function(load, "load_contention_start")).as("the server logs every wait of a second or more, on the run's own server")
                .contains("pg_server_set \"$LOAD_ART\" log_lock_waits=on").contains("LOAD_DEADLOCKS_FROM=$(load_deadlocks)");
        assertThat(function(load, "load_contention_start")).as("the server's log followed as it is written: rotation at 10 MiB loses nothing")
                .contains("pg_log_follow \"$LOAD_ART/postgres-server.log\"");
        assertThat(function(load, "pg_log_follow")).contains("kube -n finapp logs -f finapp-postgres-0 -c postgres");
        String read = function(load, "load_contention_read");
        String stop = "pg_log_stop \"$LOAD_CONTENTION_LOG\" \"$LOAD_ART/postgres-server.log\"";
        assertThat(read.indexOf(stop)).as("the log's follower stopped in the reading").isPositive();
        assertThat(read.indexOf("LOAD_DEADLOCKS_END=$(load_deadlocks)")).as("the server's count read before its log is closed, and again after")
                .isPositive().isLessThan(read.indexOf(stop));
        assertThat(read.indexOf("LOAD_DEADLOCKS_AFTER=$(load_deadlocks)")).isGreaterThan(read.indexOf(stop));
        assertThat(function(load, "load_contention_judge")).as("the log's records between the two counts, or the run fails")
                .contains("[ \"${logged:-0}\" -ge \"$counted\" ] && [ \"${logged:-0}\" -le \"$most\" ]")
                .as("the control's wait is in the log: a reader that sees nothing cannot pass")
                .contains("$4 == \"acquired\" && $5 == \"account_balance\"");
        assertThat(read(DIR + "locks.awk")).as("deadlocks and serialisation failures kept whole, every long wait with its relation")
                .contains("message ~ /^deadlock detected/").contains("message ~ /^could not serialize access/")
                .contains("(still waiting for|acquired|detected deadlock while waiting for)")
                .contains("printf \"deadlocks %d\\nserialisation %d\\nwaits %d\\nunended %d\\n\"");
        assertThat(function(load, "load_judge")).contains("[ \"$LOAD_CONTENTION_ON\" != true ] || load_contention_judge");
    }

    @Test
    @DisplayName("the soak holds the engineering workload eight hours without the payable read, and reads the tables, autovacuum and checkpoints")
    void theSoakReadsTheTablesTheVacuumAndTheCheckpoints() {
        String profile = read(DIR + "soak.json");
        assertThat(profile).as("eight hours at the engineering workload, never past the break, the payable read left out")
                .contains("\"stages\": [[28800, 0.3906]],").contains("  \"soak\": true,").contains("  \"merchantPayable\": false,")
                .doesNotContain("\"stress\"");
        assertThat(read(DIR + "scenarios.js")).as("the merchant read leaves the payable out where the profile says so")
                .contains("const payable = profile.merchantPayable !== false && exec.scenario.iterationInTest % 2 === 1;");
        assertThat(function(load, "load_soak_start")).as("every autovacuum run and checkpoint logged on the run's own server")
                .contains("pg_server_set \"$LOAD_ART\" log_autovacuum_min_duration=0 log_checkpoints=on");
        String sql = read(DIR + "soak.sql");
        for (String table : List.of("('ledger', 'journal_line')", "('ledger', 'account_balance')", "('platform', 'outbox_event')",
                "('platform', 'inbox_message')", "('platform', 'window_counter')", "('platform', 'idempotency_record')",
                "('payments', 'sweep_row_failure')", "('merchant', 'sweep_row_failure')")) {
            assertThat(sql).as("section 6.7's table %s read at every sample", table).contains(table);
        }
        assertThat(sql).as("growth and bloat as differences, WAL by its position").contains("t.n_dead_tup").contains("pg_total_relation_size(t.relid)")
                .contains("pg_current_wal_lsn() - '0/0'::pg_lsn");
        assertThat(function(load, "load_soak_judge")).as("the samples cover the soak, and the log held its checkpoints")
                .contains("-ge $(( $(load_total_seconds) / 60 * 9 / 10 )) ] || fail")
                .contains("grep -q 'checkpoint complete' \"$LOAD_ART/checkpoints.txt\" || fail");
    }

    @Test
    @DisplayName("auto_explain is preloaded and inert, opened only by a run's window, and never logs a parameter's value")
    void thePlansWindowIsInertUntilARunOpensIt() {
        String overlay = read("deploy/overlays/kind/postgres.yaml");
        assertThat(overlay).as("preloaded on the kind overlay's test-only server").contains("- shared_preload_libraries=pg_stat_statements,auto_explain")
                .as("its threshold never set by the overlay").doesNotContain("auto_explain.log_min_duration");
        assertThat(load).as("the window's settings: analyze, buffers, no timing, verbose for the identifier, no parameter's value")
                .contains("PG_PLANS_SETTINGS=\"auto_explain.log_analyze=on auto_explain.log_buffers=on auto_explain.log_timing=off"
                        + " auto_explain.log_verbose=on auto_explain.log_parameter_max_length=0 auto_explain.log_min_duration=0\"");
        assertThat(function(load, "pg_plans_close")).as("the threshold reset first, then the plans read")
                .startsWith("\n    pg_server_reset \"$1\" auto_explain.log_min_duration ");
        assertThat(read(DIR + "plans.awk")).as("each top statement's plan found by its Query Identifier, every sequential scan counted")
                .contains("match(plan, /\\tQuery Identifier: -?[0-9]+/)").contains("Seq Scan on");
        assertThat(function(load, "load_soak_tick")).as("the soak's window over 80% to 83% of the load, a twentieth of every plan")
                .contains("[ \"$1\" -ge $(($2 * 80 / 100)) ]").contains("pg_plans_open \"$LOAD_ART\" 0.05")
                .contains("[ \"$1\" -ge $(($2 * 83 / 100)) ]");
    }

    @Test
    @DisplayName("the index build is a migration's: in its transaction, under the migrator's own lock_timeout, bounded, dropped at once")
    void theIndexBuildIsAMigrationsBuild() {
        String sql = read(DIR + "index-build.sql");
        assertThat(sql).as("in a transaction, never concurrently")
                .contains("BEGIN;\nSET LOCAL lock_timeout = :'lock_timeout';\nSET LOCAL statement_timeout = '600s';\n"
                        + "CREATE INDEX rehearsal_build_probe ON ledger.journal_line (ledger_account_id, id);\nCOMMIT;")
                .doesNotContain("CONCURRENTLY")
                .as("timed from the lock request to the commit, then dropped").contains("SELECT 'before', round(extract(epoch FROM clock_timestamp())")
                .contains("DROP INDEX ledger.rehearsal_build_probe;");
        String build = function(load, "load_index_build");
        assertThat(build).as("the migrator's lock_timeout read from its code, never copied")
                .contains("Duration LOCK_TIMEOUT = Duration\\.ofSeconds(\\([0-9]*\\));")
                .contains("migrator/src/main/java/com/finapp/migrator/MigrationPlan.java")
                .contains("psql_query -v \"lock_timeout=${seconds}s\" -f - < \"$LOAD_SRC/index-build.sql\"");
        assertThat(read("migrator/src/main/java/com/finapp/migrator/MigrationPlan.java")).as("the value the harness reads is where it reads it")
                .containsPattern("Duration LOCK_TIMEOUT = Duration\\.ofSeconds\\([0-9]+\\);");
        assertThat(function(load, "load_index_judge")).as("a build that never completed, or a throwaway index left, fails the run")
                .contains("[ -n \"$before\" ] && [ -n \"$built\" ] || fail").contains("[ \"$dropped\" = 0 ] || fail");
    }

    @Test
    @DisplayName("E40's storm goes through the fault registry, must reach the sweeps, and reads the listings with the tables empty and full")
    void theStormReachesTheSweepsThroughTheRegistry() {
        String fault = function(experiments, "E40_fault");
        assertThat(fault).as("every call to the simulator hangs, through the registry that removes it on every way out")
                .contains("experiments_fault_on toxic simulator e40-storm timeout timeout=0").doesNotContain("experiments_toxiproxy");
        String steady = function(experiments, "E40_steady");
        assertThat(steady).as("the storm's length read from the code: the dispatched age and fifteen minutes")
                .contains("finapp\\.payments\\.sweeper\\.dispatched-age:PT\\([0-9]*\\)M}").contains("E40_STORM=$((age * 60 + 900))")
                .contains("platform/src/main/java/com/finapp/platform/persistence/SweepBackoff.java")
                .as("the tables empty before the storm, and the listings' plans read so")
                .contains("[ \"$(e40_rows)\" = 0 ] || fail").contains("pg_plans_close \"$EXP_DIR/empty\" \"s.query LIKE '%sweep_row_failure%'\"")
                .as("a window that caught no listing's plan fails")
                .contains("*\"caught 0 \"*) fail \"E40: the window with the tables empty caught no listing's plan");
        String observe = function(experiments, "E40_observe");
        assertThat(observe).as("the storm's plans read, and a storm that recorded nothing fails")
                .contains("pg_plans_close \"$EXP_DIR/storm\" \"s.query LIKE '%sweep_row_failure%'\"")
                .contains("*\"caught 0 \"*) fail \"E40: the storm's window caught no listing's plan")
                .contains("[ \"${rows:-0}\" -ge 1 ] || fail \"E40: the storm recorded no sweep row's failure")
                .contains("EXP_STOPPED=\"a correctness page during the storm: $firing\"");
        assertThat(function(experiments, "E40_recover")).as("every candidate resolved once the provider answers, within the read bound")
                .contains("[ \"$(date +%s)\" -lt \"$deadline\" ] || fail \"E40: $open payment attempts still unresolved")
                .contains("[ \"$(workload_unresolved e40)\" = 0 ] || fail");
        assertThat(function(experiments, "e40_tables")).as("both record tables, by leg, with their writes, HOT updates, dead rows and bytes")
                .contains("FROM payments.sweep_row_failure GROUP BY leg").contains("FROM merchant.sweep_row_failure GROUP BY leg")
                .contains("t.n_tup_hot_upd").contains("t.n_dead_tup").contains("pg_total_relation_size(t.relid)");
    }
}
