package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * credit {@code V016}'s reason screen (the Phase 10 to 11 transition; {@code INV-AUD-02}): this schema's PL/pgSQL twin of
 * {@code InstrumentShapes} - crossborder {@code V002}'s, statement for statement - agrees with the domain screen over a
 * corpus; every person-written reason column refuses an instrument shape for a raw writer past every domain guard, and
 * the database lists no reason column without its CHECK or a closed vocabulary; the evidence read's definer refuses a
 * reason holding one; and the domain refuses first - a policy and a scorecard proposal with a card number or an
 * account identifier, {@code ReasonRequired}.
 */
@Tag("database")
@DisplayName("credit V016 - person-written reasons hold no instrument shape (the Phase 10 to 11 transition)")
class CreditReasonScreenDatabaseTest {

    private static final String MIGRATION = "db/migration/credit/V016__credit_reasons_hold_no_instrument_shape.sql";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A spaced card number, a dashed one and a printed account identifier. */
    private static final List<String> NEEDLES = List.of(
            "card 4111 1111 1111 1111 in the ticket",
            "card 4111-1111-1111-1111 in the ticket",
            "pay instead to GB82 WEST 1234 5698 7654 32");

    /** Every person-written reason column credit holds - each must carry its CHECK. */
    private static final List<String> COLUMNS = List.of(
            "credit_policy_version.proposal_reason",
            "credit_policy_version.decision_reason",
            "credit_policy_event.reason",
            "scorecard_model_version.proposal_reason",
            "scorecard_model_version.decision_reason",
            "scorecard_model_event.reason",
            "underwriting_case.first_reason",
            "underwriting_case_event.reason",
            "decision_request_event.reason");

    /** The reason columns that are a closed vocabulary, each held to its list by a {@code CHECK ... IN}. */
    private static final List<String> CODED = List.of(
            "decision_request.closure_reason",
            "underwriting_case.closure_reason");

    private static Connection application;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
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
    @DisplayName("the twin masks exactly the Java screen's platform identifiers and scans its runs and tokens; every reason"
            + " column carries its CHECK, and the database lists no other person-written reason column")
    void theMigrationIsTheTwin() throws Exception {
        String sql = migration();
        assertThat(sql)
                .contains("'" + InstrumentShapes.PLATFORM_IDENTIFIER_REGEX + "'")
                .contains("'" + InstrumentShapes.DIGIT_RUN_REGEX + "'")
                .contains("'" + InstrumentShapes.ALPHANUMERIC_RUN_REGEX + "'");
        for (String column : COLUMNS) {
            String table = column.substring(0, column.indexOf('.'));
            String name = column.replace('.', '_') + "_no_instrument_shape";
            assertThat(checksOf(table)).as(column)
                    .contains(name + ": CHECK ((NOT credit.holds_instrument_shape("
                            + column.substring(column.indexOf('.') + 1) + ")))");
        }
        List<String> reasonColumns = new ArrayList<>();
        try (PreparedStatement select = application.prepareStatement(
                        "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE"
                                + " table_schema = 'credit' AND column_name LIKE '%reason' ORDER BY 1");
                ResultSet row = select.executeQuery()) {
            while (row.next()) {
                reasonColumns.add(row.getString(1));
            }
        }
        List<String> expected = new ArrayList<>(COLUMNS);
        expected.addAll(CODED);
        assertThat(reasonColumns).containsExactlyInAnyOrderElementsOf(expected);
        for (String column : CODED) {
            String table = column.substring(0, column.indexOf('.'));
            assertThat(checksOf(table)).as("%s is held to its list", column)
                    .contains(column.substring(column.indexOf('.') + 1) + " = ANY (ARRAY[");
        }
    }

    @Test
    @DisplayName("the database's verdict is the domain's over a corpus of grouped card numbers, printed and corrupted"
            + " account identifiers, platform UUIDs and prose")
    void theTwinAgreesWithTheDomainScreen() throws SQLException {
        List<String> corpus = new ArrayList<>(ShapeCorpus.fixed());
        corpus.addAll(ShapeCorpus.random(new Random(12L), 2500));
        long seed = RANDOM.nextLong();
        corpus.addAll(ShapeCorpus.random(new Random(seed), 1500));
        int cards = 0;
        int accounts = 0;
        try (PreparedStatement verdicts = application.prepareStatement(
                "SELECT u.text, credit.holds_card_number_shape(u.text), credit.holds_account_identifier_shape(u.text),"
                        + " credit.holds_instrument_shape(u.text) FROM unnest(?::text[]) WITH ORDINALITY AS u(text, n)"
                        + " ORDER BY u.n")) {
            Array texts = application.createArrayOf("text", corpus.toArray());
            verdicts.setArray(1, texts);
            try (ResultSet row = verdicts.executeQuery()) {
                while (row.next()) {
                    String text = row.getString(1);
                    boolean card = InstrumentShapes.holdsCardNumber(text);
                    boolean account = InstrumentShapes.holdsAccountIdentifier(text);
                    assertThat(row.getBoolean(2)).as("card verdict for [%s] (seed %d)", text, seed).isEqualTo(card);
                    assertThat(row.getBoolean(3)).as("account verdict for [%s] (seed %d)", text, seed).isEqualTo(account);
                    assertThat(row.getBoolean(4)).isEqualTo(card || account);
                    cards += card ? 1 : 0;
                    accounts += account ? 1 : 0;
                }
            }
        }
        assertThat(cards).as("the corpus exercises the card verdict both ways").isBetween(200, corpus.size() - 200);
        assertThat(accounts).as("the corpus exercises the account verdict both ways").isBetween(200, corpus.size() - 200);
    }

    @Test
    @DisplayName("a raw writer's policy and scorecard history reason holding a needle is refused by name; clean prose is"
            + " admitted")
    void theHistoryColumnsRefuseTheNeedles() throws SQLException {
        String policy = "(SELECT id FROM credit.credit_policy_version ORDER BY version LIMIT 1)";
        String model = "(SELECT id FROM credit.scorecard_model_version ORDER BY version LIMIT 1)";
        for (String needle : NEEDLES) {
            refusedBy("credit_policy_event_reason_no_instrument_shape",
                    "INSERT INTO credit.credit_policy_event (id, policy_version_id, from_status, to_status, actor_id,"
                            + " reason, occurred_at) VALUES (gen_random_uuid(), " + policy + ", NULL, 'PROPOSED', 'op', '"
                            + needle + "', now())");
            refusedBy("scorecard_model_event_reason_no_instrument_shape",
                    "INSERT INTO credit.scorecard_model_event (id, model_version_id, from_status, to_status, actor_id,"
                            + " reason, occurred_at) VALUES (gen_random_uuid(), " + model + ", NULL, 'PROPOSED', 'op', '"
                            + needle + "', now())");
        }
        try (Statement statement = application.createStatement()) {
            statement.execute("INSERT INTO credit.credit_policy_event (id, policy_version_id, from_status, to_status,"
                    + " actor_id, reason, occurred_at) VALUES (gen_random_uuid(), " + policy + ", NULL, 'PROPOSED', 'op',"
                    + " 'clean prose, invoice 2026-10-10, case 01a121cc-7402-7abc-8def-0123456789ab', now())");
        }
    }

    @Test
    @DisplayName("the evidence read's definer refuses a reason holding a needle before anything is read (23514); a clean"
            + " reason reads (nothing, for an unknown evidence id)")
    void theEvidenceReadRefusesTheNeedles() throws SQLException {
        for (String needle : NEEDLES) {
            refusedWith("23514", "SELECT * FROM credit.read_evidence(gen_random_uuid(), '" + needle + "')");
        }
        try (Statement statement = application.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT * FROM credit.read_evidence(gen_random_uuid(), 'a complaint, ticket 4471')")) {
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    @DisplayName("the domain refuses first: a policy proposal and a scorecard proposal whose reason holds a needle are"
            + " ReasonRequired, nothing proposed")
    void theDomainRefusesFirst() throws SQLException {
        long policies = ScorecardFixtures.count("SELECT count(*) FROM credit.credit_policy_version");
        long models = ScorecardFixtures.count("SELECT count(*) FROM credit.scorecard_model_version");
        for (String needle : NEEDLES) {
            assertThatThrownBy(() -> ScorecardFixtures.inOneTransaction(uow -> CreditPolicyFixtures.ADMINISTRATION.propose(
                    uow, CreditPolicyFixtures.policy(CreditProduct.PERSONAL_LOAN, 900), needle,
                    ScorecardFixtures.employee(), ScorecardFixtures.correlation())))
                    .as(needle).isInstanceOf(CreditPolicyAdministration.ReasonRequired.class)
                    .hasMessageContaining("card-number or bank-account");
            assertThatThrownBy(() -> ScorecardFixtures.inOneTransaction(uow -> ScorecardFixtures.ADMINISTRATION.propose(
                    uow, ScorecardFamily.RETAIL_SCORECARD, ScorecardFixtures.table(500), needle,
                    ScorecardFixtures.employee(), ScorecardFixtures.correlation())))
                    .as(needle).isInstanceOf(ScorecardAdministration.ReasonRequired.class)
                    .hasMessageContaining("card-number or bank-account");
        }
        assertThat(ScorecardFixtures.count("SELECT count(*) FROM credit.credit_policy_version")).isEqualTo(policies);
        assertThat(ScorecardFixtures.count("SELECT count(*) FROM credit.scorecard_model_version")).isEqualTo(models);
    }

    // ------------------------------------------------------------------ plumbing

    private void refusedBy(String constraint, String sql) throws SQLException {
        try (Statement savepoint = application.createStatement()) {
            savepoint.execute("SAVEPOINT refused");
        }
        assertThatThrownBy(() -> {
                    try (Statement statement = application.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql).isInstanceOf(SQLException.class)
                .satisfies(failure -> {
                    assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514");
                    assertThat(failure.getMessage()).contains(constraint);
                });
        try (Statement back = application.createStatement()) {
            back.execute("ROLLBACK TO SAVEPOINT refused");
        }
    }

    private void refusedWith(String sqlState, String sql) throws SQLException {
        try (Statement savepoint = application.createStatement()) {
            savepoint.execute("SAVEPOINT refused");
        }
        assertThatThrownBy(() -> {
                    try (Statement statement = application.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql).isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo(sqlState));
        try (Statement back = application.createStatement()) {
            back.execute("ROLLBACK TO SAVEPOINT refused");
        }
    }

    /** {@code table}'s CHECKs as the catalogue holds them, one {@code name: definition} per line. */
    private String checksOf(String table) throws SQLException {
        StringBuilder checks = new StringBuilder();
        try (PreparedStatement select = application.prepareStatement(
                "SELECT c.conname || ': ' || pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_class t"
                        + " ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'credit'"
                        + " AND t.relname = ? AND c.contype = 'c'")) {
            select.setString(1, table);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    checks.append(row.getString(1)).append('\n');
                }
            }
        }
        return checks.toString();
    }

    private static String migration() throws IOException {
        try (InputStream in = CreditReasonScreenDatabaseTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
