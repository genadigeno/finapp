package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Settlement `V012` - the Phase 8 → 9 transition's correction of the audit's {@code SEC-04} and
 * the recorded debt row "Person-written reasons reach the database unscreened", at the database
 * rank ({@code INV-PAY-02}, {@code INV-RAIL-03}): this schema's PL/pgSQL twin of
 * {@link InstrumentShapes} agrees with it over a corpus, and {@code file_event.reason} and
 * {@code batch_event.reason} - where the decline's and the readmission's reasons rest - refuse a
 * card number written with spaces or dashes and a printed account identifier, by name, with
 * nothing stored, for a raw writer past every domain guard.
 *
 * <p>Every probe runs in a transaction that is rolled back (the `V010` test's idiom).
 */
@Tag("database")
@DisplayName("settlement V012 - person-written reasons hold no instrument shape (SEC-04)")
class SettlementV012MigrationTest {

    private static final String MIGRATION =
            "db/migration/settlement/V012__person_written_reasons_hold_no_instrument_shape.sql";
    private static final String SOURCE = "simulated-psp.settlement";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** SEC-03's two card spellings and the printed account identifier. */
    private static final List<String> NEEDLES =
            List.of(
                    "declined: card 4111 1111 1111 1111 in the descriptor",
                    "declined: card 4111-1111-1111-1111 in the descriptor",
                    "pay instead to GB82 WEST 1234 5698 7654 32");

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
    @DisplayName("the twin masks exactly the Java screen's platform identifiers and scans its runs"
            + " and tokens; both reason columns gain their CHECK; the counterparty's references"
            + " take none")
    void theMigrationIsTheTwin() {
        String sql = migration();
        assertThat(sql)
                .contains("'" + InstrumentShapes.PLATFORM_IDENTIFIER_REGEX + "'")
                .contains("'" + InstrumentShapes.DIGIT_RUN_REGEX + "'")
                .contains("'" + InstrumentShapes.ALPHANUMERIC_RUN_REGEX + "'")
                .contains("ADD CONSTRAINT file_event_reason_no_instrument_shape CHECK (\n"
                        + "        NOT settlement.holds_instrument_shape(reason));")
                .contains("ADD CONSTRAINT batch_event_reason_no_instrument_shape CHECK (\n"
                        + "        NOT settlement.holds_instrument_shape(reason));")
                .doesNotContain("ALTER TABLE settlement.line_reference")
                .doesNotContain("ALTER TABLE settlement.batch\n")
                .doesNotContain("GRANT");
    }

    // ----------------------------------------------------------------- the parity

    @Test
    @DisplayName("the database's verdict is the domain's, half for half, over a corpus of grouped"
            + " card numbers, printed and corrupted account identifiers, platform UUIDs and prose")
    void theTwinAgreesWithTheDomainScreen() throws SQLException {
        List<String> corpus = new ArrayList<>(ShapeCorpus.fixed());
        corpus.addAll(ShapeCorpus.random(new Random(12L), 2500));
        long seed = RANDOM.nextLong();
        corpus.addAll(ShapeCorpus.random(new Random(seed), 1500));
        int cards = 0;
        int accounts = 0;
        try (PreparedStatement verdicts =
                application.prepareStatement(
                        "SELECT u.text, settlement.holds_card_number_shape(u.text),"
                                + " settlement.holds_account_identifier_shape(u.text),"
                                + " settlement.holds_instrument_shape(u.text)"
                                + " FROM unnest(?::text[]) WITH ORDINALITY AS u(text, n)"
                                + " ORDER BY u.n")) {
            Array texts = application.createArrayOf("text", corpus.toArray());
            verdicts.setArray(1, texts);
            try (ResultSet row = verdicts.executeQuery()) {
                while (row.next()) {
                    String text = row.getString(1);
                    boolean card = InstrumentShapes.holdsCardNumber(text);
                    boolean account = InstrumentShapes.holdsAccountIdentifier(text);
                    assertThat(row.getBoolean(2))
                            .as("card verdict for [%s] (random seed %d)", text, seed)
                            .isEqualTo(card);
                    assertThat(row.getBoolean(3))
                            .as("account verdict for [%s] (random seed %d)", text, seed)
                            .isEqualTo(account);
                    assertThat(row.getBoolean(4)).isEqualTo(card || account);
                    cards += card ? 1 : 0;
                    accounts += account ? 1 : 0;
                }
            }
        }
        assertThat(cards).as("the corpus exercises the card verdict both ways")
                .isBetween(200, corpus.size() - 200);
        assertThat(accounts).as("the corpus exercises the account verdict both ways")
                .isBetween(200, corpus.size() - 200);
    }

    // ----------------------------------------------------------------- the live rank

    @Test
    @DisplayName("file_event.reason and batch_event.reason refuse a spaced card number, a dashed"
            + " one and a printed account identifier by name, nothing stored, and admit the"
            + " platform's own reasons and clean prose - for a raw writer")
    void bothReasonColumnsRefuseTheNeedles() throws SQLException {
        UUID[] fileAndBatch = parsedBatch();
        UUID fileId = fileAndBatch[0];
        UUID batchId = fileAndBatch[1];
        for (String needle : NEEDLES) {
            refusedBy("file_event_reason_no_instrument_shape",
                    "INSERT INTO settlement.file_event (file_id, from_status, to_status, actor,"
                            + " actor_type, reason, occurred_at, correlation_id) VALUES (?,"
                            + " 'PARSED', 'REJECTED', 'op-v012', 'EMPLOYEE', ?, now(), 'v012')",
                    fileId, needle);
            refusedBy("batch_event_reason_no_instrument_shape",
                    "INSERT INTO settlement.batch_event (batch_id, from_status, to_status, actor,"
                            + " actor_type, reason, occurred_at, correlation_id) VALUES (?,"
                            + " 'PARSED', 'REJECTED', 'op-v012', 'EMPLOYEE', ?, now(), 'v012')",
                    batchId, needle);
        }
        assertThat(count("SELECT count(*) FROM settlement.file_event WHERE file_id = ?", fileId)
                        + count("SELECT count(*) FROM settlement.batch_event WHERE batch_id = ?",
                                batchId))
                .as("nothing stored by any refused writer")
                .isZero();

        // The platform's own reasons and a person's clean one pass: the CHECK refuses the shape.
        for (String reason : List.of("DECLINED", "parse failed: SettlementStorageException",
                "resolution=" + UUID.randomUUID(), "wrong day's report, re-issue expected")) {
            execute("INSERT INTO settlement.file_event (file_id, from_status, to_status, actor,"
                    + " actor_type, reason, occurred_at, correlation_id) VALUES (?, 'PARSED',"
                    + " 'REJECTED', 'op-v012', 'EMPLOYEE', ?, now(), 'v012')", fileId, reason);
        }
        assertThat(count("SELECT count(*) FROM settlement.file_event WHERE file_id = ?", fileId))
                .isEqualTo(4);
    }

    // ----------------------------------------------------------------- seeds (rolled back)

    /** A RECEIVED file and its PARSED report batch, raw as the application role (V010's shape). */
    private static UUID[] parsedBatch() throws SQLException {
        UUID fileId = UUID.randomUUID();
        byte[] sha = new byte[32];
        ThreadLocalRandom.current().nextBytes(sha);
        execute("INSERT INTO settlement.file (id, source_id, received_via, status, format_id,"
                + " format_version, content_sha256, content_length, line_count, key_version,"
                + " received_by, received_at, status_changed_at, correlation_id) SELECT ?, id,"
                + " 'UPLOAD', 'RECEIVED', 'SIM_PSP_CSV', 1, ?, 1, 1, 1, 'op-v012', now(), now(),"
                + " 'v012' FROM settlement.source WHERE code = ?", fileId, sha, SOURCE);
        UUID batchId = UUID.randomUUID();
        execute("INSERT INTO settlement.batch (id, file_id, source_id, external_batch_ref,"
                + " currency, status, business_date, format_id, format_version, line_count,"
                + " declared_line_count, net_minor, net_scale, remittance_reference, created_at,"
                + " status_changed_at, correlation_id) SELECT ?, ?, id, ?, 'EUR', 'PARSED',"
                + " DATE '2026-09-25', 'SIM_PSP_CSV', 1, 0, 0, 0, 2, 'PSP-REM-0001', now(),"
                + " now(), 'v012' FROM settlement.source WHERE code = ?",
                batchId, fileId, "PSPB-V012-" + UUID.randomUUID().toString().substring(0, 18),
                SOURCE);
        return new UUID[] {fileId, batchId};
    }

    // ----------------------------------------------------------------- plumbing

    /** Refused by exactly the named CHECK (23514), its savepoint rolled back - nothing stored. */
    private static void refusedBy(String constraint, String sql, Object... args)
            throws SQLException {
        Savepoint before = application.setSavepoint();
        try {
            execute(sql, args);
        } catch (SQLException refusal) {
            application.rollback(before);
            assertThat(refusal.getSQLState())
                    .as("a CHECK violation, not some other failure: %s", refusal.getMessage())
                    .isEqualTo("23514");
            assertThat(refusal.getMessage()).contains(constraint);
            return;
        }
        application.rollback(before);
        throw new AssertionError("admitted, but " + constraint + " should refuse it: " + sql);
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String migration() {
        try (InputStream in =
                SettlementV012MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
