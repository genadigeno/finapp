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
 * {@code accounting V005}'s text held to the code (`P14-TSK-014`; {@code AccountingPeriodMigrationTest}'s idiom -
 * hermetic, so it runs on every build while the live ranks are {@code ValuationRateSetDatabaseTest}'s, written and owed
 * under C5): the status list is the enum's, one approved set per period, the four-eyes and persons {@code CHECK}s, the
 * EUR base and the rate's column as fx's, the edge trigger (born PROPOSED for a LOCKED period as of its end, frozen, one
 * edge, approved only LOCKED and whole), the rates born with their set inside its window and never changed, the set
 * committing whole, nothing seeded, no DELETE, no other schema, every constraint name held once.
 */
@DisplayName("accounting V005 holds the valuation rate set's ranks as the code declares them (P14-TSK-014)")
class ValuationRateSetMigrationTest {

    private static final String RESOURCE = "db/migration/accounting/V005__valuation_rate_sets.sql";
    private static final List<String> EARLIER = List.of(
            "db/migration/accounting/V001__initialise_accounting_schema.sql",
            "db/migration/accounting/V002__the_gl_chart_and_mapping_as_versioned_data.sql",
            "db/migration/accounting/V003__accounting_periods_open_lock_unlock.sql",
            "db/migration/accounting/V004__report_runs_immutable_reproducible.sql");

    @Test
    @DisplayName("the status list is ValuationRateSetStatus; one APPROVED set per period by partial unique; four eyes and"
            + " persons for every writer")
    void oneApprovedPerPeriodFourEyes() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CONSTRAINT valuation_rate_set_status_is_known CHECK (status IN ("
                + list(ValuationRateSetStatus.values()) + "))");
        assertThat(sql).contains("CREATE UNIQUE INDEX valuation_rate_set_one_approved_per_period ON"
                + " accounting.valuation_rate_set (period_id) WHERE status = 'APPROVED';");
        assertThat(sql).contains("CONSTRAINT valuation_rate_set_four_eyes CHECK (status <> 'APPROVED' OR decided_by <>"
                + " captured_by)");
        assertThat(sql).contains("captured_by_type IN ('CUSTOMER', 'EMPLOYEE') AND (decided_by_type IS NULL OR"
                + " decided_by_type IN ('CUSTOMER', 'EMPLOYEE'))");
        assertThat(sql).contains("(status = 'PROPOSED') = (decided_by IS NULL)")
                .contains("char_length(capture_reason) BETWEEN 1 AND " + AccountingReasons.BOUND)
                .contains("char_length(decision_reason) BETWEEN 1 AND " + AccountingReasons.BOUND);
        assertThat(sql).contains("CHECK (max_age_hours BETWEEN 1 AND " + ValuationStaleness.MAX_HOURS + ")")
                .contains("CHECK (functional_currency = '" + ValuationRates.FUNCTIONAL.code() + "')");
        assertThat(ValuationRateSetStatus.PROPOSED.fourEyesTo(ValuationRateSetStatus.APPROVED)).isTrue();
    }

    @Test
    @DisplayName("every rate EUR-based, NUMERIC(20,10) as fx stores it, positive, a source's code, its snapshot once per"
            + " set; no cross-schema reference")
    void everyRateIsTheReferencesEurPair() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("rate numeric(20,10) NOT NULL")
                .contains("CONSTRAINT valuation_rate_pk PRIMARY KEY (set_id, quote_currency)")
                .contains("CONSTRAINT valuation_rate_snapshot_once UNIQUE (set_id, snapshot_id)")
                .contains("CHECK (base_currency = 'EUR' AND quote_currency ~ '^[A-Z]{3}$' AND quote_currency <>"
                        + " base_currency)")
                .contains("CHECK (rate > 0)")
                .contains("CHECK (source ~ '" + ValuationRate.SOURCE.pattern() + "')");
        assertThat(sql).as("the snapshot id is a bare uuid - no key into fx").contains("snapshot_id uuid NOT NULL")
                .doesNotContain("REFERENCES fx.");
    }

    @Test
    @DisplayName("the edge trigger: born PROPOSED for a LOCKED period as of its end, the capture frozen, one edge, the"
            + " approval only LOCKED and whole; never deleted or truncated")
    void theSetsEdgeTrigger() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.valuation_rate_set FOR EACH ROW EXECUTE"
                + " FUNCTION accounting.valuation_rate_set_permits_only_machine_edges();");
        assertThat(sql).contains("IF NOT FOUND OR held_status <> 'LOCKED' THEN")
                .contains("IF NEW.as_of IS DISTINCT FROM ((held_end + 1)::timestamp AT TIME ZONE 'UTC') THEN")
                .contains("a valuation rate set''s capture is frozen")
                .contains("IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN")
                .contains("IF held_status <> 'LOCKED' THEN")
                .contains("IF held_rates <> NEW.rate_count THEN")
                .contains("a valuation rate set is never deleted");
        assertThat(ValuationRateSet.asOf(java.time.YearMonth.of(2026, 9)))
                .as("the code's as-of is the trigger's: the day after the period end, midnight UTC")
                .isEqualTo(java.time.Instant.parse("2026-10-01T00:00:00Z"));
        for (String table : List.of("valuation_rate_set", "valuation_rate")) {
            assertThat(sql).as("%s is never truncated", table).contains("BEFORE TRUNCATE ON accounting." + table + " ");
        }
    }

    @Test
    @DisplayName("the rates: born only with their PROPOSED set, up to its count, inside its window; immutable; the set"
            + " commits whole")
    void theRatesAreBornWithTheirSet() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.valuation_rate FOR EACH ROW EXECUTE"
                + " FUNCTION accounting.valuation_rate_is_born_with_its_set();");
        assertThat(sql).contains("FROM accounting.valuation_rate_set s WHERE s.id = NEW.set_id FOR UPDATE;")
                .contains("IF NOT FOUND OR held_status <> 'PROPOSED' THEN")
                .contains("IF held_rates >= held_count THEN")
                .contains("IF NOT (NEW.observed_at < held_as_of AND NEW.observed_at >= held_as_of -"
                        + " make_interval(hours => held_hours)) THEN");
        assertThat(sql).contains("CREATE CONSTRAINT TRIGGER valuation_rate_set_commits_whole AFTER INSERT ON"
                + " accounting.valuation_rate_set DEFERRABLE INITIALLY DEFERRED");
    }

    @Test
    @DisplayName("a report run's pinned set is a key into the sets (P14-TSK-008's bare uuid, keyed here): V004 untouched,"
            + " no grant added, the column's comment restated")
    void theReportRunsPinIsAKey() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("ALTER TABLE accounting.report_run ADD CONSTRAINT report_run_valuation_rate_set_fk FOREIGN"
                + " KEY (valuation_rate_set_id) REFERENCES accounting.valuation_rate_set (id);");
        assertThat(sql).as("restrict on delete and update - a set is never deleted and its id frozen")
                .doesNotContain("ON DELETE").doesNotContain("ON UPDATE");
        assertThat(sql).contains("COMMENT ON COLUMN accounting.report_run.valuation_rate_set_id IS")
                .contains("REFERENCES accounting.valuation_rate_set (id) by report_run_valuation_rate_set_fk");
        // V004 declared the column a bare uuid and still does - the key is this migration's, so V004's text is history.
        String v004 = collapsed(EARLIER.get(3));
        assertThat(v004).contains("valuation_rate_set_id uuid,")
                .doesNotContain("REFERENCES accounting.valuation_rate_set");
        assertThat(sql).as("the key adds no grant on report_run").doesNotContain("ON accounting.report_run TO");
    }

    @Test
    @DisplayName("nothing is seeded, no DELETE is granted, only the decision columns update, and no other schema is named")
    void noSeedNoDeleteNoOtherSchema() {
        String sql = statements(RESOURCE);
        assertThat(sql).doesNotContain("INSERT INTO").doesNotContain("ALTER DEFAULT").doesNotContain("REVOKE");
        List<String> grants = sql.lines().filter(line -> line.startsWith("GRANT")).toList();
        assertThat(grants).noneMatch(grant -> grant.contains("DELETE") || grant.contains("TRUNCATE"));
        assertThat(grants).containsExactly(
                "GRANT SELECT, INSERT ON accounting.valuation_rate_set TO finapp_app;",
                "GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason)",
                "GRANT SELECT, INSERT ON accounting.valuation_rate TO finapp_app;");
        assertThat(sql).doesNotContain("fx.").doesNotContain("ledger.").doesNotContain("platform.")
                .doesNotContain("public.");
    }

    @Test
    @DisplayName("every constraint name is held once across accounting's migrations")
    void constraintNamesAreUnique() {
        List<String> names = new ArrayList<>();
        String all = EARLIER.stream().map(ValuationRateSetMigrationTest::statements).collect(Collectors.joining("\n"))
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
        try (InputStream stream = ValuationRateSetMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
