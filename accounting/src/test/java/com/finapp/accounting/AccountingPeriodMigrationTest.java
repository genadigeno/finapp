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
 * {@code accounting V003}'s text held to the code (`P14-TSK-005`; {@code AccountingMappingMigrationTest}'s idiom -
 * hermetic, so it runs on every build while the live ranks are {@code AccountingPeriodDatabaseTest}'s, written and owed
 * under C5): the status and act lists are the enums', one period per month and at most one in close, the four-eyes and
 * persons {@code CHECK}s, the edge trigger's rules (contiguous, in order, the pin the version in force, approval only of
 * a pending proposal), the history append-only, the mapping activation refused into a pinned period, nothing seeded, no
 * DELETE, no other schema, every constraint name held once, and the pin read's shared key pinned.
 */
@DisplayName("accounting V003 holds the period's ranks as the code declares them (P14-TSK-005)")
class AccountingPeriodMigrationTest {

    private static final String RESOURCE = "db/migration/accounting/V003__accounting_periods_open_lock_unlock.sql";
    private static final List<String> EARLIER = List.of(
            "db/migration/accounting/V001__initialise_accounting_schema.sql",
            "db/migration/accounting/V002__the_gl_chart_and_mapping_as_versioned_data.sql");

    @Test
    @DisplayName("the status list is PeriodStatus, the act list AccountingPeriodStore.Act, each act on its one edge")
    void theValueListsAreTheEnums() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CHECK (status IN (" + list(PeriodStatus.values()) + "))");
        for (AccountingPeriodStore.Act act : AccountingPeriodStore.Act.values()) {
            assertThat(sql).as("the act %s is on its edge", act).contains("(act = '" + act.name() + "' AND from_status");
        }
        assertThat(PeriodStatus.OPEN.canMoveTo(PeriodStatus.LOCKED)).isTrue();
        assertThat(PeriodStatus.LOCKED.fourEyesTo(PeriodStatus.OPEN)).as("the unlock is four-eyes").isTrue();
    }

    @Test
    @DisplayName("one period per month, at most one in close, four eyes and persons - for every writer")
    void oneAMonthOneInCloseFourEyes() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CONSTRAINT accounting_period_one_per_month UNIQUE (period_start)");
        assertThat(sql).contains("CREATE UNIQUE INDEX accounting_period_one_in_close ON accounting.accounting_period"
                + " ((status IN ('LOCKED', 'CLOSE_PROPOSED'))) WHERE status IN ('LOCKED', 'CLOSE_PROPOSED');");
        assertThat(sql).contains("CONSTRAINT accounting_period_unlock_four_eyes CHECK (unlock_approved_by IS NULL OR"
                + " unlock_approved_by <> unlock_proposed_by)");
        assertThat(sql).contains("(proposed_by IS NULL OR proposed_by <> actor)");
        assertThat(sql).contains("(locked_by_type IS NULL OR locked_by_type IN ('CUSTOMER', 'EMPLOYEE'))")
                .contains("(unlock_approved_by_type IS NULL OR unlock_approved_by_type IN ('CUSTOMER', 'EMPLOYEE'))")
                .contains("(act = 'OPENED' OR actor_type IN ('CUSTOMER', 'EMPLOYEE'))");
        assertThat(sql).contains("char_length(unlock_proposal_reason) BETWEEN 1 AND " + AccountingReasons.BOUND)
                .contains("char_length(reason) BETWEEN 1 AND " + AccountingReasons.BOUND);
        assertThat(sql).contains("EXTRACT(DAY FROM period_start) = 1 AND period_end = (period_start + interval '1 month')::date - 1");
    }

    @Test
    @DisplayName("the edge trigger: born OPEN and contiguous, identity frozen, in order, the pin the version in force,"
            + " the unlock only by a pending proposal's approval; the history append-only; nothing truncated")
    void theTriggers() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.accounting_period FOR EACH ROW EXECUTE"
                + " FUNCTION accounting.accounting_period_permits_only_machine_edges();");
        assertThat(sql).contains("NEW.period_start > (latest_start + interval '1 month')::date OR NEW.period_start <"
                + " first_start");
        assertThat(sql).contains("IF FOUND AND previous_status <> 'CLOSED' THEN");
        assertThat(sql).contains("AND (v.effective_to_period IS NULL OR v.effective_to_period > OLD.period_start);")
                .contains("IF NEW.pinned_mapping_version_id IS DISTINCT FROM in_force THEN");
        assertThat(sql).contains("IF OLD.unlock_proposed_by IS NULL OR NEW.unlock_approved_by IS NULL");
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.period_event FOR EACH ROW EXECUTE"
                + " FUNCTION accounting.period_event_is_append_only();");
        for (String table : List.of("accounting_period", "period_event")) {
            assertThat(sql).as("%s is never truncated", table).contains("BEFORE TRUNCATE ON accounting." + table);
        }
    }

    @Test
    @DisplayName("the mapping activation is refused into a pinned period: V002's edge function replaced whole, its rules"
            + " kept, the period rule added")
    void theMappingEdgeFunctionReReadsThePeriods() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CREATE OR REPLACE FUNCTION accounting.gl_mapping_version_permits_only_machine_edges()");
        assertThat(sql).contains("v.effective_from_period > NEW.effective_from_period")
                .contains("WHERE p.period_start >= NEW.effective_from_period AND p.status <> 'OPEN'");
        // Every rule V002's function held survives the replacement.
        String earlier = collapsed(EARLIER.get(1));
        for (String rule : List.of("no version is activated by insert", "identity is frozen", "is not an edge",
                "a decision records no end", "is retroactive (P14-TSK-003", "a retirement records only its end")) {
            assertThat(earlier).contains(rule);
            assertThat(sql).as("V003 keeps: %s", rule).contains(rule);
        }
    }

    @Test
    @DisplayName("nothing is seeded, no DELETE is granted, only the act columns update, and no other schema is named")
    void noSeedNoDeleteNoOtherSchema() {
        String sql = statements(RESOURCE);
        assertThat(sql).doesNotContain("INSERT INTO").doesNotContain("ALTER DEFAULT").doesNotContain("REVOKE");
        List<String> grants = sql.lines().filter(line -> line.startsWith("GRANT")).toList();
        assertThat(grants).noneMatch(grant -> grant.contains("DELETE") || grant.contains("TRUNCATE"));
        assertThat(grants).containsExactly(
                "GRANT SELECT, INSERT ON accounting.accounting_period TO finapp_app;",
                "GRANT UPDATE (status, locked_by, locked_by_type, locked_at, pinned_mapping_version_id, unlock_proposed_by,"
                        + " unlock_proposed_by_type, unlock_proposed_at, unlock_proposal_reason, unlock_approved_by,"
                        + " unlock_approved_by_type, unlock_approved_at, unlock_approval_reason)",
                "GRANT SELECT, INSERT ON accounting.period_event TO finapp_app;");
        assertThat(sql).doesNotContain("ledger.").doesNotContain("risk.").doesNotContain("platform.")
                .doesNotContain("public.");
    }

    @Test
    @DisplayName("every constraint name is held once across accounting's migrations")
    void constraintNamesAreUnique() {
        List<String> names = new ArrayList<>();
        String all = EARLIER.stream().map(AccountingPeriodMigrationTest::statements).collect(Collectors.joining("\n"))
                + statements(RESOURCE);
        Matcher matcher = Pattern.compile("CONSTRAINT ([a-z_]+)").matcher(all);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        assertThat(names).isNotEmpty().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the pin read's key is namespace 14 'mapping' SHARED - the activation's key, the other mode")
    void thePinReadsUnderTheMappingKeyShared() {
        assertThat(JdbcGlMappingStore.SHARED_LOCK_SQL)
                .isEqualTo("SELECT pg_advisory_xact_lock_shared(14, hashtext('mapping'))");
        assertThat(JdbcGlMappingStore.LOCK_SQL).isEqualTo("SELECT pg_advisory_xact_lock(14, hashtext('mapping'))");
        assertThat(migration(RESOURCE)).contains("namespace 14 on hashtext('mapping') SHARED");
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
        try (InputStream stream = AccountingPeriodMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
