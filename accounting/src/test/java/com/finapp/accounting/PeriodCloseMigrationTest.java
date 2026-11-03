package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code accounting V008} as text (`P14-TSK-012`; {@code AccountingPeriodMigrationTest}'s shape): <strong>a text pin proves only the text</strong> - the live ranks are {@code PeriodCloseDatabaseTest}'s and {@code
 * PeriodReopenDatabaseTest}'s, written and owed (C5) - but this holds the enums, the four-eyes {@code CHECK}s, the edges, the grants and the rows already in the table to what the code relies on, on every build, and walks what can
 * be walked without a database: every column a statement names, and every column a trigger names, is one this schema has.
 */
@DisplayName("accounting V008 holds the close, the reopen and the generation as the code relies on them (P14-TSK-012)")
class PeriodCloseMigrationTest {

    private static final String RESOURCE = "db/migration/accounting/V008__four_eyes_close_rejection_and_reopen.sql";

    @Test
    @DisplayName("the status lists are the enums; the nine checks are CloseCheckCode's; each act is on its one edge")
    void theValueListsAreTheEnums() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CHECK (status IN (" + list(PeriodCloseStatus.values()) + "))");
        assertThat(sql).contains("CHECK (status IN (" + list(PeriodReopenStatus.values()) + "))");
        assertThat(sql).contains("CHECK (check_code IN (" + list(CloseCheckCode.values()) + "))");
        for (AccountingPeriodStore.Act act : AccountingPeriodStore.Act.values()) {
            assertThat(sql).as("the act %s is on its edge", act).contains("(act = '" + act.name() + "' AND from_status");
        }
        assertThat(sql).contains("(act = 'CLOSED' AND from_status = 'CLOSE_PROPOSED' AND to_status = 'CLOSED')")
                .contains("(act = 'CLOSE_REJECTED' AND from_status = 'CLOSE_PROPOSED' AND to_status = 'LOCKED')")
                .contains("(act = 'REOPENED' AND from_status = 'CLOSED' AND to_status = 'OPEN')")
                .contains("(act = 'REOPEN_PROPOSED' AND from_status = 'CLOSED' AND to_status = 'CLOSED')");
        assertThat(sql).contains("<> 9").contains("= 9");
        assertThat(CloseCheckCode.values()).as("the trigger's literal").hasSize(9);
    }

    @Test
    @DisplayName("four eyes for every writer: on the proposal row, on the reopen row and in the history, on both acts; a rejection may be the proposer's")
    void fourEyesForEveryWriter() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CONSTRAINT period_close_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)");
        assertThat(sql).contains("CONSTRAINT period_reopen_four_eyes CHECK (approved_by IS NULL OR approved_by <> proposed_by)");
        assertThat(sql).contains("CONSTRAINT period_event_four_eyes CHECK ( (act IN ('UNLOCKED', 'CLOSED', 'REOPENED')) = (proposed_by IS NOT NULL)"
                + " AND (proposed_by IS NULL OR proposed_by <> actor))");
        assertThat(sql).contains("AND c.decided_by <> c.proposed_by").contains("AND r.approved_by <> r.proposed_by");
    }

    @Test
    @DisplayName("the arbiters: one pending per period, one approved per revision, one reopen per revision, a check once per proposal")
    void theArbiters() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CREATE UNIQUE INDEX period_close_one_pending ON accounting.period_close (period_id) WHERE status = 'PROPOSED';")
                .contains("CREATE UNIQUE INDEX period_close_one_approved ON accounting.period_close (period_id, period_revision) WHERE status = 'APPROVED';")
                .contains("CONSTRAINT period_reopen_one_per_revision UNIQUE (period_id, period_revision)")
                .contains("CONSTRAINT period_close_check_pk PRIMARY KEY (close_id, check_code)");
    }

    @Test
    @DisplayName("the key's migration holds for rows already in the table: generation 1 by the default, the same constraint name over the wider key, the birth's ON CONFLICT still naming it; the trigger lets a racer through to the unique")
    void theGenerationsMigration() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("ALTER TABLE accounting.accounting_break ADD COLUMN generation integer NOT NULL DEFAULT 1;")
                .contains("DROP CONSTRAINT accounting_break_born_once, ")
                .contains("ADD CONSTRAINT accounting_break_born_once UNIQUE (period_start, check_code, subject_ref, generation)")
                .contains("ADD CONSTRAINT accounting_break_generation_positive CHECK (generation >= 1)");
        assertThat(JdbcAccountingBreakStore.INSERT_SQL).contains("ON CONFLICT ON CONSTRAINT accounting_break_born_once DO NOTHING");
        assertThat(sql).contains("earlier.generation = NEW.generation - 1 AND earlier.status IN ('ACKNOWLEDGED', 'RESOLVED') AND earlier.evidence <> NEW.evidence")
                .contains("OR NEW.generation IS DISTINCT FROM OLD.generation");
    }

    @Test
    @DisplayName("V006's edge rules stand in the replaced function, word for word; V003's too - the machine as it was, with the new edges added and the revision moved only at a reopen")
    void theReplacedFunctionsKeepWhatTheyHeld() throws IOException {
        String sql = collapsed(RESOURCE);
        String v006 = collapsed("db/migration/accounting/V006__accounting_breaks.sql");
        for (String rule : List.of("an OPEN accounting break records one acknowledgement proposal and nothing else (P14-TSK-010)", "a RESOLVED accounting break is final (P14-TSK-010)",
                "an acknowledgement proposal is frozen once made (P14-TSK-010)", "an acknowledgement is frozen once approved (P14-TSK-010)",
                "a break is acknowledged only by the approval of its pending proposal (P14-TSK-010, INV-AUD-04)",
                "a break is resolved only by reference to its correcting entry, and nothing else moves (P14-TSK-010)")) {
            assertThat(v006).as("the rule is V006's").contains(rule);
            assertThat(sql).as("and it stands").contains(rule);
        }
        String v003 = collapsed("db/migration/accounting/V003__accounting_periods_open_lock_unlock.sql");
        for (String rule : List.of("an accounting period is born OPEN at revision 1 with no act recorded (P14-TSK-005)", "accounting periods are contiguous calendar months: % is not the month after % (P14-TSK-005)",
                "accounting periods lock in order: the period before % is %, not CLOSED (P14-TSK-005)", "a locked period pins the GL mapping version in force for its month (P14-TSK-005, INV-ACC-07)",
                "a lock starts the period''s lock afresh: no unlock is carried into it (P14-TSK-005)", "a LOCKED period records one unlock proposal and nothing else (P14-TSK-005)",
                "a period unlocks only by the approval of its pending unlock proposal (P14-TSK-005)", "an accounting period is never deleted (P14-TSK-005, INV-ACC-03)")) {
            assertThat(v003).as("the rule is V003's").contains(rule);
            assertThat(sql).as("and it stands").contains(rule);
        }
        assertThat(sql).contains("an accounting period''s revision moves only at a reopen (P14-TSK-012, INV-ACC-10)").contains("NEW.revision IS DISTINCT FROM OLD.revision + 1");
    }

    @Test
    @DisplayName("the reopen's edge: the next period's status judged for every writer, the lock and pin released; the close's: the lock and pin frozen, no unlock pending")
    void theEdges() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("p.period_start = (OLD.period_start + interval '1 month')::date").contains("IF FOUND AND next_status <> 'OPEN' THEN");
        assertThat(sql).contains("IF (OLD.status = 'LOCKED' AND NEW.status = 'CLOSE_PROPOSED') OR (OLD.status = 'CLOSE_PROPOSED' AND NEW.status IN ('CLOSED', 'LOCKED')) THEN")
                .contains("OR NEW.locked_at IS DISTINCT FROM OLD.locked_at OR NEW.pinned_mapping_version_id IS DISTINCT FROM OLD.pinned_mapping_version_id OR OLD.unlock_proposed_by IS NOT NULL");
        assertThat(sql).contains("EXISTS (SELECT 1 FROM accounting.period_close_check f JOIN accounting.close_check later ON later.close_run_id = NEW.close_run_id")
                .contains("SELECT 1 FROM accounting.accounting_break b WHERE b.period_start = held_start AND b.status = 'OPEN'");
    }

    @Test
    @DisplayName("the grants: the moving columns only, V003's column grant widened by the revision; INSERT-only for the record; no DELETE; no other schema; each table never truncated")
    void theGrants() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("GRANT UPDATE (revision) ON accounting.accounting_period TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON accounting.period_close TO finapp_app;")
                .contains("GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason) ON accounting.period_close TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON accounting.period_close_check TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON accounting.period_reopen TO finapp_app;")
                .contains("GRANT UPDATE (status, approved_by, approved_by_type, approved_at, approval_reason) ON accounting.period_reopen TO finapp_app;");
        assertThat(sql.toUpperCase(java.util.Locale.ROOT)).doesNotContain("GRANT DELETE").doesNotContain("GRANT ALL").doesNotContain("TRUNCATE ACCOUNTING").doesNotContain("SECURITY DEFINER");
        assertThat(executable(sql)).doesNotContain("ledger.").doesNotContain("platform.").doesNotContain("reconciliation.").doesNotContain("settlement.");
        for (String table : List.of("period_close", "period_close_check", "period_reopen")) {
            assertThat(sql).contains("CREATE TRIGGER " + table + "_is_never_truncated BEFORE TRUNCATE ON accounting." + table);
        }
    }

    @Test
    @DisplayName("every table and function the migration names is schema-qualified; every constraint name is held once; no applied migration was edited to get here")
    void theMigrationIsItsOwn() throws IOException {
        String sql = executable(collapsed(RESOURCE));
        for (Pattern unqualified : List.of(Pattern.compile("(?i)\\b(FROM|JOIN|UPDATE|INTO|ON|REFERENCES|TABLE|FUNCTION) (period_close|period_reopen|close_check|close_run|accounting_period|accounting_break|valuation_rate_set|gl_mapping_version)\\b"))) {
            assertThat(unqualified.matcher(sql).find()).as(unqualified.pattern()).isFalse();
        }
        List<String> constraints = new ArrayList<>();
        Matcher named = Pattern.compile("CONSTRAINT ([a-z_]+) (?:PRIMARY|FOREIGN|UNIQUE|CHECK)").matcher(sql);
        while (named.find()) {
            constraints.add(named.group(1));
        }
        assertThat(constraints).isNotEmpty().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the store's statements name only columns the schema has: period_close, period_close_check, period_reopen, accounting_period, by a hand-kept walk read from the DDL, not from the statement")
    void theStatementsNameColumnsThatExist() throws IOException {
        String sql = collapsed(RESOURCE);
        for (String column : List.of("id", "period_id", "period_revision", "period_locked_at", "close_run_id", "pinned_mapping_version_id", "valuation_rate_set_id", "breaks_acknowledged", "breaks_resolved", "status",
                "proposed_by", "proposed_by_type", "proposed_at", "proposal_reason", "decided_by", "decided_by_type", "decided_at", "decision_reason", "correlation_id")) {
            assertThat(tableColumns(sql, "period_close")).as("period_close." + column).contains(column);
        }
        for (String column : List.of("close_id", "check_code", "close_check_id", "attempt", "status", "judged_by", "evidence", "disclosure_reason")) {
            assertThat(tableColumns(sql, "period_close_check")).as("period_close_check." + column).contains(column);
        }
        for (String column : List.of("id", "period_id", "period_revision", "status", "proposed_by", "proposed_by_type", "proposed_at", "proposal_reason", "approved_by", "approved_by_type", "approved_at",
                "approval_reason", "correlation_id")) {
            assertThat(tableColumns(sql, "period_reopen")).as("period_reopen." + column).contains(column);
        }
        assertThat(columnsOf(JdbcPeriodCloseStore.INSERT_CLOSE_SQL)).isSubsetOf(tableColumns(sql, "period_close"));
        assertThat(columnsOf(JdbcPeriodCloseStore.INSERT_CHECK_SQL)).isSubsetOf(tableColumns(sql, "period_close_check"));
        assertThat(columnsOf(JdbcPeriodCloseStore.INSERT_REOPEN_SQL)).isSubsetOf(tableColumns(sql, "period_reopen"));
    }

    // ------------------------------------------------------------------ plumbing

    private static List<String> columnsOf(String insert) {
        String inside = insert.substring(insert.indexOf('(') + 1, insert.indexOf(')'));
        return Arrays.stream(inside.split(",")).map(String::strip).toList();
    }

    private static List<String> tableColumns(String sql, String table) {
        String opened = "CREATE TABLE accounting." + table + " (";
        int from = sql.indexOf(opened) + opened.length();
        String body = sql.substring(from, sql.indexOf("CONSTRAINT " + table + "_pk", from));
        return Arrays.stream(body.split(",")).map(String::strip).filter(column -> !column.isEmpty()).map(column -> column.split(" ")[0]).toList();
    }

    /** The statements alone: comments and {@code COMMENT ON} strings can name what they like. */
    private static String executable(String collapsed) {
        return collapsed.replaceAll("COMMENT ON [^;]*;", "");
    }

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }

    private static String collapsed(String resource) throws IOException {
        try (InputStream stream = PeriodCloseMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return text.replaceAll("(?m)^\\s*--.*$", "").replaceAll("(?m)\\s--.*$", "").replaceAll("\\s+", " ").strip();
        }
    }
}
