package com.finapp.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.RepositoryPaths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every column in the {@code platform} schema has a classification decision, and every decision
 * describes a column that exists.
 *
 * <p><strong>Why this is a build failure and not a review note.</strong> {@code P0-TSK-033}'s
 * acceptance criterion is that the scheme is "referenced by later data-model tasks", and a register
 * nothing checks is referenced exactly once - on the day it is written. Phase 3 adds ledger tables
 * with dozens of columns; without this, they would be unclassified and nothing would say so, which
 * is the difference between the criterion being satisfied on paper and in fact.
 *
 * <p>The failure direction that matters is a column with <em>no</em> decision. A column cannot be
 * reclassified once it holds data - by then the handling it was given for its whole life was
 * already wrong, and a log aggregator, an event stream or a backup may already have it. That is the
 * same argument ADR-0010 makes for actor attribution: the decision has to precede the first row.
 *
 * <p>The other direction is checked too, because a register naming columns that no longer exist is
 * a register nobody trusts, and an entry that has quietly stopped applying to anything is
 * indistinguishable from one that still does.
 *
 * <p>This is the same mechanism as {@code AuditableActionRegistryTest} and
 * {@code CorrelationSinkCoverageTest}: an expectation derived from the system cannot rot, and one
 * maintained by hand always does.
 */
@Tag("database")
class ColumnClassificationTest {

    private static final String DOCUMENT = "docs/architecture/DATA_CLASSIFICATION.md";

    /**
     * Flyway's own bookkeeping table, excluded by name.
     *
     * <p>It is not this platform's schema design and holds no business data. Excluded by name
     * rather than by a pattern so that a future Flyway version adding a column does not fail this
     * test, and so the exclusion is visible rather than implied.
     */
    private static final String NOT_OURS = "flyway_schema_history";

    /** The levels the document defines. A row naming anything else is a typo, not a decision. */
    private static final Set<String> LEVELS =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED-FINANCIAL", "RESTRICTED-PII");

    /** A register row: table, column, level, all backticked except the note. */
    private static final Pattern ROW =
            Pattern.compile(
                    // Digits are permitted in a table or column name, and their absence was a real
                    // defect rather than a cosmetic one: `[a-z_]+` cannot express `address_line_2`
                    // or `iso_4217_code`, so a Phase 3 column would have been impossible to
                    // classify - the row would sit in the register unparsed and the failure would
                    // read "this column has no entry" while the entry was right there. Found by
                    // planting such a column; it fails safe but diagnoses the wrong thing.
                    "^\\|\\s*`([a-z0-9_]+)`\\s*\\|\\s*`([a-z0-9_]+)`\\s*\\|\\s*`([A-Z-]+)`\\s*\\|",
                    // MULTILINE, or `^` anchors to the start of the whole document and matches
                    // nothing at all. Caught immediately by theRegisterIsActuallyRead, which is
                    // exactly what a vacuity guard is for: without it the comparison above would
                    // have passed over an empty register and reported a scheme classifying nothing.
                    Pattern.MULTILINE);

    @Test
    @DisplayName("every column in the schema has a classification, and every classification a column")
    void theRegisterAndTheSchemaAgree() throws SQLException {
        Set<String> classified = registered();
        Set<String> actual = columnsInTheSchema();

        assertThat(classified)
                .as(
                        """
                        %s section 4 must classify exactly the columns the platform schema has.

                        A column with no entry is a column whose handling nobody decided, and it \
                        cannot be decided later: by the time it holds data, the handling it was \
                        given for its whole life was already wrong.

                        An entry with no column is a register describing something that does not \
                        exist, which makes the rest of it harder to trust.""",
                        DOCUMENT)
                .isEqualTo(actual);
    }

    @Test
    @DisplayName("the register uses only levels the document defines")
    void everyLevelIsOneOfTheFive() {
        Set<String> used = new TreeSet<>();
        Matcher matcher = ROW.matcher(document());
        while (matcher.find()) {
            used.add(matcher.group(3));
        }

        assertThat(used)
                .as("a level outside the five is a typo, and a typo silently classifies nothing")
                .isSubsetOf(LEVELS);
    }

    @Test
    @DisplayName("the scan is not vacuous")
    void theRegisterIsActuallyRead() {
        // Without this, a renamed heading or a changed table format would empty both sets and the
        // comparison above would pass over nothing at all - the failure mode P0-TST-007 found in a
        // privilege check and the P0-TSK-031 review found in a configuration scan. Named columns
        // rather than a count, because a count is satisfied by parsing the wrong table.
        assertThat(registered())
                .hasSizeGreaterThan(40)
                .contains(
                        "audit_record.actor_id",
                        "idempotency_record.response_body",
                        "outbox_event.payload");
    }

    @Test
    @DisplayName("every level defined by the document is used, or it is not a scheme")
    void theRestrictedLevelsAreReachable() {
        // A level nobody applies is a level nobody has thought about. PUBLIC is the deliberate
        // exception and the document says so: nothing in the platform is public.
        Set<String> used = new TreeSet<>();
        Matcher matcher = ROW.matcher(document());
        while (matcher.find()) {
            used.add(matcher.group(3));
        }

        assertThat(used)
                .as("a restricted level that classifies nothing is decoration")
                .contains("INTERNAL", "CONFIDENTIAL", "RESTRICTED-FINANCIAL", "RESTRICTED-PII");
    }

    // -----------------------------------------------------------------

    private static Set<String> registered() {
        Set<String> rows = new LinkedHashSet<>();
        Matcher matcher = ROW.matcher(document());
        while (matcher.find()) {
            rows.add(matcher.group(1) + "." + matcher.group(2));
        }
        return new TreeSet<>(rows);
    }

    /**
     * Every column in every schema this repository owns.
     *
     * <p><strong>Derived, not hardcoded to {@code platform}.</strong> It was hardcoded until
     * {@code P1-TSK-003} added the {@code party} and {@code identity} schemas, at which point
     * ADR-0022's guarantee — *a migration adding a column the register does not classify fails the
     * build* — silently became true for one schema out of three. The next task creates the
     * platform's first {@code RESTRICTED-PII} columns in quantity, in exactly the two schemas the
     * guard could not see.
     *
     * <p>The set comes from the database rather than from a list here, so a fourth schema-owning
     * module is covered without anyone remembering — the same reasoning as
     * {@code ProductionModules} and the unqualified {@code flywayMigrate} in CI. Schemas PostgreSQL
     * ships are excluded by name; everything else in this database is ours by construction, because
     * the migrator role created it.
     */
    private static Set<String> columnsInTheSchema() throws SQLException {
        Set<String> columns = new TreeSet<>();
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT table_schema, table_name, column_name"
                                        + " FROM information_schema.columns"
                                        + " WHERE table_schema NOT IN ('pg_catalog', 'information_schema')"
                                        + " AND table_name <> '"
                                        + NOT_OURS
                                        + "'")) {
            while (rows.next()) {
                columns.add(rows.getString(2) + "." + rows.getString(3));
            }
        }
        return columns;
    }

    /**
     * The register keys rows on {@code table.column} with no schema, so two schemas must not both
     * define a table of the same name.
     *
     * <p>Not a hypothetical: {@code audit_record} is a name any module might reasonably reuse, and a
     * collision would silently merge two tables' columns into one set — after which the register
     * could classify a column that exists in a different schema from the one the reader assumes,
     * and the comparison above would still pass. Failing here says which name collided; the
     * alternative is a register that is quietly about the wrong table.
     *
     * <p>When a collision does arrive, the fix is to key the register on {@code schema.table.column}
     * rather than to rename a table. This assertion is what forces that decision to be made rather
     * than discovered.
     */
    @Test
    @DisplayName("no two schemas define a table of the same name, so table.column is unambiguous")
    void tableNamesAreUniqueAcrossSchemas() throws SQLException {
        Map<String, List<String>> schemasByTable = new TreeMap<>();
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT DISTINCT table_schema, table_name FROM information_schema.tables"
                                        + " WHERE table_schema NOT IN ('pg_catalog', 'information_schema')"
                                        + " AND table_name <> '"
                                        + NOT_OURS
                                        + "'")) {
            while (rows.next()) {
                schemasByTable
                        .computeIfAbsent(rows.getString(2), name -> new ArrayList<>())
                        .add(rows.getString(1));
            }
        }

        assertThat(schemasByTable)
                .as("the register must have seen some tables, or this asserts nothing")
                .isNotEmpty();

        Map<String, List<String>> collisions = new TreeMap<>();
        schemasByTable.forEach(
                (table, schemas) -> {
                    if (schemas.size() > 1) {
                        collisions.put(table, schemas);
                    }
                });

        assertThat(collisions)
                .as(
                        "%s keys rows on table.column with no schema. Two schemas defining the same "
                                + "table name would merge into one set, and the register would be "
                                + "about a table the reader did not mean. Key the register on "
                                + "schema.table.column instead of renaming a table.",
                        DOCUMENT)
                .isEmpty();
    }

    /**
     * Walks upward, so the test does not assume a working directory.
     *
     * <p>This was a private copy of {@code RepositoryPaths}, which lived in {@code app}'s tests
     * and so was unreachable from here. {@code P0-TSK-036} moved it into the shared fixtures,
     * where both modules can use one definition — the same duplication, and the same fix, as the
     * thirteen private connection helpers.
     */
    private static String document() {
        return RepositoryPaths.read(DOCUMENT);
    }
}
