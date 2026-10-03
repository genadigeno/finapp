package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `V017` — the Phase 8 -> 9 transition's corrections at the database rank (REC-3, REC-8): one
 * LIVE suspense item per external item and one {@code RECON_PARK} item per park, where `V004`'s
 * once-ever uniques jammed a reopened item's next park; and the key index's one edge, a closed
 * remittance's {@code REMITTANCE_REF} released by its own batch's approved repudiation, refused
 * for every other writer and subject.
 *
 * <p>The admitted paths are proven where they are driven: app's {@code BatchRepudiationDatabaseTest}
 * case 15 (a reopened bank item parks again; the released reference taken by the genuine
 * remittance) and {@code AtomicityByFailureInjectionDatabaseTest} (a park contained per item).
 * Every probe here is rolled back.
 */
@Tag("database")
@DisplayName("reconciliation V017: a repudiation leaves its items and keys recoverable (the"
        + " Phase 8 -> 9 transition)")
class ReconciliationV017MigrationTest {

    private static final String V017 =
            "db/migration/reconciliation/"
                    + "V017__a_repudiation_leaves_its_items_and_keys_recoverable.sql";

    private static final UUID SOURCE = GateFixtures.IDS.next();
    private static final UUID RULE_SET = GateFixtures.IDS.next();
    private static GateFixtures.Seeded keyed;

    @BeforeAll
    static void seed() {
        GateFixtures.seedRuleSet(SOURCE, RULE_SET);
        keyed = GateFixtures.openExpectation(
                SOURCE, RULE_SET, "V017-" + UUID.randomUUID(), 17_00,
                ExpectationDirection.INBOUND, GateFixtures.FAR_FUTURE);
    }

    @Test
    @DisplayName("the text: V004's two once-ever uniques dropped for the live, per-park and"
            + " other-origin partial uniques; the key unique partial on unreleased keys; the"
            + " release edge refused but for a closed remittance's own repudiation, APPROVED at"
            + " commit by a deferred constraint trigger; one column-narrowed grant")
    void theTextStatesTheRules() {
        String sql = migration().replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("DROP CONSTRAINT suspense_item_external_item_once, DROP CONSTRAINT"
                        + " suspense_item_origin_once;")
                .contains("CREATE UNIQUE INDEX suspense_item_external_item_live ON"
                        + " reconciliation.suspense_item (external_item_id) WHERE status <>"
                        + " 'RELEASED';")
                .contains("CREATE UNIQUE INDEX suspense_item_origin_once ON"
                        + " reconciliation.suspense_item (origin_ref) WHERE origin <>"
                        + " 'RECON_PARK';")
                .contains("CREATE UNIQUE INDEX suspense_item_recon_park_once ON"
                        + " reconciliation.suspense_item (external_item_id, park_id) WHERE origin"
                        + " = 'RECON_PARK';")
                .contains("CREATE UNIQUE INDEX expectation_key_once ON"
                        + " reconciliation.expectation_key (source_id, key_kind, key_value) WHERE"
                        + " released_by_resolution_id IS NULL;")
                .contains("AND e.kind = 'REMITTANCE' AND e.status = 'RESOLVED_BY_ADJUSTMENT' AND"
                        + " r.kind = 'REPUDIATE_BATCH' AND r.settlement_batch_id::text ="
                        + " e.operation_ref")
                .contains("CREATE CONSTRAINT TRIGGER expectation_key_release_is_approved AFTER"
                        + " UPDATE OF released_by_resolution_id ON reconciliation.expectation_key"
                        + " DEFERRABLE INITIALLY DEFERRED")
                .contains("GRANT UPDATE (released_by_resolution_id) ON"
                        + " reconciliation.expectation_key TO finapp_app;")
                .doesNotContain("DELETE FROM")
                .doesNotContain("UPDATE reconciliation.suspense_item");
    }

    @Test
    @DisplayName("the key's release is refused for the application and the owner alike when it"
            + " names no repudiation of the keyed expectation's batch - a capture's key, a"
            + " random resolution - and a released key's facts never move")
    void aKeyIsReleasedOnlyByItsRemittancesRepudiation() throws SQLException {
        for (boolean asOwner : new boolean[] {false, true}) {
            try (Connection writer = asOwner ? migrator() : application()) {
                try {
                    SQLException release =
                            refused(writer, "UPDATE reconciliation.expectation_key SET"
                                    + " released_by_resolution_id = ? WHERE expectation_id = ?",
                                    UUID.randomUUID(), keyed.id());
                    assertThat(release.getMessage())
                            .as("REC-8: only the REPUDIATE_BATCH of a closed remittance's own"
                                    + " batch releases its key (%s)",
                                    asOwner ? "owner" : "application")
                            .contains("expectation_key_release_is_a_repudiation");
                    assertThat(count(writer, "SELECT count(*) FROM reconciliation.expectation_key"
                                    + " WHERE expectation_id = ? AND released_by_resolution_id"
                                    + " IS NULL", keyed.id()))
                            .as("the key stays bound")
                            .isEqualTo(1);
                } finally {
                    writer.rollback();
                }
            }
        }
    }

    @Test
    @DisplayName("the indexes stand with their predicates: a released suspense item no bar to its"
            + " item's next live one, a park's item once per park, every other origin once, the"
            + " key once among the bound")
    void theIndexesStandWithTheirPredicates() throws SQLException {
        try (Connection reader = application()) {
            try {
                assertThat(text(reader, "SELECT indexdef FROM pg_indexes WHERE schemaname ="
                                + " 'reconciliation' AND indexname = ?",
                                "suspense_item_external_item_live"))
                        .contains("UNIQUE INDEX")
                        .contains("(external_item_id)")
                        .contains("WHERE (status <> 'RELEASED'::text)");
                assertThat(text(reader, "SELECT indexdef FROM pg_indexes WHERE schemaname ="
                                + " 'reconciliation' AND indexname = ?",
                                "suspense_item_recon_park_once"))
                        .contains("(external_item_id, park_id)")
                        .contains("WHERE (origin = 'RECON_PARK'::text)");
                assertThat(text(reader, "SELECT indexdef FROM pg_indexes WHERE schemaname ="
                                + " 'reconciliation' AND indexname = ?",
                                "suspense_item_origin_once"))
                        .contains("(origin_ref)")
                        .contains("WHERE (origin <> 'RECON_PARK'::text)");
                assertThat(text(reader, "SELECT indexdef FROM pg_indexes WHERE schemaname ="
                                + " 'reconciliation' AND indexname = ?", "expectation_key_once"))
                        .contains("(source_id, key_kind, key_value)")
                        .contains("WHERE (released_by_resolution_id IS NULL)");
                assertThat(count(reader, "SELECT count(*) FROM pg_constraint WHERE conname IN"
                                + " ('suspense_item_external_item_once',"
                                + " 'suspense_item_origin_once', 'expectation_key_once')"))
                        .as("V004's and V002's once-ever constraints are gone")
                        .isZero();
            } finally {
                reader.rollback();
            }
        }
    }

    private static Connection application() throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(connection);
        return connection;
    }

    private static Connection migrator() throws SQLException {
        Connection connection = DatabaseRoles.migrator();
        connection.setAutoCommit(false);
        return connection;
    }

    private static SQLException refused(Connection connection, String sql, Object... args)
            throws SQLException {
        Savepoint before = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        } catch (SQLException refusal) {
            connection.rollback(before);
            return refusal;
        }
        connection.rollback(before);
        throw new AssertionError("admitted, but expected a refusal: " + sql);
    }

    private static long count(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String text(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("one row for %s", args[0]).isTrue();
                return row.getString(1);
            }
        }
    }

    private static String migration() {
        try (InputStream in =
                ReconciliationV017MigrationTest.class.getClassLoader().getResourceAsStream(V017)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
