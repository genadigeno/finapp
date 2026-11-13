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
 * What E65 holds about itself ({@code P16-TSK-012}; ADR-0142; {@code OPERATIONS_RUNBOOK.md} section 11.57; {@code RESILIENCE_EXPERIMENTS.md}
 * E65): the readings that would confirm or overturn ADR-0142's two decisions, riding the soak (E39).
 *
 * <p>The run is a cluster's, owed under {@code POSTPONED_ISSUES.md} PI-025, and nothing here claims what it measures. A reading that
 * decides fails by reading nothing and reporting that as cheap, so each member refuses one way of doing that:
 *
 * <ol>
 *   <li>E65 rides the soak alone, off unless asked, and is judged after it;
 *   <li>seven report runs at 10% to 70% of the load, each in the load's own loop, all before the soak's plans window;
 *   <li>the reader reads the statements of report generation's own role, in the finapp database, before and after each run;
 *   <li>a run that did not complete PROVISIONAL, a sample never asked, two samples of one run or a reader that saw nothing fails the run;
 *   <li>the bound is the code's, read and never copied, and the fit is the slowest statement's, the bound being a statement's;
 *   <li>every table soak.sql names is read for its growth, per hour and per money movement, and the journal must have grown.
 * </ol>
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("E65's readings hold what they say they hold (P16-TSK-012)")
class DecisionReadingsRehearsalContractTest {

    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String DIR = "infra/scripts/kind-rehearsal/load/";

    private static Path root;
    private static String load;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        load = read(LOAD);
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
    @DisplayName("E65 rides the soak alone, off unless asked, and is judged after the load")
    void theReadingsRideTheSoakAlone() {
        assertThat(load).as("off unless asked").contains("LOAD_DECISIONS=${REHEARSAL_LOAD_DECISIONS:-0}\n");
        assertThat(function(load, "load_preflight")).as("refused on any profile but the soak, and on any value but 0 or 1")
                .contains("[ \"$LOAD_SOAK\" = true ] || cannot \"REHEARSAL_LOAD_DECISIONS=1 rides the soak")
                .contains("*) cannot \"REHEARSAL_LOAD_DECISIONS is 0 or 1, not '$LOAD_DECISIONS'\" ;;");
        assertThat(function(load, "load_run")).as("asked in the load's loop")
                .contains("[ \"$LOAD_DECISIONS\" != 1 ] || load_decisions_tick \"$elapsed\" \"$secs\"");
        assertThat(function(load, "load_judge")).as("judged after the soak's own judge")
                .contains("[ \"$LOAD_SOAK\" != true ] || load_soak_judge\n    # P16-TSK-012: the decisions' readings over the soak (E65).\n"
                        + "    [ \"$LOAD_DECISIONS\" != 1 ] || load_decisions_judge");
    }

    @Test
    @DisplayName("seven report runs at 10% to 70% of the load, each in the load's own loop, before the soak's plans window opens")
    void sevenRunsBeforeThePlansWindowInTheLoadsOwnLoop() {
        String tick = function(load, "load_decisions_tick");
        assertThat(tick).as("the next of seven, at its tenth of the load")
                .contains("[ \"$next\" -le 7 ] && [ \"$1\" -ge $(($2 * next * 10 / 100)) ] || return 0")
                .as("in the load's own loop, never beside another client").contains("( load_report_sample \"$next\" )").doesNotContain("&\n");
        assertThat(function(load, "load_soak_tick")).as("the soak's plans window still opens after the last run")
                .contains("[ \"$1\" -ge $(($2 * 80 / 100)) ]");
    }

    @Test
    @DisplayName("the reader reads report generation's own role's statements in finapp, before and after each run, and the journal's size")
    void theReaderReadsTheReportingRolesStatements() {
        String sql = read(DIR + "reports.sql");
        assertThat(sql).as("the role report generation's pool connects as, in the finapp database")
                .contains("JOIN pg_roles r ON r.oid = s.userid").contains("r.rolname = 'finapp_reporting'").contains("d.datname = 'finapp'")
                .contains("SELECT 'role', count(*) FROM pg_roles WHERE rolname = 'finapp_reporting';");
        assertThat(read("app/src/main/resources/application.yaml")).as("the pool's role is the one the reader filters on")
                .contains("username: ${FINAPP_DB_REPORTING_USER:finapp_reporting}");
        String sample = function(load, "load_report_sample");
        assertThat(sample).as("before, the run, after: the run's cost is a difference")
                .containsSubsequence("pg_admin -f - < \"$LOAD_SRC/reports.sql\" > \"$dir/$1-before.txt\"",
                        "client trial-balance-report > \"$dir/$1-client.txt\"",
                        "pg_admin -f - < \"$LOAD_SRC/reports.sql\" > \"$dir/$1-after.txt\"")
                .contains("(SELECT count(*) FROM ledger.journal_line)")
                .as("the run's own row, on the database's clock").contains("FROM accounting.report_run WHERE id = '$run'::uuid");
    }

    @Test
    @DisplayName("a run that did not complete, a sample never asked, one run read twice or a blind reader fails the run")
    void aRunThatDidNotCompleteOrABlindReaderFailsTheRun() {
        String judge = function(load, "load_decisions_judge");
        assertThat(judge)
                .contains("[ \"$LOAD_REPORTS_TAKEN\" = 7 ] || fail")
                .contains("case $run in '' | -) fail")
                .contains("grep -q '^role|1$' \"$dir/$n-after.txt\" || fail")
                .contains("[ -s \"$dir/$n-run.txt\" ] || fail")
                .contains("[ \"$status\" = PROVISIONAL ] || fail")
                .contains("[ \"${calls:-0}\" -gt 0 ] || fail")
                .contains("sort -u | grep -c .)\" = 8 ] || fail");
    }

    @Test
    @DisplayName("the bound is the code's, read and never copied; the fit is the slowest statement's, the bound being a statement's")
    void theBoundIsTheCodesAndTheFitAStatements() {
        assertThat(function(load, "load_preflight")).as("read from AccountingBeans' default, and refused when an overlay sets it")
                .contains("s/.*finapp\\.accounting\\.report\\.generation-timeout:PT\\([0-9]*\\)M}.*/\\1/p")
                .contains("app/src/main/java/com/finapp/app/accounting/AccountingBeans.java")
                .contains("grep -Eqr 'REPORT_GENERATION_TIMEOUT|report\\.generation-timeout' \"$ROOT/deploy\"");
        assertThat(read("app/src/main/java/com/finapp/app/accounting/AccountingBeans.java")).as("the value the harness reads is where it reads it")
                .containsPattern("\\$\\{finapp\\.accounting\\.report\\.generation-timeout:PT[0-9]+M}");
        String awk = read(DIR + "reports.awk");
        assertThat(awk).as("a run's cost per statement, its two reads told apart by name").contains("FILENAME == ARGV[1]")
                .contains("if (t / c > largest) largest = t / c")
                .as("the fit over the slowest statement, against the journal's lines").contains("x = $3 + 0; y = $9 + 0")
                .contains("(bound_ms - intercept) / slope");
        assertThat(function(load, "load_decisions_judge")).contains("bound_ms=$((LOAD_REPORT_BOUND_MINUTES * 60000))");
    }

    @Test
    @DisplayName("every table soak.sql names is read for its growth per hour and per movement, and the journal must have grown")
    void theGrowthReadingCoversEveryNamedTable() {
        String judge = function(load, "load_decisions_judge");
        assertThat(judge).as("soak.sql's own list, counted, never a copy of it")
                .contains("tables=$(grep -o \"('[a-z_]*', '[a-z_]*')\" \"$LOAD_SRC/soak.sql\" | sort -u | grep -c .)")
                .contains("= \"$tables\" ] || fail")
                .as("the positive control").contains("$1 == \"ledger.journal_line\" && $4 + 0 > 0 {found = 1}");
        assertThat(read(DIR + "growth.awk")).as("growth as last less first, per hour and per money movement")
                .contains("inserted = ins1[t] - ins0[t]; deleted = del1[t] - del0[t]").contains("inserted / moved")
                .contains("(bytes1[t] - bytes0[t]) / moved");
    }
}
