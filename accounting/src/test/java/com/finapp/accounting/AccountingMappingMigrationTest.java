package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code accounting V002}'s text held to the code (`P14-TSK-003`; the migration-test idiom - {@code RiskPolicyMigrationTest}'s
 * statement reading - hermetic, so it runs on every build while the live ranks are {@code GlMappingDatabaseTest}'s,
 * written and owed under C5): the value lists are the enums', the section-admits-classification CHECK is generated from
 * {@link StatementSection#admitted()}, the four-eyes and persons CHECKs and the one-ACTIVE partial unique are present,
 * the triggers that hold the content born once and the retirement beside its successor are present and deferred where
 * they must be, nothing is seeded, no DELETE is granted and the content and history are INSERT-only, no other schema is
 * named, every constraint name is held once, and advisory namespace 14 key {@code mapping} is the administration's,
 * pinned.
 */
@DisplayName("accounting V002 holds the GL mapping's ranks as the code declares them (P14-TSK-003)")
class AccountingMappingMigrationTest {

    private static final String RESOURCE = "db/migration/accounting/V002__the_gl_chart_and_mapping_as_versioned_data.sql";
    private static final String FLOOR = "db/migration/accounting/V001__initialise_accounting_schema.sql";

    @Test
    @DisplayName("the status, classification and section lists are exactly the enums' members")
    void theValueListsAreTheEnums() {
        String sql = statements(RESOURCE);
        assertThat(sql).contains("CHECK (status IN (" + GlMappingVersionStatus.sqlValueList() + "))");
        assertThat(sql).contains("CHECK (classification IN (" + list(GlClassification.values()) + "))");
        assertThat(sql).contains("CHECK (statement_section IN (" + list(StatementSection.values()) + "))");
    }

    @Test
    @DisplayName("a section admits exactly StatementSection.admitted() - the CHECK generated from the enum")
    void theSectionRuleIsTheEnums() {
        String rule = Arrays.stream(StatementSection.values())
                .map(section -> "(statement_section = '" + section.name() + "' AND classification IN ("
                        + section.admitted().stream().sorted(Comparator.comparing(Enum::ordinal))
                                .map(c -> "'" + c.name() + "'").collect(Collectors.joining(", "))
                        + "))")
                .collect(Collectors.joining(" OR "));
        assertThat(statements(RESOURCE))
                .contains("CONSTRAINT gl_account_section_admits_classification CHECK (" + rule + ")");
        assertThat(statements(RESOURCE)).contains("(classification IN ('ASSET', 'EXPENSE')) = (normal_balance = 'DEBIT')");
        for (GlClassification classification : GlClassification.values()) {
            assertThat(List.of(GlClassification.ASSET, GlClassification.EXPENSE).contains(classification))
                    .as("the SQL's debit-normal pair is GlClassification.debitNormal()")
                    .isEqualTo(classification.debitNormal());
        }
    }

    @Test
    @DisplayName("four eyes, persons only, one ACTIVE: the CHECKs and the partial unique, for every writer")
    void fourEyesPersonsAndOneActive() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("CONSTRAINT gl_mapping_version_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR"
                + " decided_by <> proposed_by)");
        assertThat(sql).contains("proposed_by_type IN ('CUSTOMER', 'EMPLOYEE') AND (decided_by_type IS NULL OR"
                + " decided_by_type IN ('CUSTOMER', 'EMPLOYEE'))");
        assertThat(sql).contains("CONSTRAINT gl_mapping_version_event_person CHECK (actor_type IN ('CUSTOMER', 'EMPLOYEE'))");
        assertThat(sql).contains("CREATE UNIQUE INDEX gl_mapping_one_active ON accounting.gl_mapping_version (status)"
                + " WHERE status = 'ACTIVE';");
        assertThat(sql).contains("CONSTRAINT gl_mapping_rule_pk PRIMARY KEY (version_id, ledger_purpose, owner_kind)");
        assertThat(sql).contains("char_length(proposal_reason) BETWEEN 1 AND " + AccountingReasons.BOUND)
                .contains("char_length(decision_reason) BETWEEN 1 AND " + AccountingReasons.BOUND)
                .contains("char_length(reason) BETWEEN 1 AND " + AccountingReasons.BOUND);
        assertThat(sql).contains("code ~ '^" + GlAccount.CODE.pattern() + "$'")
                .contains("char_length(name) BETWEEN 1 AND " + GlAccount.NAME_BOUND)
                .contains("ledger_purpose ~ '^" + MappingRule.PURPOSE.pattern() + "$'")
                .contains("owner_kind ~ '^" + MappingRule.OWNER_KIND.pattern() + "$'");
    }

    @Test
    @DisplayName("the machine's triggers: born PROPOSED, identity frozen, only the edges, never retroactive, the"
            + " retirement beside its successor and the version whole at COMMIT, the content born once")
    void theTriggers() {
        String sql = collapsed(RESOURCE);
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.gl_mapping_version FOR EACH ROW"
                + " EXECUTE FUNCTION accounting.gl_mapping_version_permits_only_machine_edges();");
        assertThat(sql).contains("v.effective_from_period > NEW.effective_from_period");
        assertThat(sql).contains("CREATE CONSTRAINT TRIGGER gl_mapping_version_retires_only_beside_its_successor AFTER"
                + " UPDATE ON accounting.gl_mapping_version DEFERRABLE INITIALLY DEFERRED");
        assertThat(sql).contains("CREATE CONSTRAINT TRIGGER gl_mapping_version_has_its_content AFTER INSERT ON"
                + " accounting.gl_mapping_version DEFERRABLE INITIALLY DEFERRED");
        for (String table : List.of("gl_account", "gl_mapping_rule")) {
            assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting." + table + " FOR EACH ROW"
                    + " EXECUTE FUNCTION accounting.gl_mapping_content_is_born_once();");
        }
        for (String table : List.of("gl_mapping_version", "gl_account", "gl_mapping_rule", "gl_mapping_version_event")) {
            assertThat(sql).as("%s is never truncated", table).contains("BEFORE TRUNCATE ON accounting." + table);
        }
        assertThat(sql).contains("BEFORE INSERT OR UPDATE OR DELETE ON accounting.gl_mapping_version_event FOR EACH ROW"
                + " EXECUTE FUNCTION accounting.gl_mapping_version_event_is_append_only();");
    }

    @Test
    @DisplayName("nothing is seeded, no DELETE is granted, only the machine row's decision columns update, and no other"
            + " schema is named")
    void noSeedNoDeleteNoOtherSchema() {
        String sql = statements(RESOURCE);
        assertThat(sql).doesNotContain("INSERT INTO");
        assertThat(sql).doesNotContain("ALTER DEFAULT").doesNotContain("REVOKE");
        List<String> grants = sql.lines().filter(line -> line.startsWith("GRANT")).toList();
        assertThat(grants).noneMatch(grant -> grant.contains("DELETE") || grant.contains("TRUNCATE"));
        assertThat(grants).containsExactly(
                "GRANT SELECT, INSERT ON accounting.gl_mapping_version TO finapp_app;",
                "GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, effective_to_period, retired_at)",
                "GRANT SELECT, INSERT ON accounting.gl_account TO finapp_app;",
                "GRANT SELECT, INSERT ON accounting.gl_mapping_rule TO finapp_app;",
                "GRANT SELECT, INSERT ON accounting.gl_mapping_version_event TO finapp_app;");
        // No cross-schema reference (ADR-0029): the purposes are the ledger's names as text, judged at activation.
        assertThat(sql).doesNotContain("ledger.").doesNotContain("risk.").doesNotContain("platform.")
                .doesNotContain("public.");
    }

    @Test
    @DisplayName("every constraint name is held once across accounting's migrations (the Phase 12 V012 lesson)")
    void constraintNamesAreUnique() {
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile("CONSTRAINT ([a-z_]+)").matcher(statements(FLOOR) + statements(RESOURCE));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        assertThat(names).isNotEmpty().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("advisory namespace 14 key 'mapping' is the administration's, pinned - distinct from the cutoff's key")
    void namespaceFourteenKeyMappingIsPinned() {
        assertThat(JdbcGlMappingStore.NAMESPACE).isEqualTo(14);
        assertThat(JdbcGlMappingStore.KEY).isEqualTo("mapping").isNotEqualTo("cutoff");
        assertThat(JdbcGlMappingStore.LOCK_SQL).isEqualTo("SELECT pg_advisory_xact_lock(14, hashtext('mapping'))");
        // The migration's own text names the namespace and key - in its commentary, the record a reviewer reads beside
        // the table; the statements hold no lock, so the pin is the Java constants above.
        assertThat(migration(RESOURCE)).contains("namespace 14 on hashtext('mapping')");
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
        try (InputStream stream = AccountingMappingMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
