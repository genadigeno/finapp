package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * FX provider evidence against a live PostgreSQL (`P9-TSK-006`, {@code INV-HIST-02}): retained
 * verbatim, encrypted, verified on read, append-only for every writer, and the cipher's arithmetic
 * held by the database itself.
 */
@Tag("database")
@DisplayName("FX provider evidence: verbatim, encrypted, verified, append-only (P9-TSK-006)")
class FxProviderEvidenceDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final byte[] KEY = new byte[32];
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String CHECK_VIOLATION = "23514";
    private static final String RAISED = "P0001";

    private final FxEvidenceCipher cipher = new FxEvidenceCipher(KEY, 1, new SecureRandom());
    private final FxProviderEvidenceStore<Connection> store =
            new JdbcFxProviderEvidenceStore(cipher, IDS);

    @Test
    @DisplayName("bytes go in verbatim and come back verbatim, oldest first, under our reference -"
            + " and the stored column is ciphertext, never the plaintext")
    void evidenceRoundTrips() throws SQLException {
        String reference = reference();
        byte[] request = "{\"reference\":\"QR\"}".getBytes(StandardCharsets.UTF_8);
        byte[] response = "{\"status\":\"quoted\",\"rate\":\"1.0850240000\"}".getBytes(StandardCharsets.UTF_8);
        try (Connection app = application()) {
            store.append(app, "fx-sim-a", reference, FxProviderEvidenceStore.Kind.REQUEST, request,
                    Instant.parse("2026-10-04T10:00:00Z"));
            store.append(app, "fx-sim-a", reference, FxProviderEvidenceStore.Kind.RESPONSE, response,
                    Instant.parse("2026-10-04T10:00:01Z"));
            app.commit();
            List<byte[]> back = store.payloadsFor(app, "fx-sim-a", reference);
            assertThat(back).hasSize(2);
            assertThat(back.get(0)).isEqualTo(request);
            assertThat(back.get(1)).isEqualTo(response);
            assertThat(new String(storedCiphertext(app, reference), StandardCharsets.ISO_8859_1))
                    .doesNotContain("quoted")
                    .doesNotContain("1.0850240000");
            app.commit();
        }
    }

    @Test
    @DisplayName("evidence is append-only: the application cannot update or delete it, and the"
            + " migrator is refused by the trigger")
    void evidenceIsAppendOnly() throws SQLException {
        String reference = reference();
        try (Connection app = application(); Connection migrator = DatabaseRoles.migrator()) {
            store.append(app, "fx-sim-a", reference, FxProviderEvidenceStore.Kind.CALLBACK,
                    "x".getBytes(StandardCharsets.UTF_8), Instant.now());
            app.commit();
            assertThatThrownBy(() -> execute(app, "UPDATE fx.fx_provider_evidence SET kind = 'REQUEST'"
                            + " WHERE client_reference = '" + reference + "'"))
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(app, "DELETE FROM fx.fx_provider_evidence"
                            + " WHERE client_reference = '" + reference + "'"))
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(migrator, "UPDATE fx.fx_provider_evidence SET kind = 'REQUEST'"
                            + " WHERE client_reference = '" + reference + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            assertThatThrownBy(() -> execute(migrator, "DELETE FROM fx.fx_provider_evidence"
                            + " WHERE client_reference = '" + reference + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
        }
    }

    @Test
    @DisplayName("a writer that never passed through the store cannot store plaintext dressed as"
            + " ciphertext, a non-GCM nonce, or an unknown kind")
    void theDatabaseHoldsTheCiphersArithmetic() throws SQLException {
        try (Connection app = application()) {
            byte[] plaintext = "plaintext pretending".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> rawInsert(app, "RESPONSE", plaintext, new byte[12], plaintext.length))
                    .as("ciphertext without the 16-byte tag")
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> rawInsert(app, "RESPONSE", new byte[20 + 16], new byte[8], 20))
                    .as("a 64-bit nonce")
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> rawInsert(app, "WEBHOOK", new byte[20 + 16], new byte[12], 20))
                    .as("a kind outside the list")
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
        }
    }

    @Test
    @DisplayName("an empty payload is refused before any write: absence is not evidence")
    void anEmptyPayloadIsRefused() throws SQLException {
        try (Connection app = application()) {
            assertThatThrownBy(() -> store.append(app, "fx-sim-a", reference(),
                            FxProviderEvidenceStore.Kind.RESPONSE, new byte[0], Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
            app.rollback();
        }
    }

    // -----------------------------------------------------------------

    private static void rawInsert(
            Connection app, String kind, byte[] ciphertext, byte[] nonce, int length)
            throws SQLException {
        try (PreparedStatement insert =
                app.prepareStatement(
                        "INSERT INTO fx.fx_provider_evidence (id, provider_code, client_reference,"
                                + " kind, content_ciphertext, content_nonce, key_version,"
                                + " checksum_sha256, content_length, recorded_at)"
                                + " VALUES (?, 'fx-sim-a', ?, ?, ?, ?, 1, ?, ?, ?)")) {
            insert.setObject(1, IDS.next());
            insert.setString(2, reference());
            insert.setString(3, kind);
            insert.setBytes(4, ciphertext);
            insert.setBytes(5, nonce);
            insert.setBytes(6, new byte[32]);
            insert.setInt(7, length);
            insert.setTimestamp(8, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
    }

    private static byte[] storedCiphertext(Connection connection, String reference) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery(
                                "SELECT content_ciphertext FROM fx.fx_provider_evidence"
                                        + " WHERE client_reference = '" + reference + "'"
                                        + " AND kind = 'RESPONSE'")) {
            assertThat(row.next()).isTrue();
            return row.getBytes(1);
        }
    }

    private static Connection application() throws SQLException {
        Connection app = DatabaseRoles.application();
        app.setAutoCommit(false);
        return app;
    }

    private static String reference() {
        return "T-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
