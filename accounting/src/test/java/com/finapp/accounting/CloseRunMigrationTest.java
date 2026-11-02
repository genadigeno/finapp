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
 * {@code accounting V007}'s text held to the code (`P14-TSK-011`; {@code AccountingBreakMigrationTest}'s idiom -
 * hermetic, so it runs on every build while the live ranks are {@code CloseRunDatabaseTest}'s, owed under C5): one run
 * per lock instance; the plan's attempt key; one {@code PENDING} per check; the code and status lists the enums';
 * {@code DISCLOSED} only where the check admits it; the machine's triggers; nothing seeded, no {@code DELETE}, only the
 * moving columns updatable, no other schema; every constraint name held once.
 */
@DisplayName("accounting V007 holds the close run's ranks as the code declares them (P14-TSK-011)")
class CloseRunMigrationTest {

    private static final String RESOURCE = "db/migration/accounting/V007__the_close_run_and_its_checks.sql";
    private static final List<String> EARLIER = List.of(
            "db/migration/accounting/V001__initialise_accounting_schema.sql",
            "db/migration/accounting/V002__the_gl_chart_and_mapping_as_versioned_data.sql",
            "db/migration/accounting/V003__accounting_periods_open_lock_unlock.sql",
            "db/migration/accounting/V004__report_runs_immutable_reproducible.sql",
            "db/migration/accounting/V005__valuation_rate_sets.sql",
            "db/migration/accounting/V006__accounting_breaks.sql");

    @Test
    @DisplayName("one run per lock instance; the plan's attempt key; at most one PENDING per check; the claim's index")
    void theKeys() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CONSTRAINT close_run_one_per_lock UNIQUE (period_id, period_locked_at)");
        assertThat(sql).contains("CONSTRAINT close_check_attempt_once UNIQUE (close_run_id, check_code, attempt)");
        assertThat(sql).contains("CREATE UNIQUE INDEX close_check_one_pending ON accounting.close_check (close_run_id,"
                + " check_code) WHERE status = 'PENDING'");
        assertThat(sql).contains("CREATE INDEX close_check_claimable ON accounting.close_check (lease_until, id) WHERE"
                + " status = 'PENDING'");
    }

    @Test
    @DisplayName("the value lists are the enums': every check, every status; DISCLOSED only where CloseCheckCode.disclosable admits it")
    void theValueListsAreTheEnums() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CHECK (check_code IN (" + list(CloseCheckCode.values()) + "))");
        assertThat(sql).contains("CHECK (status IN (" + list(CloseCheckStatus.values()) + "))");
        CloseCheckCode[] disclosable = Arrays.stream(CloseCheckCode.values()).filter(CloseCheckCode::disclosable)
                .toArray(CloseCheckCode[]::new);
        assertThat(sql).contains("CONSTRAINT close_check_disclosed_only_where_it_may CHECK (status <> 'DISCLOSED' OR"
                + " check_code IN (" + list(disclosable) + "))");
    }

    @Test
    @DisplayName("an outcome is whole, recorded exactly when concluded, under a claim; failures exactly when FAILED; the bounds CheckVerdict's")
    void theOutcomeIsCoherent() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("(status = 'PENDING') = (concluded_at IS NULL)")
                .contains("(status = 'PENDING') = (judged_by IS NULL)")
                .contains("(status = 'PENDING') = (evidence IS NULL)")
                .contains("(status = 'FAILED') = (failures IS NOT NULL)")
                .contains("(status = 'PENDING' OR holder IS NOT NULL)");
        assertThat(sql).contains("char_length(evidence) BETWEEN 2 AND " + CheckVerdict.MAX_EVIDENCE)
                .contains("char_length(failures) BETWEEN 1 AND " + CheckVerdict.MAX_FAILURES);
        assertThat(sql).contains("judged_by ~ '^[a-z][a-z-]*(:[A-Za-z0-9_.-]+){1,2}$'");
        assertThat(CheckVerdict.JUDGED_BY.pattern()).isEqualTo("[a-z][a-z-]*(:[A-Za-z0-9_.-]+){1,2}");
        assertThat(sql).contains("(requested_by_type = 'SYSTEM') = (request_reason IS NULL)");
    }

    @Test
    @DisplayName("the triggers: a run born under its period's present lock; an attempt born PENDING with no gap; PENDING moving only the"
            + " lease's columns; concluded by its holder and final; nothing deleted, nothing truncated")
    void theTriggers() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.close_run FOR EACH ROW EXECUTE FUNCTION"
                + " accounting.close_run_permits_only_machine_edges();");
        assertThat(sql).contains("FROM accounting.accounting_period WHERE id = NEW.period_id FOR SHARE;")
                .contains("held_status <> 'LOCKED' OR held_locked_at IS DISTINCT FROM NEW.period_locked_at")
                .contains("a close run is born under its period''s present lock")
                .contains("a close run records its checks concluded once");
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.close_check FOR EACH ROW EXECUTE FUNCTION"
                + " accounting.close_check_permits_only_machine_edges();");
        assertThat(sql).contains("a close check attempt is born PENDING and unclaimed")
                .contains("a close check attempt follows the last, with no gap")
                .contains("a concluded close check attempt is final")
                .contains("a close check attempt''s request is frozen from its birth")
                .contains("a close check attempt is reclaimed one claim at a time")
                .contains("a PENDING close check attempt records no outcome")
                .contains("NEW.holder IS NOT NULL AND NEW.holder IS DISTINCT FROM OLD.holder AND OLD.lease_until > clock_timestamp()")
                .contains("a close check attempt is claimed only once its lease has passed")
                .contains("(NEW.reclaims = OLD.reclaims + 1) <> (OLD.holder IS NOT NULL AND NEW.holder IS NOT NULL AND NEW.holder <> OLD.holder)")
                .contains("a lapsed holder is replaced with reclaims + 1, and nothing else moves reclaims")
                .contains("a close check attempt is concluded by the claim that holds it")
                .contains("a close run is never deleted")
                .contains("a close check attempt is never deleted");
        for (String table : List.of("close_run", "close_check")) {
            assertThat(sql).as("%s is never truncated", table).contains("BEFORE TRUNCATE ON accounting." + table);
        }
    }

    @Test
    @DisplayName("nothing is seeded, no DELETE is granted, only the moving columns update, and no other schema is named")
    void noSeedNoDeleteNoOtherSchema() {
        String sql = statements(RESOURCE);
        assertThat(sql).doesNotContain("INSERT INTO").doesNotContain("ALTER DEFAULT").doesNotContain("REVOKE");
        List<String> grants = sql.lines().filter(line -> line.startsWith("GRANT")).toList();
        assertThat(grants).noneMatch(grant -> grant.contains("DELETE") || grant.contains("TRUNCATE"));
        assertThat(grants).containsExactly(
                "GRANT SELECT, INSERT ON accounting.close_run TO finapp_app;",
                "GRANT UPDATE (checks_concluded_at) ON accounting.close_run TO finapp_app;",
                "GRANT SELECT, INSERT ON accounting.close_check TO finapp_app;",
                "GRANT UPDATE (status, holder, reclaims, lease_until, claimed_at, judged_by, failures, evidence, concluded_at)"
                        + " ON accounting.close_check TO finapp_app;");
        assertThat(sql).doesNotContain("ledger.").doesNotContain("reconciliation.").doesNotContain("platform.")
                .doesNotContain("public.");
    }

    @Test
    @DisplayName("every constraint name is held once across accounting's migrations")
    void constraintNamesAreUnique() {
        List<String> names = new ArrayList<>();
        String all = EARLIER.stream().map(CloseRunMigrationTest::statements).collect(Collectors.joining("\n"))
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
        try (InputStream stream = CloseRunMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
