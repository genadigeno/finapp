package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Accounting {@code V012} - a reference gap's way on (`P14-TSK-026`, ADR-0119 decision 2; {@code INV-ACC-11}) - read as
 * text against the code: the set counts its gaps and the rate flags its own; V005's two replaced functions restate every
 * check V005 held and add the flagged rate's window - strictly before the end and OLDER than the bound, within the count -
 * and the commit's exact count; the count frozen; no grant, no other schema, V005's file untouched. The behaviour under a
 * live PostgreSQL is {@code ValuationReferenceGapDatabaseTest}'s (owed, C5).
 */
@DisplayName("accounting V012 holds a reference gap's ranks as the code declares them (P14-TSK-026)")
class ValuationReferenceGapMigrationTest {

    private static final String DIRECTORY = "db/migration/accounting";
    private static final String RESOURCE = DIRECTORY + "/V012__valuation_reference_gap.sql";
    private static final String V005 = DIRECTORY + "/V005__valuation_rate_sets.sql";

    @Test
    @DisplayName("the set counts its gaps, bounded by its rates and reasoned; the rate flags its own; existing rows read 0"
            + " and false")
    void theColumnsAndTheirChecks() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("ADD COLUMN reference_gaps integer NOT NULL DEFAULT 0")
                .contains("ADD COLUMN beyond_bound boolean NOT NULL DEFAULT false")
                .contains("CONSTRAINT valuation_rate_set_reference_gaps_counted CHECK (reference_gaps BETWEEN 0 AND rate_count)")
                .contains("CONSTRAINT valuation_rate_set_reference_gap_reasoned CHECK (reference_gaps = 0 OR capture_reason IS NOT NULL)");
    }

    @Test
    @DisplayName("the rate's trigger restates V005's every check and its window for an unflagged rate, and admits a flagged"
            + " one only strictly before the end, older than the bound, within the set's count")
    void theRateTriggerAddsOnlyTheGap() {
        String replaced = function(RESOURCE, "valuation_rate_is_born_with_its_set");
        String original = function(V005, "valuation_rate_is_born_with_its_set");
        for (String held : List.of(
                "IF TG_OP <> 'INSERT' THEN",
                "IF NOT FOUND OR held_status <> 'PROPOSED' THEN",
                "SELECT count(*) INTO held_rates FROM accounting.valuation_rate r WHERE r.set_id = NEW.set_id;",
                "IF held_rates >= held_count THEN",
                "IF NOT (NEW.observed_at < held_as_of AND NEW.observed_at >= held_as_of - make_interval(hours => held_hours)) THEN")) {
            assertThat(original).as("V005 held it").contains(held);
            assertThat(replaced).as("V012 restates it").contains(held);
        }
        assertThat(replaced).contains("FROM accounting.valuation_rate_set s WHERE s.id = NEW.set_id FOR UPDATE;")
                .contains("IF NOT NEW.beyond_bound THEN")
                .contains("IF NOT (NEW.observed_at < held_as_of AND NEW.observed_at < held_as_of - make_interval(hours => held_hours)) THEN")
                .contains("r.set_id = NEW.set_id AND r.beyond_bound;")
                .contains("IF held_flagged >= held_gaps THEN");
        assertThat(replaced.indexOf("IF NOT NEW.beyond_bound THEN"))
                .as("the unflagged window is judged in its own branch, before the flagged one")
                .isLessThan(replaced.indexOf("held_flagged >= held_gaps"));
    }

    @Test
    @DisplayName("the commit-whole check restates V005's count and adds the flagged count; the count is frozen; no grant, no"
            + " other schema; V005 untouched")
    void theCommitAndTheFreeze() {
        String commit = function(RESOURCE, "valuation_rate_set_commits_whole");
        assertThat(commit).contains("IF held_rates <> NEW.rate_count THEN").contains("IF held_flagged <> NEW.reference_gaps THEN")
                .contains("count(*) FILTER (WHERE r.beyond_bound)");
        assertThat(collapsed(RESOURCE)).contains("CREATE TRIGGER valuation_rate_set_reference_gaps_are_frozen BEFORE UPDATE ON"
                + " accounting.valuation_rate_set FOR EACH ROW EXECUTE FUNCTION accounting.valuation_rate_set_reference_gaps_are_frozen();")
                .contains("IF NEW.reference_gaps IS DISTINCT FROM OLD.reference_gaps THEN");
        String sql = statements(RESOURCE);
        assertThat(sql).doesNotContain("GRANT").doesNotContain("fx.").doesNotContain("ledger.").doesNotContain("DROP ")
                .doesNotContain("CREATE TABLE");
        assertThat(statements(V005)).as("V005's file is not edited: it never names the new columns")
                .doesNotContain("beyond_bound").doesNotContain("reference_gaps");
    }

    @Test
    @DisplayName("every constraint and trigger name V012 adds is its own: once in V012, and in no earlier accounting migration")
    void constraintNamesAreUnique() throws IOException, URISyntaxException {
        Pattern named = Pattern.compile("(?:CONSTRAINT|CREATE TRIGGER)\\s+([a-z_]+)");
        List<String> introduced = new ArrayList<>();
        Matcher own = named.matcher(statements(RESOURCE));
        while (own.find()) {
            introduced.add(own.group(1));
        }
        List<String> earlier = new ArrayList<>();
        for (String resource : migrations()) {
            if (resource.equals(RESOURCE)) {
                continue;
            }
            Matcher matcher = named.matcher(statements(resource));
            while (matcher.find()) {
                earlier.add(matcher.group(1));
            }
        }
        assertThat(introduced).containsExactlyInAnyOrder("valuation_rate_set_reference_gaps_counted",
                "valuation_rate_set_reference_gap_reasoned", "valuation_rate_set_reference_gaps_are_frozen");
        assertThat(earlier).as("the earlier migrations are read").hasSizeGreaterThan(100).doesNotContainAnyElementsOf(introduced);
    }

    // -----------------------------------------------------------------

    /**
     * Accounting's migrations as this branch holds them, named: the module's resources reach the test classpath in a jar, so
     * a directory listing is not available. {@code V011} is `P14-TSK-024`'s, merged beside this one.
     */
    private static List<String> migrations() throws IOException, URISyntaxException {
        return Stream.of("V001__initialise_accounting_schema.sql", "V002__the_gl_chart_and_mapping_as_versioned_data.sql",
                "V003__accounting_periods_open_lock_unlock.sql", "V004__report_runs_immutable_reproducible.sql",
                "V005__valuation_rate_sets.sql", "V006__accounting_breaks.sql", "V007__the_close_run_and_its_checks.sql",
                "V008__four_eyes_close_rejection_and_reopen.sql", "V009__prior_period_adjustments.sql",
                "V010__regulatory_obligations_and_submissions.sql", "V012__valuation_reference_gap.sql")
                .map(name -> DIRECTORY + "/" + name).toList();
    }

    /** One plpgsql function's body, from its CREATE to its closing dollar quote. */
    private static String function(String resource, String name) {
        String text = migration(resource);
        int start = text.indexOf("CREATE OR REPLACE FUNCTION accounting." + name + "()");
        assertThat(start).as("function %s in %s", name, resource).isNotNegative();
        return text.substring(start, text.indexOf("$$;", start)).replaceAll("[ \\t]+", " ").replace("( ", "(")
                .replace(" )", ")");
    }

    private static String migration(String resource) {
        try (InputStream in = ValuationReferenceGapMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("%s on the test classpath", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String statements(String resource) {
        return migration(resource).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .map(line -> line.contains(" -- ") ? line.substring(0, line.indexOf(" -- ")) : line)
                .collect(Collectors.joining("\n"));
    }

    private static String collapsed(String resource) {
        return statements(resource).replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }
}
