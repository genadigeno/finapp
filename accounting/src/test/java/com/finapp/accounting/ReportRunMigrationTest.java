package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
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
 * {@code accounting V004}'s text held to the code (`P14-TSK-008`; {@code AccountingPeriodMigrationTest}'s idiom -
 * hermetic, so it runs on every build while the live ranks are {@code ReportRunDatabaseTest}'s, written and owed under
 * C5): the plan's key verbatim, the value lists the enums', the shapes the code's bounds, the hashes recomputed from the
 * stored text, <strong>the freeze its own trigger</strong> (the entry's probe target - the hermetic half beside
 * {@code ReportRunDatabaseTest#contentIsFrozen}), the machine's edges, the period's return to {@code OPEN} only with its
 * FINAL runs superseded, the rate set with no foreign key, nothing seeded, no DELETE, no other schema, every constraint
 * name held once.
 */
@DisplayName("accounting V004 holds the report run's ranks as the code declares them (P14-TSK-008)")
class ReportRunMigrationTest {

    private static final String RESOURCE = "db/migration/accounting/V004__report_runs_immutable_reproducible.sql";
    private static final List<String> EARLIER = List.of(
            "db/migration/accounting/V001__initialise_accounting_schema.sql",
            "db/migration/accounting/V002__the_gl_chart_and_mapping_as_versioned_data.sql",
            "db/migration/accounting/V003__accounting_periods_open_lock_unlock.sql");

    @Test
    @DisplayName("the plan's key verbatim - UNIQUE (definition, definition_version, period_id, revision, parameters_hash)"
            + " - and the store's insert-or-read names it")
    void theRunKeyIsThePlans() {
        assertThat(collapsed(RESOURCE)).contains("CONSTRAINT report_run_key UNIQUE (definition, definition_version,"
                + " period_id, revision, parameters_hash)");
        assertThat(JdbcReportRunStore.INSERT_SQL).endsWith("ON CONFLICT ON CONSTRAINT report_run_key DO NOTHING");
    }

    @Test
    @DisplayName("the status, basis and failure lists are RunStatus, RunBasis and RunFailure; the code and version shapes"
            + " ReportDefinitions'; the content bound ReportGeneration's")
    void theValueListsAreTheEnums() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CHECK (status IN (" + list(RunStatus.values()) + "))")
                .contains("CHECK (basis IN (" + list(RunBasis.values()) + "))")
                .contains("failure_code IN (" + list(RunFailure.values()) + ")");
        assertThat(sql).contains("CHECK (definition ~ '^" + ReportDefinitions.CODE.pattern() + "$')")
                .contains("definition_version BETWEEN 1 AND " + ReportDefinitions.MAX_VERSION);
        assertThat(sql).contains("IF octet_length(NEW.content) > " + ReportGeneration.MAX_CONTENT_BYTES + " THEN");
    }

    @Test
    @DisplayName("the hashes are the database's: content_sha256 recomputed at the completion edge - the one edge that sets"
            + " it, never by a CHECK every later UPDATE would re-run over 8 MB - and parameters_hash by CHECK, each text a"
            + " JSON object; the content whole with its hash and completion")
    void theHashesAreRecomputed() {
        String sql = collapsed(RESOURCE);
        String completion = sql.substring(sql.indexOf("IF OLD.status = 'GENERATING' AND NEW.status IN ('FINAL', 'PROVISIONAL') THEN"),
                sql.indexOf("IF OLD.status = 'GENERATING' AND NEW.status = 'FAILED' THEN"));
        assertThat(completion)
                .contains("IF NEW.content IS NOT NULL AND NEW.content_sha256 IS DISTINCT FROM encode(sha256(convert_to("
                        + "NEW.content, 'UTF8')), 'hex') THEN RAISE EXCEPTION 'report_run_content_hashed:")
                .contains("IF NEW.content IS NOT NULL AND jsonb_typeof(NEW.content::jsonb) <> 'object' THEN RAISE"
                        + " EXCEPTION 'report_run_content_is_a_json_object:")
                .contains("USING ERRCODE = 'check_violation'");
        // The bound first, so an oversized text is refused before it is parsed.
        assertThat(completion.indexOf("octet_length(NEW.content)")).isLessThan(completion.indexOf("NEW.content::jsonb"));
        // No CHECK re-reads the content: a supersession's UPDATE re-runs every CHECK.
        String checks = sql.substring(sql.indexOf("CREATE TABLE accounting.report_run"), sql.indexOf("COMMENT ON TABLE"));
        assertThat(checks).doesNotContain("content::jsonb").doesNotContain("convert_to(content")
                .doesNotContain("octet_length(content)")
                .contains("CONSTRAINT report_run_content_hash_shaped CHECK (content_sha256 IS NULL OR content_sha256 ~"
                        + " '^[0-9a-f]{64}$')");
        assertThat(sql).contains("parameters_hash = encode(sha256(convert_to(parameters, 'UTF8')), 'hex')")
                .contains("jsonb_typeof(parameters::jsonb) = 'object'");
        assertThat(sql).contains("(status IN ('FINAL', 'PROVISIONAL', 'SUPERSEDED')) = (content IS NOT NULL)"
                + " AND (content IS NULL) = (content_sha256 IS NULL) AND (content IS NULL) = (completed_at IS NULL)");
        assertThat(sql).contains("(basis = 'FINAL') = (period_locked_at IS NOT NULL)");
    }

    @Test
    @DisplayName("THE FREEZE is its own trigger, before every update naming the content, its hash or its completion, firing"
            + " first by name: once a run has content, they never change - the entry's probe target, the hermetic half")
    void theContentIsFrozenByItsOwnTrigger() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CREATE TRIGGER report_run_content_is_frozen BEFORE UPDATE OF content, content_sha256,"
                + " completed_at ON accounting.report_run FOR EACH ROW EXECUTE FUNCTION"
                + " accounting.report_run_content_is_frozen();");
        assertThat(sql).contains("IF OLD.content IS NOT NULL AND (NEW.content IS DISTINCT FROM OLD.content OR"
                + " NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 OR NEW.completed_at IS DISTINCT FROM"
                + " OLD.completed_at) THEN RAISE EXCEPTION 'report_run_content_is_frozen:");
        // Triggers of one event fire in name order: the freeze's refusal is its own, before the machine's.
        assertThat("report_run_content_is_frozen").isLessThan("report_run_permits_only_machine_edges");
    }

    @Test
    @DisplayName("the machine: born GENERATING at attempt 1 coherent with its period, the reclaim only past the lease, the"
            + " completion as its basis under the claimed lock, FINAL -> SUPERSEDED, never deleted or truncated")
    void theMachine() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.report_run FOR EACH ROW EXECUTE"
                + " FUNCTION accounting.report_run_permits_only_machine_edges();");
        assertThat(sql).contains("IF NEW.status <> 'GENERATING' OR NEW.attempt <> 1");
        assertThat(sql).contains("IF NEW.basis = 'FINAL' AND (period_status = 'OPEN' OR NEW.period_locked_at IS DISTINCT"
                + " FROM period_locked OR NEW.mapping_version_id IS DISTINCT FROM period_pin) THEN");
        assertThat(sql).contains("IF NEW.basis = 'PROVISIONAL' AND period_status <> 'OPEN' THEN");
        assertThat(sql).contains("IF NEW.attempt <> OLD.attempt + 1 OR OLD.lease_until > clock_timestamp() OR"
                + " NEW.lease_until <= clock_timestamp()");
        assertThat(sql).contains("IF period_status = 'OPEN' OR period_locked IS DISTINCT FROM NEW.period_locked_at THEN");
        assertThat(sql).contains("IF OLD.status = 'FINAL' AND NEW.status = 'SUPERSEDED' THEN");
        assertThat(sql).contains("RAISE EXCEPTION 'a report run is never deleted");
        assertThat(sql).contains("BEFORE TRUNCATE ON accounting.report_run");
        // The question and its inputs frozen from birth.
        for (String column : List.of("definition", "definition_version", "period_id", "revision", "parameters",
                "parameters_hash", "period_revision", "basis", "period_locked_at", "mapping_version_id",
                "valuation_rate_set_id", "cut_at", "requested_by", "claimed_at", "correlation_id")) {
            assertThat(sql).as("%s frozen", column).contains("NEW." + column + " IS DISTINCT FROM OLD." + column);
        }
    }

    @Test
    @DisplayName("a period returns to OPEN only with its FINAL runs superseded - the unlock's and the reopen's backstop on"
            + " accounting_period")
    void aPeriodReopensOnlyWithItsRunsSuperseded() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CREATE TRIGGER accounting_period_reopens_with_its_runs_superseded BEFORE UPDATE ON"
                + " accounting.accounting_period FOR EACH ROW EXECUTE FUNCTION"
                + " accounting.accounting_period_reopens_with_its_runs_superseded();");
        assertThat(sql).contains("IF OLD.status <> 'OPEN' AND NEW.status = 'OPEN' AND EXISTS (SELECT 1 FROM"
                + " accounting.report_run r WHERE r.period_id = NEW.id AND r.status = 'FINAL') THEN");
    }

    @Test
    @DisplayName("the valuation rate set is a uuid with NO foreign key - P14-TSK-014's table lands after - and is the"
            + " question's own parameter")
    void theRateSetHasNoForeignKey() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("valuation_rate_set_id uuid,");
        assertThat(sql).doesNotContain("REFERENCES accounting.valuation_rate_set");
        assertThat(sql).doesNotContainPattern("FOREIGN KEY \\(valuation_rate_set_id\\)");
        assertThat(sql).contains("valuation_rate_set_id IS NOT DISTINCT FROM (parameters::jsonb ->> 'valuationRateSet')::uuid");
        assertThat(migration(RESOURCE)).contains("uuid NULL with NO FOREIGN KEY");
    }

    @Test
    @DisplayName("nothing is seeded, no DELETE is granted, only the machine's and the output's columns update, no other"
            + " schema is named")
    void noSeedNoDeleteNoOtherSchema() {
        String sql = statements(RESOURCE);
        assertThat(sql).doesNotContain("INSERT INTO").doesNotContain("ALTER DEFAULT").doesNotContain("REVOKE");
        List<String> grants = sql.lines().filter(line -> line.startsWith("GRANT")).toList();
        assertThat(grants).noneMatch(grant -> grant.contains("DELETE") || grant.contains("TRUNCATE"));
        assertThat(grants).containsExactly(
                "GRANT SELECT, INSERT ON accounting.report_run TO finapp_app;",
                "GRANT UPDATE (status, attempt, lease_until, content, content_sha256, completed_at, failure_code,"
                        + " failed_at, superseded_at, superseded_correlation_id)");
        assertThat(sql).doesNotContain("ledger.").doesNotContain("risk.").doesNotContain("platform.")
                .doesNotContain("public.");
    }

    @Test
    @DisplayName("every constraint name is held once across accounting's migrations")
    void constraintNamesAreUnique() {
        List<String> names = new ArrayList<>();
        String all = EARLIER.stream().map(ReportRunMigrationTest::statements).collect(Collectors.joining("\n"))
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
        try (InputStream stream = ReportRunMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
