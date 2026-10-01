package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Settlement `V010` (`P8-TSK-023`): the batch machine whole - {@code ACCEPTED → REPUDIATED} its
 * one exit from {@code ACCEPTED}, {@code REJECTED} and {@code REPUDIATED} terminal - read as
 * text against {@link BatchStatus}, then held live for ANY writer: raw SQL as the application
 * role, past every domain guard. A repudiated batch keeps its acceptance facts, and its live
 * identity frees for the genuine file.
 *
 * <p>Every probe runs in a transaction that is rolled back: its rows - a sequence number among
 * them - never reach the shared schema, so no other suite's gapless sequence or accept leg can
 * meet them.
 */
@Tag("database")
@DisplayName("settlement V010 - the repudiated batch (P8-TSK-023)")
class SettlementV010MigrationTest {

    private static final String MIGRATION =
            "db/migration/settlement/V010__the_repudiated_batch.sql";
    private static final String SOURCE = "simulated-psp.settlement";

    private static Connection application;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
    }

    @AfterEach
    void rollBack() throws SQLException {
        application.rollback();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // ----------------------------------------------------------------- the text

    @Test
    @DisplayName("the CHECK and the transition rule are BatchStatus's mirrors, the machine whole")
    void theEnumReconcilesWithTheMigration() {
        assertThat(BatchStatus.ACCEPTED.permittedTransitions())
                .containsExactly(BatchStatus.REPUDIATED);
        assertThat(BatchStatus.ACCEPTED.isTerminal()).isFalse();
        assertThat(BatchStatus.REJECTED.isTerminal()).isTrue();
        assertThat(BatchStatus.REPUDIATED.isTerminal()).isTrue();

        String sql = normalized(migration());
        assertThat(sql)
                .contains("CONSTRAINT batch_status CHECK (status IN ("
                        + BatchStatus.sqlValueList() + "))")
                .contains("AND NOT (" + BatchStatus.sqlTransitionRule() + ") THEN")
                .as("the acceptance facts stay frozen, the repudiation edge included")
                .contains("IF OLD.source_sequence IS NOT NULL AND (NEW.source_sequence IS"
                        + " DISTINCT FROM OLD.source_sequence")
                .contains("IF OLD.source_sequence IS NULL AND NEW.source_sequence IS NOT NULL"
                        + " AND NEW.status <> 'ACCEPTED' THEN")
                .as("a repudiated batch is held to the honesty rules it was accepted under")
                .contains("CONSTRAINT batch_accepted_carries_its_facts CHECK (status NOT IN"
                        + " ('ACCEPTED', 'REPUDIATED') OR (source_sequence IS NOT NULL AND"
                        + " accepted_on IS NOT NULL))")
                .contains("CONSTRAINT batch_posting_omitted_is_honest CHECK (status NOT IN"
                        + " ('ACCEPTED', 'REPUDIATED') OR ((journal_entry_id IS NULL) ="
                        + " posting_omitted))")
                .as("no grant widens, no index changes, and the file is untouched")
                .doesNotContain("GRANT ")
                .doesNotContain("INDEX")
                .doesNotContain("settlement.file ");
    }

    // ----------------------------------------------------------------- the live machine

    @Test
    @DisplayName("ACCEPTED -> REPUDIATED is admitted for any writer, the facts kept as they were")
    void acceptedToRepudiatedIsAdmitted() throws SQLException {
        UUID batch = acceptedBatch(ref());
        String factsBefore = factsOf(batch);

        assertThat(execute("UPDATE settlement.batch SET status = 'REPUDIATED',"
                        + " status_changed_at = now() WHERE id = ?", batch))
                .isEqualTo(1);

        assertThat(scalar("SELECT status FROM settlement.batch WHERE id = ?", batch))
                .isEqualTo("REPUDIATED");
        assertThat(factsOf(batch)).as("INV-HIST-01: what acceptance recorded stands")
                .isEqualTo(factsBefore);
    }

    @Test
    @DisplayName("PARSED -> REPUDIATED, REJECTED -> REPUDIATED and REPUDIATED -> ACCEPTED are"
            + " refused by the trigger for any writer")
    void theOtherEdgesAreRefused() throws SQLException {
        UUID parsed = parsedBatch(ref());
        assertRefused(
                "UPDATE settlement.batch SET status = 'REPUDIATED', status_changed_at = now()"
                        + " WHERE id = ?",
                parsed,
                "not a settlement batch edge: PARSED -> REPUDIATED");

        UUID rejected = parsedBatch(ref());
        assertThat(execute("UPDATE settlement.batch SET status = 'REJECTED',"
                        + " status_changed_at = now() WHERE id = ?", rejected))
                .isEqualTo(1);
        assertRefused(
                "UPDATE settlement.batch SET status = 'REPUDIATED', status_changed_at = now()"
                        + " WHERE id = ?",
                rejected,
                "not a settlement batch edge: REJECTED -> REPUDIATED");

        UUID repudiated = acceptedBatch(ref());
        assertThat(execute("UPDATE settlement.batch SET status = 'REPUDIATED',"
                        + " status_changed_at = now() WHERE id = ?", repudiated))
                .isEqualTo(1);
        assertRefused(
                "UPDATE settlement.batch SET status = 'ACCEPTED', status_changed_at = now()"
                        + " WHERE id = ?",
                repudiated,
                "not a settlement batch edge: REPUDIATED -> ACCEPTED");
        assertRefused(
                "UPDATE settlement.batch SET status = 'REJECTED', status_changed_at = now()"
                        + " WHERE id = ?",
                repudiated,
                "not a settlement batch edge: REPUDIATED -> REJECTED");
    }

    @Test
    @DisplayName("the acceptance facts cannot change on the repudiation write, nor after it")
    void theFactsAreFrozenAcrossTheEdge() throws SQLException {
        for (String change :
                new String[] {
                    "source_sequence = source_sequence + 1",
                    "accepted_on = accepted_on + 1",
                    "journal_entry_id = gen_random_uuid()",
                    "posting_omitted = NOT posting_omitted"
                }) {
            UUID onTheEdge = acceptedBatch(ref());
            assertRefused(
                    "UPDATE settlement.batch SET status = 'REPUDIATED', status_changed_at ="
                            + " now(), " + change + " WHERE id = ?",
                    onTheEdge,
                    "acceptance facts are recorded once");

            UUID after = acceptedBatch(ref());
            assertThat(execute("UPDATE settlement.batch SET status = 'REPUDIATED',"
                            + " status_changed_at = now() WHERE id = ?", after))
                    .isEqualTo(1);
            assertRefused(
                    "UPDATE settlement.batch SET " + change + " WHERE id = ?",
                    after,
                    "acceptance facts are recorded once");
        }
    }

    @Test
    @DisplayName("the live uniques exclude REPUDIATED: the same identity is refused while the"
            + " batch is ACCEPTED and admitted as a new PARSED batch once it is REPUDIATED")
    void theLiveIdentityFreesOnRepudiation() throws SQLException {
        String ref = ref();
        UUID original = acceptedBatch(ref);

        Savepoint beforeConflict = application.setSavepoint();
        assertThatExceptionOfType(SQLException.class)
                .as("the control: an ACCEPTED batch holds its identity")
                .isThrownBy(() -> parsedBatch(ref))
                .withMessageContaining("batch_live_identity");
        application.rollback(beforeConflict);

        assertThat(execute("UPDATE settlement.batch SET status = 'REPUDIATED',"
                        + " status_changed_at = now() WHERE id = ?", original))
                .isEqualTo(1);
        UUID genuine = parsedBatch(ref);
        assertThat(scalar("SELECT status FROM settlement.batch WHERE id = ?", genuine))
                .isEqualTo("PARSED");
    }

    // ----------------------------------------------------------------- seeds (rolled back)

    /** A unique batch reference per seed, so no probe meets another's identity. */
    private static String ref() {
        return "PSPB-V010-" + UUID.randomUUID().toString().substring(0, 18);
    }

    /** A RECEIVED file and its PARSED report batch, raw as the application role. */
    private static UUID parsedBatch(String ref) throws SQLException {
        UUID fileId = UUID.randomUUID();
        byte[] sha = new byte[32];
        ThreadLocalRandom.current().nextBytes(sha);
        try (PreparedStatement insert =
                application.prepareStatement(
                        "INSERT INTO settlement.file (id, source_id, received_via, status,"
                                + " format_id, format_version, content_sha256, content_length,"
                                + " line_count, key_version, received_by, received_at,"
                                + " status_changed_at, correlation_id)"
                                + " SELECT ?, id, 'UPLOAD', 'RECEIVED', 'SIM_PSP_CSV', 1, ?, 1,"
                                + " 1, 1, 'op-v010', now(), now(), 'p8-tsk-023-v010'"
                                + " FROM settlement.source WHERE code = ?")) {
            insert.setObject(1, fileId);
            insert.setBytes(2, sha);
            insert.setString(3, SOURCE);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        UUID batchId = UUID.randomUUID();
        try (PreparedStatement insert =
                application.prepareStatement(
                        "INSERT INTO settlement.batch (id, file_id, source_id, external_batch_ref,"
                                + " currency, status, business_date, format_id, format_version,"
                                + " line_count, declared_line_count, net_minor, net_scale,"
                                + " remittance_reference, created_at, status_changed_at,"
                                + " correlation_id)"
                                + " SELECT ?, ?, id, ?, 'EUR', 'PARSED', DATE '2026-09-25',"
                                + " 'SIM_PSP_CSV', 1, 0, 0, 0, 2, 'PSP-REM-0001', now(), now(),"
                                + " 'p8-tsk-023-v010' FROM settlement.source WHERE code = ?")) {
            insert.setObject(1, batchId);
            insert.setObject(2, fileId);
            insert.setString(3, ref);
            insert.setString(4, SOURCE);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return batchId;
    }

    /**
     * A batch accepted raw with its four facts in one statement (V004's shape). The sequence is
     * far beyond any the accept leg reaches, and rolled back with the probe regardless.
     */
    private static UUID acceptedBatch(String ref) throws SQLException {
        UUID batchId = parsedBatch(ref);
        assertThat(execute(
                        "UPDATE settlement.batch SET status = 'ACCEPTED', source_sequence = ?,"
                                + " accepted_on = DATE '2026-09-29', journal_entry_id ="
                                + " gen_random_uuid(), posting_omitted = false,"
                                + " status_changed_at = now() WHERE id = ?",
                        ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L),
                        batchId))
                .isEqualTo(1);
        return batchId;
    }

    private static String factsOf(UUID batchId) throws SQLException {
        return scalar(
                "SELECT source_sequence || '/' || accepted_on || '/' || journal_entry_id || '/'"
                        + " || posting_omitted FROM settlement.batch WHERE id = ?",
                batchId);
    }

    /** The statement refused inside a savepoint, so the probe's transaction stays usable. */
    private static void assertRefused(String sql, UUID batchId, String message)
            throws SQLException {
        Savepoint before = application.setSavepoint();
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> execute(sql, batchId))
                .withMessageContaining(message);
        application.rollback(before);
    }

    private static int execute(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static String scalar(String sql, UUID id) throws SQLException {
        try (PreparedStatement read = application.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static String migration() {
        try (InputStream in =
                SettlementV010MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Statements only, whitespace collapsed - so a fragment matches however the SQL wraps. */
    private static String normalized(String sql) {
        return sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"))
                .replaceAll("\\s+", " ")
                .replace("( ", "(")
                .replace(" )", ")");
    }
}
