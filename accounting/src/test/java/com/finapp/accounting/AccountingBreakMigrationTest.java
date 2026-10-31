package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
 * {@code accounting V006}'s text held to the code (`P14-TSK-010`; {@code AccountingPeriodMigrationTest}'s idiom -
 * hermetic, so it runs on every build while the live ranks are {@code AccountingBreakDatabaseTest}'s, written and owed
 * under C5): born once per {@code (period, check, subject)} - the row-10 arbiter the store's insert names; the check,
 * type, status and act lists the enums'; each type on its check; the register-version shape the findings'; four eyes on
 * the row and on the case file; the edge trigger; the case file append-only; nothing seeded, no DELETE, only the act
 * columns updatable, no other schema; every constraint name held once.
 */
@DisplayName("accounting V006 holds the break's ranks as the code declares them (P14-TSK-010)")
class AccountingBreakMigrationTest {

    private static final String RESOURCE = "db/migration/accounting/V006__accounting_breaks.sql";
    private static final List<String> EARLIER = List.of(
            "db/migration/accounting/V001__initialise_accounting_schema.sql",
            "db/migration/accounting/V002__the_gl_chart_and_mapping_as_versioned_data.sql",
            "db/migration/accounting/V003__accounting_periods_open_lock_unlock.sql");

    @Test
    @DisplayName("born once per (period, check, subject) - the constraint the store's insert-if-absent names")
    void bornOncePerPeriodCheckAndSubject() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CONSTRAINT accounting_break_born_once UNIQUE (period_start, check_code, subject_ref)");
        assertThat(sql).contains("CONSTRAINT accounting_break_period_is_a_month CHECK (EXTRACT(DAY FROM period_start) = 1)");
        assertThat(sql).contains("char_length(subject_ref) BETWEEN 1 AND " + BreakFinding.MAX_SUBJECT)
                .contains("char_length(evidence) BETWEEN 1 AND " + BreakFinding.MAX_EVIDENCE);
    }

    @Test
    @DisplayName("the value lists are the enums': the three raising checks, every type on its check, BreakStatus, the"
            + " acts each on their edge; the register-version shape BreakFinding's")
    void theValueListsAreTheEnums() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CHECK (check_code IN (" + BreakFinding.CHECKS.stream().map(c -> "'" + c.name() + "'")
                .collect(Collectors.joining(", ")) + "))");
        for (CloseCheckCode check : BreakFinding.CHECKS) {
            String types = Arrays.stream(BreakType.values()).filter(type -> BreakFinding.checkOf(type) == check)
                    .map(type -> "'" + type.name() + "'").collect(Collectors.joining(", "));
            assertThat(sql).as("%s's types", check)
                    .contains("(check_code = '" + check.name() + "' AND break_type IN (" + types + "))");
        }
        assertThat(sql).contains("CHECK (status IN (" + list(BreakStatus.values()) + "))");
        for (AccountingBreakStore.Act act : AccountingBreakStore.Act.values()) {
            assertThat(sql).as("the act %s is on its edge", act).contains("(act = '" + act.name() + "' AND from_status");
        }
        assertThat(sql).contains("judged_by ~ '^(posting-rules|gl-mapping|source-proofs):v[1-9][0-9]{0,8}$'");
        assertThat(BreakFinding.JUDGED_BY.pattern()).isEqualTo("(posting-rules|gl-mapping|source-proofs):v[1-9][0-9]{0,8}");
        assertThat(BreakStatus.OPEN.canMoveTo(BreakStatus.ACKNOWLEDGED)).isTrue();
        assertThat(BreakStatus.ACKNOWLEDGED.canMoveTo(BreakStatus.RESOLVED)).isTrue();
    }

    @Test
    @DisplayName("four eyes and persons, for every writer - on the row and on the case file; reasons bounded")
    void fourEyesAndPersons() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CONSTRAINT accounting_break_acknowledgement_four_eyes CHECK (ack_approved_by IS NULL OR"
                + " ack_approved_by <> ack_proposed_by)");
        assertThat(sql).contains("(proposed_by IS NULL OR proposed_by <> actor)");
        assertThat(sql).contains("raised_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE')")
                .contains("(ack_proposed_by_type IS NULL OR ack_proposed_by_type IN ('CUSTOMER', 'EMPLOYEE'))")
                .contains("(ack_approved_by_type IS NULL OR ack_approved_by_type IN ('CUSTOMER', 'EMPLOYEE'))")
                .contains("(resolved_by_type IS NULL OR resolved_by_type IN ('CUSTOMER', 'EMPLOYEE'))")
                .contains("(act = 'RAISED' OR actor_type IN ('CUSTOMER', 'EMPLOYEE'))");
        assertThat(sql).contains("char_length(disclosure) BETWEEN 1 AND " + AccountingReasons.BOUND)
                .contains("char_length(resolution_reason) BETWEEN 1 AND " + AccountingReasons.BOUND)
                .contains("char_length(reason) BETWEEN 1 AND " + AccountingReasons.BOUND);
        assertThat(sql).contains("CONSTRAINT break_event_resolution_names_its_entry CHECK ((act = 'RESOLVED') ="
                + " (resolving_entry_id IS NOT NULL))");
    }

    @Test
    @DisplayName("the edge trigger: born OPEN with nothing acted, the finding frozen, RESOLVED final, the four edges only;"
            + " the case file append-only; nothing truncated")
    void theTriggers() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.accounting_break FOR EACH ROW EXECUTE"
                + " FUNCTION accounting.accounting_break_permits_only_machine_edges();");
        assertThat(sql).contains("an accounting break is born OPEN with nothing acted")
                .contains("an accounting break is never deleted")
                .contains("finding is frozen from its birth")
                .contains("a RESOLVED accounting break is final")
                .contains("IF OLD.status IN ('OPEN', 'ACKNOWLEDGED') AND NEW.status = 'RESOLVED' THEN");
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.break_event FOR EACH ROW EXECUTE"
                + " FUNCTION accounting.break_event_is_append_only();");
        for (String table : List.of("accounting_break", "break_event")) {
            assertThat(sql).as("%s is never truncated", table).contains("BEFORE TRUNCATE ON accounting." + table);
        }
    }

    @Test
    @DisplayName("nothing is seeded, no DELETE is granted, only the act columns update, and no other schema is named -"
            + " no column holds an amount")
    void noSeedNoDeleteNoOtherSchema() {
        String sql = statements(RESOURCE);
        assertThat(sql).doesNotContain("INSERT INTO").doesNotContain("ALTER DEFAULT").doesNotContain("REVOKE");
        List<String> grants = sql.lines().filter(line -> line.startsWith("GRANT")).toList();
        assertThat(grants).noneMatch(grant -> grant.contains("DELETE") || grant.contains("TRUNCATE"));
        assertThat(grants).containsExactly(
                "GRANT SELECT, INSERT ON accounting.accounting_break TO finapp_app;",
                "GRANT UPDATE (status, ack_proposed_by, ack_proposed_by_type, ack_proposed_at, disclosure, ack_approved_by,"
                        + " ack_approved_by_type, ack_approved_at, ack_approval_reason, resolved_by, resolved_by_type,"
                        + " resolved_at, resolving_entry_id, resolution_reason)",
                "GRANT SELECT, INSERT ON accounting.break_event TO finapp_app;");
        assertThat(sql).doesNotContain("ledger.").doesNotContain("risk.").doesNotContain("platform.")
                .doesNotContain("public.");
        assertThat(sql).doesNotContain("amount").doesNotContain("minor").doesNotContain("balance");
    }

    @Test
    @DisplayName("every constraint name is held once across accounting's migrations")
    void constraintNamesAreUnique() {
        List<String> names = new ArrayList<>();
        String all = EARLIER.stream().map(AccountingBreakMigrationTest::statements).collect(Collectors.joining("\n"))
                + statements(RESOURCE);
        Matcher matcher = Pattern.compile("CONSTRAINT ([a-z_]+)").matcher(all);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        assertThat(names).isNotEmpty().doesNotHaveDuplicates();
    }

    // -----------------------------------------------------------------

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(v -> "'" + v.name() + "'").collect(Collectors.joining(", "));
    }

    /** The executable lines, comments removed. */
    private static String statements(String resource) {
        return migration(resource).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .map(line -> line.contains(" -- ") ? line.substring(0, line.indexOf(" -- ")) : line)
                .collect(Collectors.joining("\n"));
    }

    /** The executable text with every run of whitespace one space - multi-line constraints read as one. */
    private static String collapsed(String resource) {
        return statements(resource).replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    private static String migration(String resource) {
        try (InputStream stream = AccountingBreakMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
