package com.finapp.platform.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves a monetary value survives a real PostgreSQL round-trip unchanged
 * ({@code INV-MON-05}).
 *
 * <p><strong>Why this cannot be a unit test.</strong> The claim is about the database and its
 * driver: that {@code BIGINT} returns the {@code long} that was written, that {@code CHAR(3)}
 * does not mangle the code, and that {@code SMALLINT} round-trips the scale. Asserting that
 * against an in-memory fake would test the fake.
 *
 * <p><strong>Why it does not skip itself.</strong> A test that quietly skips when the database
 * is absent reports success, and a suite that reports success for work it did not do is worse
 * than one that fails. This is tagged {@code database} and runs only under
 * {@code ./gradlew :platform:databaseTest}, so an absent database means a task visibly did not
 * run rather than a test that silently passed. If the tag is selected and the database is
 * unreachable, the connection failure fails the run.
 *
 * <p>Requires {@code docker compose up -d postgres}. CI runs it in the migrations job, which
 * already has the pinned PostgreSQL up.
 */
@Tag("database")
class MoneyColumnsDatabaseTest {

    private static final MoneyColumns.ColumnNames COLUMNS = MoneyColumns.columnsFor("amount");

    /** PostgreSQL SQLState codes; locale-independent, unlike the messages. */
    private static final String NOT_NULL_VIOLATION = "23502";

    private static final String CHECK_VIOLATION = "23514";

    private static final String STRING_TOO_LONG = "22001";

    private static Connection connection;

    @BeforeAll
    static void connectAndCreateTable() throws SQLException {
        connection =
                DatabaseRoles.bootstrap();
        connection.setAutoCommit(true);

        // A TEMPORARY table, not a migration. Phase 0 creates no tables, and a throwaway
        // fixture has no business becoming part of the schema history; PostgreSQL drops it
        // when the session ends.
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TEMPORARY TABLE money_round_trip (id BIGINT PRIMARY KEY, "
                            + COLUMNS.ddl()
                            + ")");
        }
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("every currency scale round-trips through real columns unchanged")
    void roundTripsEveryCurrencyScale() throws SQLException {
        // 0-, 2-, 3- and 4-minor-unit currencies. The invariant's stated verification method
        // asks for 0, 2 and 3; CLF is included because it exists and would otherwise be the
        // one nobody tried.
        List<Money> amounts =
                List.of(
                        Money.ofMinorUnits(1234L, CurrencyCode.of("USD")),
                        Money.ofMinorUnits(100L, CurrencyCode.of("JPY")),
                        Money.ofMinorUnits(1234L, CurrencyCode.of("BHD")),
                        Money.ofMinorUnits(1L, CurrencyCode.of("CLF")),
                        Money.zero(CurrencyCode.of("EUR")),
                        Money.ofMinorUnits(-5000L, CurrencyCode.of("USD")));

        for (int i = 0; i < amounts.size(); i++) {
            Money original = amounts.get(i);

            write(i, original);

            assertThat(read(i)).as("%s must round-trip unchanged", original).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("the extremes of BIGINT survive without coercion loss")
    void roundTripsTheLimitsOfTheColumn() throws SQLException {
        // If anything on the path went via a floating-point type, these are the values that
        // would come back altered.
        long[] extremes = {Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1L, 9_007_199_254_740_993L};

        for (int i = 0; i < extremes.length; i++) {
            Money original = Money.ofMinorUnits(extremes[i], CurrencyCode.of("USD"));
            int id = 100 + i;

            write(id, original);

            assertThat(read(id).minorUnits())
                    .as("BIGINT must return exactly what was written")
                    .isEqualTo(extremes[i]);
        }
    }

    @Test
    @DisplayName("a historical scale is stored and read back, not re-derived from the currency")
    void storedScaleSurvivesIndependentlyOfCurrentCurrencyData() throws SQLException {
        // The reason scale is a column at all: this row claims USD with three decimals. It
        // must read back as 1.234 even though USD has two decimals today.
        Money historical = Money.ofPersisted(1234L, CurrencyCode.of("USD"), 3);

        write(200, historical);
        Money restored = read(200);

        assertThat(restored.scale()).isEqualTo(3);
        assertThat(restored).isEqualTo(historical);
        assertThat(restored).isNotEqualTo(Money.ofMinorUnits(1234L, CurrencyCode.of("USD")));
    }

    @Test
    @DisplayName("the currency column stores exactly three characters")
    void currencyColumnHoldsTheCodeExactly() throws SQLException {
        write(300, Money.ofMinorUnits(1L, CurrencyCode.of("JPY")));

        try (PreparedStatement select =
                        connection.prepareStatement(
                                "SELECT " + COLUMNS.currency() + " FROM money_round_trip WHERE id = 300");
                ResultSet rows = select.executeQuery()) {
            assertThat(rows.next()).isTrue();
            // CHAR(3) is blank-padded; the mapping is responsible for coping, and does.
            assertThat(rows.getString(1).trim()).isEqualTo("JPY");
        }
    }

    @Test
    @DisplayName("every monetary column rejects null, so a half-written amount cannot exist")
    void columnsAreNotNullable() {
        // Enforced by the schema, not by application code: an amount with no currency must be
        // impossible to write at all, whatever wrote it (INV-MON-02).
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(MoneyColumnsDatabaseTest::insertWithNullCurrency)
                // SQLState, not the message: PostgreSQL messages are localisable, and an
                // assertion that breaks under a different lc_messages is not an assertion.
                .matches(e -> NOT_NULL_VIOLATION.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("the schema rejects a malformed currency, which CHAR(3) alone does not")
    void schemaRejectsMalformedCurrency() {
        // CHAR(3) pads rather than rejects: 'US ' would otherwise be stored happily. The
        // check constraint is what makes INV-MON-02 a schema guarantee rather than a habit.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertRawCurrency(500, "US"))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertRawCurrency(501, "usd"))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));

        // Too long is rejected by the column type itself.
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertRawCurrency(502, "USDD"))
                .matches(e -> STRING_TOO_LONG.equals(e.getSQLState()));
    }

    @Test
    @DisplayName("the schema rejects a scale outside the range Money allows")
    void schemaRejectsImpossibleScale() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertRawScale(600, (short) -1))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));

        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insertRawScale(601, (short) (Money.MAX_SUPPORTED_SCALE + 1)))
                .matches(e -> CHECK_VIOLATION.equals(e.getSQLState()));
    }

    private static void insertRawCurrency(int id, String currency) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(insertSql())) {
            insert.setInt(1, id);
            insert.setLong(2, 100L);
            insert.setString(3, currency);
            insert.setShort(4, (short) 2);
            insert.executeUpdate();
        }
    }

    private static void insertRawScale(int id, short scale) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(insertSql())) {
            insert.setInt(1, id);
            insert.setLong(2, 100L);
            insert.setString(3, "USD");
            insert.setShort(4, scale);
            insert.executeUpdate();
        }
    }

    private static void insertWithNullCurrency() throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO money_round_trip (id, "
                                + COLUMNS.amountMinor()
                                + ", "
                                + COLUMNS.currency()
                                + ", "
                                + COLUMNS.scale()
                                + ") VALUES (400, 100, NULL, 2)")) {
            insert.executeUpdate();
        }
    }

    private static String insertSql() {
        return "INSERT INTO money_round_trip (id, "
                + COLUMNS.amountMinor()
                + ", "
                + COLUMNS.currency()
                + ", "
                + COLUMNS.scale()
                + ") VALUES (?, ?, ?, ?)";
    }

    // -----------------------------------------------------------------

    private static void write(int id, Money money) throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO money_round_trip (id, "
                                + COLUMNS.amountMinor()
                                + ", "
                                + COLUMNS.currency()
                                + ", "
                                + COLUMNS.scale()
                                + ") VALUES (?, ?, ?, ?)")) {
            insert.setInt(1, id);
            insert.setLong(2, MoneyColumns.amountMinorOf(money));
            insert.setString(3, MoneyColumns.currencyOf(money));
            insert.setShort(4, MoneyColumns.scaleOf(money));
            insert.executeUpdate();
        }
    }

    private static Money read(int id) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT "
                                + COLUMNS.amountMinor()
                                + ", "
                                + COLUMNS.currency()
                                + ", "
                                + COLUMNS.scale()
                                + " FROM money_round_trip WHERE id = ?")) {
            select.setInt(1, id);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("row %d must exist", id).isTrue();
                return MoneyColumns.read(rows.getLong(1), rows.getString(2), rows.getShort(3));
            }
        }
    }

}
