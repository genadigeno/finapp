package com.finapp.app.kyc;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * kyc `V009`'s reason screen (`P9-TSK-016`, {@code INV-AUD-02}): this schema's PL/pgSQL twin of
 * {@code InstrumentShapes} - fx `V004`'s, statement for statement - agrees with the domain screen over
 * a corpus, and the counterparty reviewer's narrative refuses the needles by name for a raw writer.
 */
@Tag("database")
@DisplayName("kyc V009 - a reviewer's narrative holds no instrument shape (P9-TSK-016)")
class KycReasonScreenDatabaseTest {

    private static final String MIGRATION =
            "db/migration/kyc/V009__counterparty_screening.sql";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** SEC-03's two card spellings and the printed account identifier. */
    private static final List<String> NEEDLES =
            List.of(
                    "outage: card 4111 1111 1111 1111 in the ticket",
                    "outage: card 4111-1111-1111-1111 in the ticket",
                    "pay instead to GB82 WEST 1234 5698 7654 32");

    /** Every person-written column V009 holds - each must gain its CHECK. */
    private static final List<String> COLUMNS = List.of("counterparty_screening.decision_narrative");

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

    @Test
    @DisplayName("the twin masks exactly the Java screen's platform identifiers and scans its runs and"
            + " tokens; every reason column gains its CHECK, and the database lists no other")
    void theMigrationIsTheTwin() throws SQLException {
        String sql = migration();
        assertThat(sql)
                .contains("'" + InstrumentShapes.PLATFORM_IDENTIFIER_REGEX + "'")
                .contains("'" + InstrumentShapes.DIGIT_RUN_REGEX + "'")
                .contains("'" + InstrumentShapes.ALPHANUMERIC_RUN_REGEX + "'");
        for (String column : COLUMNS) {
            String table = column.substring(0, column.indexOf('.'));
            String name = column.replace('.', '_') + "_no_instrument_shape";
            assertThat(sql).contains("ALTER TABLE kyc." + table + "\n    ADD CONSTRAINT " + name + " CHECK (\n"
                    + "        NOT kyc.holds_instrument_shape(" + column.substring(column.indexOf('.') + 1) + "));");
        }
        List<String> reasonColumns = new ArrayList<>();
        try (PreparedStatement select = application.prepareStatement(
                        "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE"
                                + " table_schema = 'kyc' AND table_name LIKE 'counterparty_screening%'"
                                // review_reason is a closed vocabulary, CHECKed against its enum - not prose.
                                + " AND (column_name LIKE '%reason' OR column_name LIKE '%narrative')"
                                + " AND column_name <> 'review_reason' ORDER BY 1");
                ResultSet row = select.executeQuery()) {
            while (row.next()) {
                reasonColumns.add(row.getString(1));
            }
        }
        assertThat(reasonColumns).containsExactlyInAnyOrderElementsOf(COLUMNS);
    }

    @Test
    @DisplayName("the database's verdict is the domain's, half for half, over a corpus of grouped card"
            + " numbers, printed and corrupted account identifiers, platform UUIDs and prose")
    void theTwinAgreesWithTheDomainScreen() throws SQLException {
        List<String> corpus = new ArrayList<>(ShapeCorpus.fixed());
        corpus.addAll(ShapeCorpus.random(new Random(12L), 2500));
        long seed = RANDOM.nextLong();
        corpus.addAll(ShapeCorpus.random(new Random(seed), 1500));
        int cards = 0;
        int accounts = 0;
        try (PreparedStatement verdicts =
                application.prepareStatement(
                        "SELECT u.text, kyc.holds_card_number_shape(u.text),"
                                + " kyc.holds_account_identifier_shape(u.text),"
                                + " kyc.holds_instrument_shape(u.text)"
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

    @Test
    @DisplayName("the narrative refuses a spaced card number, a dashed one and a printed account identifier by"
            + " name, and admits clean prose - for a raw writer")
    void everyReasonColumnRefusesTheNeedles() throws SQLException {
        UUID screening = UUID.randomUUID();
        execute("INSERT INTO kyc.counterparty_screening (id, request_reference, subject_ciphertext, subject_nonce,"
                + " subject_key_version, country, entity_type, payee_verdict, status, review_reason, decision_basis,"
                + " policy_version, decided_at, attempts, requested_at) VALUES (?, ?, ?, ?, 1, 'US', 'INDIVIDUAL',"
                + " 'MATCH', 'IN_REVIEW', 'HIT', 'AUTOMATIC', 'kyc-2026-09', now(), 1, now())",
                screening, "screen-" + screening, new byte[24], new byte[12]);
        for (String needle : NEEDLES) {
            refusedBy("counterparty_screening_decision_narrative_no_instrument_shape",
                    "UPDATE kyc.counterparty_screening SET status = 'BLOCKED', decision_basis = 'REVIEWER',"
                            + " decided_by = 'reviewer-screen', decision_reason_code = 'TRUE_MATCH',"
                            + " decision_narrative = ? WHERE id = ?", needle, screening);
        }
        execute("UPDATE kyc.counterparty_screening SET status = 'RELEASED', decision_basis = 'REVIEWER',"
                + " decided_by = 'reviewer-screen', decision_reason_code = 'FALSE_POSITIVE',"
                + " decision_narrative = 'a different person, born 1971, ticket 4411' WHERE id = ?", screening);
        assertThat(count("SELECT count(*) FROM kyc.counterparty_screening WHERE id = ? AND status = 'RELEASED'", screening))
                .as("clean prose passes: the CHECK refuses the shape, not the text")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------

    /** Refused by exactly the named CHECK (23514), its savepoint rolled back - nothing stored. */
    private static void refusedBy(String constraint, String sql, Object... args) throws SQLException {
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
        try (InputStream in = KycReasonScreenDatabaseTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
