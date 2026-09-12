package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.consent.ConsentAction;
import com.finapp.consent.ConsentPurpose;
import com.finapp.consent.ConsentRecord;
import com.finapp.consent.ConsentStore;
import com.finapp.consent.JdbcConsentStore;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The consent history against a real PostgreSQL (`P2-TSK-017`): the history IS the store,
 * proven immutable — and the derivation follows the server-assigned order, never a clock.
 *
 * <p>Runs as {@code finapp_app} throughout (the {@code PartyAndIdentitySchemaDatabaseTest}
 * reasoning): every assertion is then also a statement about what the application role can and
 * cannot do, which is the rank {@code INV-CNS-02} claims. The one exception is the fixture that
 * seeds an extra text <em>version</em> — the application role deliberately cannot, so the
 * fixture uses the migrator and removes its rows afterwards, because text versions are shared
 * state every consent test in this JVM reads.
 */
@Tag("database")
@DisplayName("the consent history (P2-TSK-017)")
class ConsentHistoryDatabaseTest {

    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String INSUFFICIENT_PRIVILEGE_MESSAGE = "permission denied";

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final ConsentStore<Connection> store = new JdbcConsentStore();

    // -----------------------------------------------------------------
    // INV-CNS-02: append-only at the privilege level

    @Test
    @DisplayName("no column of a consent record is updatable, and rows cannot be deleted")
    void theHistoryIsImmutableToTheApplication() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            // Per column, because a column-level grant is invisible in table_privileges - the
            // P0-TST-007 finding; the column list from information_schema, so a column added
            // later is swept without anyone remembering.
            List<String> columns = columnsOf(app, "consent_record");
            assertThat(columns)
                    .as("the sweep must actually see the table")
                    .contains("party_id", "purpose", "action", "text_version", "seq");
            for (String column : columns) {
                // seq is probed with DEFAULT, deliberately: GENERATED ALWAYS refuses
                // `seq = seq` before the privilege check ever runs, but `seq = DEFAULT` is the
                // one update the identity mechanism admits - and it would RE-ORDER history,
                // handing an old withdrawal the newest position. The refusal that matters for
                // that statement is the grant's, and this reaches it.
                String value = "seq".equals(column) ? "DEFAULT" : column;
                try (PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE consent.consent_record SET " + column + " = " + value)) {
                    assertThatExceptionOfType(SQLException.class)
                            .as("UPDATE (%s) is a rewritten fact - INV-CNS-02 repealed", column)
                            .isThrownBy(update::executeUpdate)
                            .withMessageContaining(INSUFFICIENT_PRIVILEGE_MESSAGE);
                }
            }
            for (String statement :
                    new String[] {
                        "DELETE FROM consent.consent_record",
                        "TRUNCATE consent.consent_record"
                    }) {
                try (PreparedStatement denied = app.prepareStatement(statement)) {
                    assertThatExceptionOfType(SQLException.class)
                            .as("%s destroys history", statement)
                            .isThrownBy(denied::executeUpdate)
                            .withMessageContaining(INSUFFICIENT_PRIVILEGE_MESSAGE);
                }
            }
        }
    }

    @Test
    @DisplayName("consent texts are unwritable by the application entirely")
    void textsArriveOnlyByMigration() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            for (String statement :
                    new String[] {
                        "INSERT INTO consent.consent_text"
                                + " (purpose, version, body, requires_reconsent, published_at)"
                                + " VALUES ('SCREENING', 99, 'x', false, now())",
                        "UPDATE consent.consent_text SET requires_reconsent = true",
                        "DELETE FROM consent.consent_text"
                    }) {
                try (PreparedStatement denied = app.prepareStatement(statement)) {
                    assertThatExceptionOfType(SQLException.class)
                            .as("a consent text is a migration-reviewed artefact")
                            .isThrownBy(denied::executeUpdate)
                            .withMessageContaining(INSUFFICIENT_PRIVILEGE_MESSAGE);
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // The derivation

    @Test
    @DisplayName("the basis follows the history, and absence equals withdrawal - as an equality")
    void theDerivationFollowsTheHistory() throws SQLException {
        UUID party = IDS.next();
        UUID strangerWithNoHistory = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            int version = store.currentTextFor(app, ConsentPurpose.KYC_PROCESSING).version();

            assertThat(store.hasCurrentBasis(app, party, ConsentPurpose.KYC_PROCESSING))
                    .as("no history is no basis (INV-CNS-01)")
                    .isFalse();

            store.append(
                    app,
                    ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, version));
            assertThat(store.hasCurrentBasis(app, party, ConsentPurpose.KYC_PROCESSING)).isTrue();
            assertThat(store.hasCurrentBasis(app, party, ConsentPurpose.SCREENING))
                    .as("a basis is purpose-scoped - KYC_PROCESSING proves nothing about"
                            + " SCREENING")
                    .isFalse();

            store.append(
                    app,
                    ConsentRecord.withdrawal(
                            IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, version));
            // The INV-CNS-01 shape, asserted as an EQUALITY between the causes rather than two
            // independent assertions: a withdrawn party and a party with no history must get
            // the same answer, or the answer discloses which one they are.
            assertThat(store.hasCurrentBasis(app, party, ConsentPurpose.KYC_PROCESSING))
                    .isEqualTo(
                            store.hasCurrentBasis(
                                    app, strangerWithNoHistory, ConsentPurpose.KYC_PROCESSING))
                    .isFalse();

            store.append(
                    app,
                    ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, version));
            assertThat(store.hasCurrentBasis(app, party, ConsentPurpose.KYC_PROCESSING))
                    .as("re-granting after a withdrawal is a new basis - the history only grows")
                    .isTrue();

            assertThat(store.latestFor(app, party, ConsentPurpose.KYC_PROCESSING))
                    .hasValueSatisfying(
                            latest -> assertThat(latest.action()).isEqualTo(ConsentAction.GRANT));
        }
    }

    @Test
    @DisplayName("the order is the server's sequence, not any instance's clock or commit order")
    void theOrderIsTheSequenceNotTheClock() throws SQLException {
        // Two facts race: the GRANT is inserted FIRST (lower seq) but carries a LATER
        // recorded_at and commits LAST. The withdrawal must win, because it holds the higher
        // seq - the one order every reader agrees on. A derivation ordered by recorded_at
        // would resurrect the grant; one confused by commit order would too. Deterministic,
        // not timed: the interleaving is held open by this test's own transactions.
        UUID party = IDS.next();
        try (Connection first = DatabaseRoles.application();
                Connection second = DatabaseRoles.application();
                Connection reader = DatabaseRoles.application()) {
            int version = store.currentTextFor(reader, ConsentPurpose.SCREENING).version();

            first.setAutoCommit(false);
            insertRawRecord(
                    first, party, "SCREENING", "GRANT", version,
                    Instant.parse("2026-09-12T12:00:05Z")); // later clock, lower seq

            insertRawRecord(
                    second, party, "SCREENING", "WITHDRAWAL", version,
                    Instant.parse("2026-09-12T12:00:00Z")); // earlier clock, higher seq
            // `second` is auto-commit: the withdrawal is durable before the grant commits.

            first.commit();

            assertThat(store.hasCurrentBasis(reader, party, ConsentPurpose.SCREENING))
                    .as("the higher-seq withdrawal is the latest fact, whatever the clocks and"
                            + " the commit order said")
                    .isFalse();
            assertThat(store.latestFor(reader, party, ConsentPurpose.SCREENING))
                    .hasValueSatisfying(
                            latest ->
                                    assertThat(latest.action())
                                            .isEqualTo(ConsentAction.WITHDRAWAL));
        }
    }

    @Test
    @DisplayName("ten instances append concurrently: no locks, no losers, one agreed answer")
    void tenInstancesAppendWithoutConflict() throws Exception {
        UUID party = IDS.next();
        int instances = 10;
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try (Connection reader = DatabaseRoles.application()) {
            int version = store.currentTextFor(reader, ConsentPurpose.KYC_PROCESSING).version();
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> appends = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                boolean grant = i % 2 == 0;
                appends.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    try (Connection own = DatabaseRoles.application()) {
                                        store.append(
                                                own,
                                                grant
                                                        ? ConsentRecord.grant(
                                                                IDS, CLOCK, party,
                                                                ConsentPurpose.KYC_PROCESSING,
                                                                version)
                                                        : ConsentRecord.withdrawal(
                                                                IDS, CLOCK, party,
                                                                ConsentPurpose.KYC_PROCESSING,
                                                                version));
                                    }
                                    return null;
                                }));
            }
            go.countDown();
            for (Future<?> append : appends) {
                append.get(); // every appender succeeds - append-only has no losing branch
            }

            assertThat(historyCountOf(reader, party)).isEqualTo(instances);
            // Which fact is last was a genuine race; the sequence arbitrates, and the derived
            // answer must agree with the raw highest-seq row - the same answer for every reader.
            assertThat(store.hasCurrentBasis(reader, party, ConsentPurpose.KYC_PROCESSING))
                    .isEqualTo("GRANT".equals(highestSeqActionOf(reader, party)));
        } finally {
            pool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------
    // INV-CNS-04: the pin

    @Test
    @DisplayName("a null text version is refused, and so is another purpose's version")
    void theVersionPinIsUnforgeable() throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            try (PreparedStatement unpinned =
                    app.prepareStatement(
                            "INSERT INTO consent.consent_record"
                                    + " (id, party_id, purpose, action, text_version,"
                                    + " recorded_at) VALUES (?, ?, 'SCREENING', 'GRANT', NULL,"
                                    + " now())")) {
                unpinned.setObject(1, IDS.next());
                unpinned.setObject(2, party);
                assertThatExceptionOfType(SQLException.class)
                        .as("version-pinning refused null (INV-CNS-04)")
                        .isThrownBy(unpinned::executeUpdate)
                        .satisfies(
                                failure ->
                                        assertThat(failure.getSQLState())
                                                .isEqualTo(NOT_NULL_VIOLATION));
            }

            // The composite-FK half (the V008 lesson): seed v2 for SCREENING alone, then try
            // to pin it from KYC_PROCESSING - a version that EXISTS, for the wrong purpose.
            seedTextVersion(ConsentPurpose.SCREENING, 2, false);
            try {
                try (PreparedStatement crossPurpose =
                        app.prepareStatement(
                                "INSERT INTO consent.consent_record"
                                        + " (id, party_id, purpose, action, text_version,"
                                        + " recorded_at) VALUES (?, ?, 'KYC_PROCESSING',"
                                        + " 'GRANT', 2, now())")) {
                    crossPurpose.setObject(1, IDS.next());
                    crossPurpose.setObject(2, party);
                    assertThatExceptionOfType(SQLException.class)
                            .as("a record pinning another purpose's text references an artefact"
                                    + " the person was never shown")
                            .isThrownBy(crossPurpose::executeUpdate)
                            .satisfies(
                                    failure ->
                                            assertThat(failure.getSQLState())
                                                    .isEqualTo(FOREIGN_KEY_VIOLATION));
                }
            } finally {
                removeSeededTextVersions();
            }
        }
    }

    @Test
    @DisplayName("a newer version requiring re-consent ends the basis; one that does not, does not")
    void reconsentIsAPropertyOfTheVersion() throws SQLException {
        UUID lapsing = IDS.next();
        UUID surviving = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            store.append(
                    app, ConsentRecord.grant(IDS, CLOCK, lapsing, ConsentPurpose.SCREENING, 1));
            store.append(
                    app,
                    ConsentRecord.grant(IDS, CLOCK, surviving, ConsentPurpose.KYC_PROCESSING, 1));
            assertThat(store.hasCurrentBasis(app, lapsing, ConsentPurpose.SCREENING)).isTrue();

            // v2 arrives - by migration in production, by the migrator here, because the
            // application role deliberately cannot write texts.
            seedTextVersion(ConsentPurpose.SCREENING, 2, true);
            seedTextVersion(ConsentPurpose.KYC_PROCESSING, 2, false);
            try {
                assertThat(store.hasCurrentBasis(app, lapsing, ConsentPurpose.SCREENING))
                        .as("a grant against v1 proves nothing about a v2 that demands"
                                + " re-consent (INV-CNS-04)")
                        .isFalse();
                assertThat(store.hasCurrentBasis(app, surviving, ConsentPurpose.KYC_PROCESSING))
                        .as("whether grants lapse is a recorded property of the version, not a"
                                + " blanket rule")
                        .isTrue();
                assertThat(store.currentTextFor(app, ConsentPurpose.SCREENING).version())
                        .as("the current text is the highest version - what P2-TSK-018 presents")
                        .isEqualTo(2);
            } finally {
                removeSeededTextVersions();
            }
        }
    }

    // -----------------------------------------------------------------
    // Fixtures

    /** Raw insert so the test controls recorded_at exactly; the store's columns, verbatim. */
    private static void insertRawRecord(
            Connection connection,
            UUID party,
            String purpose,
            String action,
            int version,
            Instant recordedAt)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO consent.consent_record"
                                + " (id, party_id, purpose, action, text_version, recorded_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, party);
            insert.setString(3, purpose);
            insert.setString(4, action);
            insert.setInt(5, version);
            insert.setTimestamp(6, Timestamp.from(recordedAt));
            insert.executeUpdate();
        }
    }

    /**
     * Seeds an extra text version as the migrator — texts arrive only by migration in
     * production, and this models exactly that arrival. Marked by version {@code >= 2} so
     * {@link #removeSeededTextVersions()} deletes precisely what tests seeded and never the
     * migration's v1 rows, since text versions are shared state every consent test reads.
     */
    private static void seedTextVersion(
            ConsentPurpose purpose, int version, boolean requiresReconsent) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement insert =
                        migrator.prepareStatement(
                                "INSERT INTO consent.consent_text"
                                        + " (purpose, version, body, requires_reconsent,"
                                        + " published_at) VALUES (?, ?, 'test fixture version',"
                                        + " ?, now())")) {
            insert.setString(1, purpose.name());
            insert.setInt(2, version);
            insert.setBoolean(3, requiresReconsent);
            insert.executeUpdate();
        }
    }

    private static void removeSeededTextVersions() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement delete =
                        migrator.prepareStatement(
                                "DELETE FROM consent.consent_text WHERE version >= 2"
                                        + " AND body = 'test fixture version'")) {
            delete.executeUpdate();
        }
    }

    private static List<String> columnsOf(Connection connection, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_schema = 'consent' AND table_name = ?")) {
            select.setString(1, table);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        return columns;
    }

    private static long historyCountOf(Connection connection, UUID party) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT count(*) FROM consent.consent_record WHERE party_id = ?")) {
            select.setObject(1, party);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static String highestSeqActionOf(Connection connection, UUID party)
            throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT action FROM consent.consent_record WHERE party_id = ?"
                                + " ORDER BY seq DESC LIMIT 1")) {
            select.setObject(1, party);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }
}
