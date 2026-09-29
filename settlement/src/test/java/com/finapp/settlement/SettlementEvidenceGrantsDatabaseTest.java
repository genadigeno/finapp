package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `V002`'s privilege model and triggers, exercised (`P8-TSK-002`): append-only binds EVERY
 * writer — the migrator included — the file's `UPDATE` is column-narrowed, no `DELETE` exists
 * for the application role anywhere, and the machines that have no edges yet refuse every
 * move. The {@code ApplicationRoleGrantsTest} asymmetry: the denials are asserted at least as
 * carefully as the permissions, because a grant too wide fails silently, forever.
 */
@Tag("database")
@DisplayName("settlement evidence grants and triggers (P8-TSK-002)")
class SettlementEvidenceGrantsDatabaseTest {

    /** PostgreSQL's SQLState for a refused privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static Connection application;
    private static Connection migrator;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        migrator = DatabaseRoles.migrator();
        migrator.setAutoCommit(false);
        probeFileId = insertedFileRow();
        seedProbeRows(probeFileId);
    }

    /** One committed row per append-only table, so every row trigger has a row to fire on. */
    private static void seedProbeRows(String fileId) throws SQLException {
        execute(application,
                "INSERT INTO settlement.file_chunk (file_id, seq, ciphertext, nonce,"
                        + " plaintext_length) VALUES ('" + fileId + "', 0,"
                        + " decode('" + "00".repeat(17) + "', 'hex'),"
                        + " decode('" + "00".repeat(12) + "', 'hex'), 1)");
        execute(application,
                "INSERT INTO settlement.file_event (file_id, from_status, to_status, actor,"
                        + " actor_type, occurred_at, correlation_id) VALUES ('" + fileId
                        + "', NULL, 'RECEIVED', 'op-alice', 'EMPLOYEE', now(), 'grants-test')");
        execute(application,
                "INSERT INTO settlement.file_receipt (id, file_id, outcome, channel, actor,"
                        + " actor_type, received_at, correlation_id) VALUES ('"
                        + java.util.UUID.randomUUID() + "', '" + fileId + "', 'NEW', 'UPLOAD',"
                        + " 'op-alice', 'EMPLOYEE', now(), 'grants-test')");
        execute(application,
                "INSERT INTO settlement.refused_delivery (id, source_id, content_sha256,"
                        + " content_length, format_id, format_version, reason, line_no,"
                        + " field_name, channel, actor, actor_type, refused_at, correlation_id)"
                        + " SELECT '" + java.util.UUID.randomUUID() + "', id,"
                        + " decode('" + "cd".repeat(32) + "', 'hex'), 1, 'SIM_PSP_CSV', 1,"
                        + " 'PRIMARY_ACCOUNT_NUMBER', 1, NULL, 'UPLOAD', 'op-alice',"
                        + " 'EMPLOYEE', now(), 'grants-test'"
                        + " FROM settlement.source WHERE code = 'simulated-psp.settlement'");
        application.commit();
    }

    private static String probeFileId;

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
        if (migrator != null) {
            migrator.rollback();
            migrator.close();
        }
    }

    @Test
    @DisplayName("the evidence tables are append-only for the application role by privilege"
            + " AND for the migrator by trigger")
    void appendOnlyBindsEveryWriter() throws SQLException {
        Map<String, String> tables =
                Map.of(
                        "file_chunk", "plaintext_length = 1",
                        "file_event", "correlation_id = 'x'",
                        "file_receipt", "correlation_id = 'x'",
                        "refused_delivery", "correlation_id = 'x'");
        for (Map.Entry<String, String> probe : new java.util.TreeMap<>(tables).entrySet()) {
            String table = probe.getKey();
            assertThatExceptionOfType(SQLException.class)
                    .as("%s: the application role holds no UPDATE", table)
                    .isThrownBy(
                            () ->
                                    execute(
                                            application,
                                            "UPDATE settlement." + table
                                                    + " SET " + probe.getValue()))
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
            application.rollback();
            assertThatExceptionOfType(SQLException.class)
                    .as("%s: the application role holds no DELETE", table)
                    .isThrownBy(() -> execute(application, "DELETE FROM settlement." + table))
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
            application.rollback();
            assertThatExceptionOfType(SQLException.class)
                    .as("%s: the migrator is bound by the trigger", table)
                    .isThrownBy(() -> execute(migrator, "DELETE FROM settlement." + table))
                    .withMessageContaining("append-only");
            migrator.rollback();
        }
    }

    @Test
    @DisplayName("file_chunk's UPDATE is refused for the migrator too - retained bytes are"
            + " never edited")
    void chunksAreNeverEdited() throws SQLException {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () ->
                                execute(
                                        migrator,
                                        "UPDATE settlement.file_chunk SET plaintext_length = 1"))
                .withMessageContaining("append-only");
        migrator.rollback();
    }

    @Test
    @DisplayName("the file row's UPDATE is narrowed: the lifecycle columns move, the birth"
            + " statement cannot")
    void theFileUpdateIsNarrowed() throws SQLException {
        // The frozen columns are not even grantable mistakes: the application role's UPDATE
        // simply does not cover them.
        for (String frozen :
                List.of("content_sha256 = '\\x00'", "source_id = NULL", "received_via = 'PULL'")) {
            assertThatExceptionOfType(SQLException.class)
                    .as("the application role cannot move %s", frozen)
                    .isThrownBy(
                            () -> execute(application, "UPDATE settlement.file SET " + frozen))
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(e.getSQLState()));
            application.rollback();
        }
        // And for every writer, the trigger, probed on a REAL row (a vacuous UPDATE fires no
        // row trigger and proves nothing): no edge exists yet, so status cannot move at all,
        // and an attestation is recorded once.
        String fileId = probeFileId;
        assertThatExceptionOfType(SQLException.class)
                .as("no edge leaves RECEIVED until its producer exists")
                .isThrownBy(
                        () ->
                                execute(
                                        migrator,
                                        "UPDATE settlement.file SET status = 'PARSED'"
                                                + " WHERE id = '" + fileId + "'"))
                .withMessageContaining("no edge");
        migrator.rollback();

        execute(application,
                "UPDATE settlement.file SET attested_by = 'op-bob', attested_at = now()"
                        + " WHERE id = '" + fileId + "'");
        assertThatExceptionOfType(SQLException.class)
                .as("an attestation never moves once recorded (INV-SET-07)")
                .isThrownBy(
                        () ->
                                execute(
                                        application,
                                        "UPDATE settlement.file SET attested_by = 'op-carol'"
                                                + " WHERE id = '" + fileId + "'"))
                .withMessageContaining("once");
        application.rollback();

        // The uploader cannot attest their own file - the CHECK, for every writer.
        assertThatExceptionOfType(SQLException.class)
                .as("self-attestation is refused at the schema (INV-SET-07)")
                .isThrownBy(
                        () ->
                                execute(
                                        application,
                                        "UPDATE settlement.file SET attested_by = 'op-alice',"
                                                + " attested_at = now() WHERE id = '"
                                                + fileId + "'"))
                .withMessageContaining("file_attester_is_second_person");
        application.rollback();
    }

    /** A minimal RECEIVED row, inserted as the application role inside its transaction. */
    private static String insertedFileRow() throws SQLException {
        String id = java.util.UUID.randomUUID().toString();
        execute(application,
                "INSERT INTO settlement.file (id, source_id, received_via, status, format_id,"
                        + " format_version, content_sha256, content_length, line_count,"
                        + " key_version, received_by, received_at, status_changed_at,"
                        + " correlation_id) SELECT '" + id + "', id, 'UPLOAD', 'RECEIVED',"
                        + " 'SIM_PSP_CSV', 1, decode('" + "ab".repeat(32) + "', 'hex'), 1, 1,"
                        + " 1, 'op-alice', now(), now(), 'grants-test'"
                        + " FROM settlement.source WHERE code = 'simulated-psp.settlement'");
        // Committed, so the migrator's connection can meet the row: the database is this test
        // JVM's own container, and evidence rows are never deleted anyway.
        application.commit();
        return id;
    }

    @Test
    @DisplayName("no settlement table grants DELETE to the application role - the"
            + " break-immutability floor's settlement half")
    void noDeleteAnywhere() throws SQLException {
        try (Statement statement = application.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT count(*) FROM information_schema.table_privileges"
                                        + " WHERE table_schema = 'settlement'"
                                        + " AND privilege_type = 'DELETE'"
                                        + " AND grantee = current_user")) {
            rows.next();
            assertThat(rows.getLong(1)).isZero();
        }
        application.commit();
    }

    @Test
    @DisplayName("a source retires once: RETIRED never returns, identity is frozen and the"
            + " sequence never walks backwards")
    void theSourceMovesForwardOnly() throws SQLException {
        // In a transaction that rolls back, so the seeded fixture is untouched.
        execute(application,
                "UPDATE settlement.source SET status = 'RETIRED'"
                        + " WHERE code = 'simulated-psp.settlement'");
        assertThatExceptionOfType(SQLException.class)
                .as("RETIRED -> ACTIVE is refused")
                .isThrownBy(
                        () ->
                                execute(
                                        application,
                                        "UPDATE settlement.source SET status = 'ACTIVE'"
                                                + " WHERE code = 'simulated-psp.settlement'"))
                .withMessageContaining("retires once");
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .as("the sequence never walks backwards")
                .isThrownBy(
                        () ->
                                execute(
                                        application,
                                        "UPDATE settlement.source SET next_sequence = 0"
                                                + " WHERE code = 'simulated-psp.settlement'"))
                .withMessageContaining("never walks backwards");
        application.rollback();

        assertThatExceptionOfType(SQLException.class)
                .as("identity is frozen for every writer")
                .isThrownBy(
                        () ->
                                execute(
                                        migrator,
                                        "UPDATE settlement.source SET code = 'renamed.source'"
                                                + " WHERE code = 'simulated-psp.settlement'"))
                .withMessageContaining("frozen");
        migrator.rollback();

        assertThatExceptionOfType(SQLException.class)
                .as("a source is never deleted, for any writer")
                .isThrownBy(() -> execute(migrator, "DELETE FROM settlement.source"))
                .withMessageContaining("never deleted");
        migrator.rollback();
    }

    @Test
    @DisplayName("the four sources are seeded ACTIVE with their declared codes and kinds")
    void theSeedsStand() throws SQLException {
        try (Statement statement = application.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT code, kind, status, next_sequence"
                                        + " FROM settlement.source ORDER BY code")) {
            StringBuilder seeded = new StringBuilder();
            while (rows.next()) {
                seeded.append(rows.getString(1))
                        .append('=')
                        .append(rows.getString(2))
                        .append(':')
                        .append(rows.getString(3))
                        .append(':')
                        .append(rows.getLong(4))
                        .append(';');
            }
            assertThat(seeded.toString())
                    .isEqualTo(
                            "simulated-bank.statement=BANK_STATEMENT:ACTIVE:1;"
                                    + "simulated-payout.settlement=PAYOUT_PROVIDER_REPORT:ACTIVE:1;"
                                    + "simulated-psp.settlement=PSP_SETTLEMENT_REPORT:ACTIVE:1;"
                                    + "simulated-scheme.cycle-report=SCHEME_CYCLE_REPORT:ACTIVE:1;");
        }
        application.commit();
    }

    // -----------------------------------------------------------------

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
