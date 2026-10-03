package com.finapp.platform.money;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.math.BigDecimal;
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
 * {@code RateColumns.ddl()} against real PostgreSQL (`P9-TSK-002`): every rate the domain admits
 * round-trips unchanged - and a value past the scale is ROUNDED by the column, silently, which is
 * the whole reason {@code ExchangeRate} refuses one before it ever reaches a column.
 */
@Tag("database")
@DisplayName("a rate column holds every admitted rate exactly, and would round an over-scaled one")
class RateColumnsDatabaseTest {

    private static Connection connection;

    @BeforeAll
    static void connectAndCreateTable() throws SQLException {
        connection = DatabaseRoles.bootstrap();
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE rate_round_trip (id INT PRIMARY KEY, value "
                    + RateColumns.ddl() + " NOT NULL)");
        }
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("the widest, the smallest and the market-shaped rates round-trip unchanged")
    void everyAdmittedRateRoundTrips() throws SQLException {
        List<String> rates = List.of(
                "1234567890.0000000001", // twenty digits, ten of them decimals
                "0.0000000001", // the smallest positive rate
                "1.0850240000", "149.8742000000", "0.0025123456", "397.5121");
        int id = 0;
        for (String rate : rates) {
            BigDecimal stored = roundTrip(++id, new BigDecimal(rate));
            assertThat(stored).as(rate).isEqualByComparingTo(rate);
        }
    }

    @Test
    @DisplayName("an eleventh decimal is rounded by the column without a word - why the domain"
            + " refuses it")
    void theColumnRoundsSilently() throws SQLException {
        assertThat(roundTrip(100, new BigDecimal("1.08502400005")))
                .as("PostgreSQL accepted it and changed the price")
                .isEqualByComparingTo("1.0850240001");
    }

    private static BigDecimal roundTrip(int id, BigDecimal value) throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT INTO rate_round_trip VALUES (?, ?)")) {
            insert.setInt(1, id);
            insert.setBigDecimal(2, value);
            insert.executeUpdate();
        }
        try (PreparedStatement read =
                        connection.prepareStatement("SELECT value FROM rate_round_trip WHERE id = ?")) {
            read.setInt(1, id);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getBigDecimal(1);
            }
        }
    }
}
