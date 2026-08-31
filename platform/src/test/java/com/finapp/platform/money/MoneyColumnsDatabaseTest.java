package com.finapp.platform.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.DriverManager;
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

    private static Connection connection;

    @BeforeAll
    static void connectAndCreateTable() throws SQLException {
        connection =
                DriverManager.getConnection(
                        requiredProperty("finapp.db.url"),
                        requiredProperty("finapp.db.user"),
                        requiredProperty("finapp.db.password"));
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
                .isThrownBy(() -> insertWithNullCurrency())
                .withMessageContaining("null");
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

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "System property " + name + " is not set. Run this through "
                            + "'./gradlew :platform:databaseTest', which supplies it.");
        }
        return value;
    }
}
