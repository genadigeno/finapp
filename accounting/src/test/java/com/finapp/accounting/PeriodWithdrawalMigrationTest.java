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
 * {@code accounting V011} as text (`P14-TSK-024`; {@code PeriodCloseMigrationTest}'s shape): <strong>a text pin proves only the text</strong> - the live ranks are {@code PeriodCloseDatabaseTest}'s and {@code
 * PeriodReopenDatabaseTest}'s, written and owed (C5) - but this holds the enums, the four eyes, the arbiters, the replaced functions, the grants and the rows already in the table to what the code relies on, on every build,
 * and walks what can be walked without a database: every column a statement names is one this schema has.
 */
@DisplayName("accounting V011 holds the close's withdrawals as the code relies on them (P14-TSK-024)")
class PeriodWithdrawalMigrationTest {

    private static final String RESOURCE = "db/migration/accounting/V011__the_close_s_withdrawals.sql";
    private static final String V008 = "db/migration/accounting/V008__four_eyes_close_rejection_and_reopen.sql";
    private static final String V007 = "db/migration/accounting/V007__the_close_run_and_its_checks.sql";

    @Test
    @DisplayName("the lists are the enums: the reopen's statuses, an unlock's end, and every act on its one edge, each end on the edge it leaves standing")
    void theValueListsAreTheEnums() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CHECK (status IN (" + list(PeriodReopenStatus.values()) + "))");
        assertThat(sql).contains("CHECK (outcome IN (" + list(PeriodRequestEnd.values()) + "))");
        for (AccountingPeriodStore.Act act : AccountingPeriodStore.Act.values()) {
            assertThat(sql).as("the act %s is on its edge", act).contains("(act = '" + act.name() + "' AND from_status");
        }
        assertThat(sql).contains("(act = 'UNLOCK_REJECTED' AND from_status = 'LOCKED' AND to_status = 'LOCKED')")
                .contains("(act = 'UNLOCK_WITHDRAWN' AND from_status = 'LOCKED' AND to_status = 'LOCKED')")
                .contains("(act = 'REOPEN_REJECTED' AND from_status = 'CLOSED' AND to_status = 'CLOSED')")
                .contains("(act = 'REOPEN_WITHDRAWN' AND from_status = 'CLOSED' AND to_status = 'CLOSED')");
        for (PeriodRequestEnd end : PeriodRequestEnd.values()) {
            assertThat(end.reopenStatus().name()).isEqualTo(end.name());
            assertThat(end.unlockAct().name()).isEqualTo("UNLOCK_" + end.name());
            assertThat(end.reopenAct().name()).isEqualTo("REOPEN_" + end.name());
        }
    }

    @Test
    @DisplayName("four eyes for every writer: a rejection is a second person's, a withdrawal its proposer's own - on the end's row, on the reopen's row and in the history; every other act names no proposer")
    void fourEyesForEveryWriter() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CONSTRAINT period_unlock_end_four_eyes CHECK ( (outcome = 'REJECTED' AND ended_by <> proposed_by) OR (outcome = 'WITHDRAWN' AND ended_by = proposed_by))");
        assertThat(sql).contains("ADD CONSTRAINT period_reopen_end_four_eyes CHECK ( (status <> 'REJECTED' OR ended_by <> proposed_by) AND (status <> 'WITHDRAWN' OR ended_by = proposed_by))");
        assertThat(sql).contains("WHEN act IN ('UNLOCKED', 'CLOSED', 'REOPENED', 'UNLOCK_REJECTED', 'REOPEN_REJECTED') THEN proposed_by IS NOT NULL AND proposed_by <> actor")
                .contains("WHEN act IN ('UNLOCK_WITHDRAWN', 'REOPEN_WITHDRAWN') THEN proposed_by IS NOT NULL AND proposed_by = actor").contains("ELSE proposed_by IS NULL");
        assertThat(sql).as("each end is reasoned in the history").contains("'UNLOCK_REJECTED', 'UNLOCK_WITHDRAWN', 'REOPEN_REJECTED', 'REOPEN_WITHDRAWN') OR reason IS NOT NULL)");
        assertThat(sql).contains("(status IN ('REJECTED', 'WITHDRAWN')) = (ended_by IS NOT NULL)");
        assertThat(sql).as("V008's approval four eyes untouched").doesNotContain("DROP CONSTRAINT period_reopen_four_eyes");
    }

    @Test
    @DisplayName("the arbiters: one pending reopen per period and one approved per revision replace one per revision, which held for every row V008 admitted; one end per unlock request")
    void theArbiters() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("DROP CONSTRAINT period_reopen_one_per_revision,")
                .contains("CREATE UNIQUE INDEX period_reopen_one_pending ON accounting.period_reopen (period_id) WHERE status = 'PROPOSED';")
                .contains("CREATE UNIQUE INDEX period_reopen_one_approved ON accounting.period_reopen (period_id, period_revision) WHERE status = 'APPROVED';")
                .contains("CREATE INDEX period_reopen_by_revision ON accounting.period_reopen (period_id, period_revision);")
                .contains("CONSTRAINT period_unlock_end_once UNIQUE (period_id, period_locked_at, proposed_at)");
        assertThat(collapsed(V008)).as("the constraint dropped is V008's").contains("CONSTRAINT period_reopen_one_per_revision UNIQUE (period_id, period_revision)")
                .contains("CONSTRAINT period_reopen_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED'))");
    }

    @Test
    @DisplayName("the period's edge, replaced whole: every rule of V008's function stands word for word; an unlock's end clears the proposal whole, moves no lock or pin, and only beside its end's row, which names that very proposal")
    void thePeriodsEdgeKeepsWhatItHeld() throws IOException {
        String sql = collapsed(RESOURCE);
        String v008 = function(collapsed(V008), "accounting_period_permits_only_machine_edges");
        Matcher raised = Pattern.compile("RAISE EXCEPTION '((?:[^']|'')+)'").matcher(v008);
        int rules = 0;
        while (raised.find()) {
            rules++;
            assertThat(function(sql, "accounting_period_permits_only_machine_edges")).as("V008's rule stands").contains(raised.group(1));
        }
        assertThat(rules).as("the walk read V008's function").isGreaterThan(15);
        assertThat(sql).contains("IF OLD.unlock_proposed_by IS NOT NULL AND NEW.unlock_proposed_by IS NULL THEN")
                .contains("OR NEW.locked_at IS DISTINCT FROM OLD.locked_at OR NEW.pinned_mapping_version_id IS DISTINCT FROM OLD.pinned_mapping_version_id THEN RAISE EXCEPTION 'a pending unlock is ended by clearing its proposal whole, and nothing else moves (P14-TSK-024)'")
                .contains("SELECT 1 FROM accounting.period_unlock_end u WHERE u.period_id = OLD.id AND u.period_locked_at = OLD.locked_at AND u.proposed_by = OLD.unlock_proposed_by AND u.proposed_at = OLD.unlock_proposed_at");
        assertThat(sql).as("the end's row names the proposal the row holds, held FOR SHARE as the domain holds it FOR UPDATE")
                .contains("FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE; IF NOT FOUND OR held_status <> 'LOCKED' OR held_by IS NULL OR")
                .contains("OR held_at IS DISTINCT FROM NEW.proposed_at OR held_approver IS NOT NULL THEN");
        assertThat(sql).as("an end committed beside the proposal still standing is refused at commit")
                .contains("CREATE CONSTRAINT TRIGGER period_unlock_end_commits_with_its_edge AFTER INSERT ON accounting.period_unlock_end DEFERRABLE INITIALLY DEFERRED");
    }

    @Test
    @DisplayName("the reopen's edge, replaced whole: born undecided, frozen but for its one decision, PROPOSED to APPROVED, REJECTED or WITHDRAWN once, an end only while its period is CLOSED at the proposal's revision and never with an approval")
    void theReopensEdge() throws IOException {
        String reopen = function(collapsed(RESOURCE), "period_reopen_permits_only_machine_edges");
        assertThat(reopen).contains("IF OLD.status = 'PROPOSED' AND NEW.status = 'APPROVED' THEN").contains("IF OLD.status = 'PROPOSED' AND NEW.status IN ('REJECTED', 'WITHDRAWN') THEN")
                .contains("RAISE EXCEPTION 'a reopen is ended while its period is CLOSED at the proposal''s revision (P14-TSK-024)'")
                .contains("RAISE EXCEPTION 'an ended reopen records no approval (P14-TSK-024)'").contains("RAISE EXCEPTION 'an approved reopen records no end (P14-TSK-024)'")
                .contains("OR NEW.ended_by IS NOT NULL OR NEW.ended_by_type IS NOT NULL OR NEW.ended_at IS NOT NULL OR NEW.end_reason IS NOT NULL THEN RAISE EXCEPTION 'a reopen proposal is born PROPOSED and undecided (P14-TSK-012)'")
                .contains("RAISE EXCEPTION 'a reopen proposal moves PROPOSED -> APPROVED | REJECTED | WITHDRAWN, once; % -> % is not an edge (P14-TSK-024)'")
                .contains("NEW.ended_at := clock_timestamp();");
        for (String rule : List.of("a reopen proposal is never deleted (P14-TSK-012, INV-ACC-10)", "a reopen is proposed for a CLOSED period at its present revision (P14-TSK-012, INV-ACC-10)",
                "a reopen is approved while its period is CLOSED at the proposal''s revision (P14-TSK-012, INV-ACC-10)")) {
            assertThat(collapsed(V008)).as("the rule is V008's").contains(rule);
            assertThat(reopen).as("and it stands").contains(rule);
        }
    }

    @Test
    @DisplayName("the grants: the end's row INSERT-only, the reopen's four end columns; no DELETE; no other schema; the end's table never truncated; nothing here moves the cutoff")
    void theGrants() throws IOException {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("GRANT SELECT, INSERT ON accounting.period_unlock_end TO finapp_app;")
                .contains("GRANT UPDATE (ended_by, ended_by_type, ended_at, end_reason) ON accounting.period_reopen TO finapp_app;")
                .contains("CREATE TRIGGER period_unlock_end_is_never_truncated BEFORE TRUNCATE ON accounting.period_unlock_end");
        assertThat(sql.toUpperCase(java.util.Locale.ROOT)).doesNotContain("GRANT DELETE").doesNotContain("GRANT ALL").doesNotContain("TRUNCATE ACCOUNTING").doesNotContain("SECURITY DEFINER");
        assertThat(executable(sql)).doesNotContain("ledger.").doesNotContain("platform.").doesNotContain("posting_cutoff").doesNotContain("reconciliation.").doesNotContain("settlement.");
    }

    @Test
    @DisplayName("every table and function the migration names is schema-qualified; every constraint name it adds is held once")
    void theMigrationIsItsOwn() throws IOException {
        String sql = executable(collapsed(RESOURCE));
        Pattern unqualified = Pattern.compile("(?i)\\b(FROM|JOIN|UPDATE|INTO|ON|REFERENCES|TABLE|FUNCTION) (period_unlock_end|period_reopen|period_event|accounting_period)\\b");
        assertThat(unqualified.matcher(sql).find()).as(unqualified.pattern()).isFalse();
        List<String> constraints = new ArrayList<>();
        Matcher named = Pattern.compile("ADD CONSTRAINT ([a-z_]+) |CONSTRAINT ([a-z_]+) (?:PRIMARY|FOREIGN|UNIQUE|CHECK)").matcher(sql);
        while (named.find()) {
            constraints.add(named.group(1) != null ? named.group(1) : named.group(2));
        }
        assertThat(constraints).isNotEmpty().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the statements name only columns the schema has: period_unlock_end, period_reopen as V008 made it and V011 widened it, close_check's concluded_at - by a hand-kept walk read from the DDL, not from the statement")
    void theStatementsNameColumnsThatExist() throws IOException {
        String sql = collapsed(RESOURCE);
        List<String> ends = tableColumns(sql, "period_unlock_end");
        assertThat(ends).containsExactly("id", "period_id", "period_revision", "period_locked_at", "proposed_by", "proposed_by_type", "proposed_at", "outcome", "ended_by", "ended_by_type", "ended_at", "end_reason",
                "correlation_id");
        assertThat(columnsOf(JdbcAccountingPeriodStore.INSERT_UNLOCK_END_SQL)).containsExactlyElementsOf(ends);
        List<String> reopen = new ArrayList<>(tableColumns(collapsed(V008), "period_reopen"));
        reopen.addAll(List.of("ended_by", "ended_by_type", "ended_at", "end_reason"));
        for (String added : List.of("ADD COLUMN ended_by text", "ADD COLUMN ended_by_type text", "ADD COLUMN ended_at timestamptz", "ADD COLUMN end_reason text")) {
            assertThat(sql).contains(added);
        }
        for (String column : List.of("status", "ended_by", "ended_by_type", "ended_at", "end_reason", "id")) {
            assertThat(JdbcPeriodCloseStore.END_REOPEN_SQL).contains(column);
            assertThat(reopen).contains(column);
        }
        String selected = JdbcPeriodCloseStore.PENDING_REOPEN_SQL.substring("SELECT ".length(), JdbcPeriodCloseStore.PENDING_REOPEN_SQL.indexOf(" FROM"));
        assertThat(Arrays.stream(selected.split(",")).map(String::strip).toList()).isSubsetOf(reopen);
        assertThat(tableColumns(collapsed(V007), "close_check")).contains("evidence", "concluded_at");
        for (String column : List.of("unlock_proposed_by", "unlock_proposed_by_type", "unlock_proposed_at", "unlock_proposal_reason", "unlock_approved_by", "locked_at", "revision", "status")) {
            assertThat(tableColumns(collapsed("db/migration/accounting/V003__accounting_periods_open_lock_unlock.sql"), "accounting_period")).as(column).contains(column);
        }
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

    /** One function's body as the migration states it, from its {@code CREATE} to its closing {@code $$}. */
    private static String function(String sql, String name) {
        int from = sql.indexOf("CREATE OR REPLACE FUNCTION accounting." + name + "()");
        assertThat(from).as(name).isNotNegative();
        int body = sql.indexOf("$$", from);
        return sql.substring(from, sql.indexOf("$$", body + 2));
    }

    /** The statements alone: comments and {@code COMMENT ON} strings can name what they like. */
    private static String executable(String collapsed) {
        return collapsed.replaceAll("COMMENT ON [^;]*;", "");
    }

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }

    private static String collapsed(String resource) throws IOException {
        try (InputStream stream = PeriodWithdrawalMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return text.replaceAll("(?m)^\\s*--.*$", "").replaceAll("(?m)\\s--.*$", "").replaceAll("\\s+", " ").strip();
        }
    }
}
