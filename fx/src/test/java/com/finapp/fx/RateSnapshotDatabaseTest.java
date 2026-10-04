package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.money.RateColumns;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reference snapshot against a live PostgreSQL (`P9-TSK-005`, ADR-0075 §1, {@code INV-FX-02}):
 * a stale or replayed reference is never read as fresh - for every writer, on the database clock.
 *
 * <p>Each case uses its own source code, so the cases share no pair history and run in any order.
 */
@Tag("database")
@DisplayName("the reference snapshot: newer only, the database's clock, immutable (P9-TSK-005)")
class RateSnapshotDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final ReferencePair EUR_USD = ReferencePair.of("EUR", "USD");
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String RAISED = "P0001";

    private final RateSnapshotStore<Connection> store = new JdbcRateSnapshotStore();

    @Test
    @DisplayName("a newer observation is stored; an equal or older one stores nothing, and latest"
            + " is the newest observed")
    void onlyANewerObservationIsStored() throws SQLException {
        String source = freshSource();
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(30);
        try (Connection app = application()) {
            assertThat(record(app, source, "1.0812264160", t)).isEqualTo(RateSnapshotStore.Recorded.STORED);
            assertThat(record(app, source, "1.0900000000", t))
                    .as("the same observation again - a duplicate")
                    .isEqualTo(RateSnapshotStore.Recorded.NOT_NEWER);
            assertThat(record(app, source, "1.0700000000", t.minusSeconds(10)))
                    .as("an older observation - a replay of a stuck feed")
                    .isEqualTo(RateSnapshotStore.Recorded.NOT_NEWER);
            assertThat(record(app, source, "1.0820000000", t.plusSeconds(5)))
                    .isEqualTo(RateSnapshotStore.Recorded.STORED);
            app.commit();

            RateSnapshot latest = store.latest(app, source, EUR_USD).orElseThrow();
            assertThat(latest.observedAt()).isEqualTo(t.plusSeconds(5));
            assertThat(latest.rate().value()).isEqualByComparingTo("1.082");
            assertThat(rows(app, source)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the rule holds for every writer: a raw-SQL replay stores nothing, and a writer's"
            + " own received_at is overwritten by the database's")
    void theTriggerHoldsForEveryWriter() throws SQLException {
        String source = freshSource();
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(30);
        try (Connection app = application()) {
            record(app, source, "1.0812264160", t);
            app.commit();
            // A raw writer replays an OLDER observation and claims it arrived "now and fresh".
            assertThat(rawInsert(app, source, "EUR", "USD", t.minusSeconds(60),
                            Instant.now().plus(Duration.ofDays(1))))
                    .as("rows inserted").isZero();
            // A raw NEWER observation claiming it arrived long ago - stored, with OUR clock.
            assertThat(rawInsert(app, source, "EUR", "USD", t.plusSeconds(1),
                            Instant.parse("2000-01-01T00:00:00Z")))
                    .isEqualTo(1);
            app.commit();
            Instant databaseNow = databaseNow(app);
            RateSnapshot latest = store.latest(app, source, EUR_USD).orElseThrow();
            assertThat(latest.observedAt()).isEqualTo(t.plusSeconds(1));
            assertThat(latest.receivedAt())
                    .as("received_at is the database's statement_timestamp(), never the writer's")
                    .isBetween(databaseNow.minusSeconds(60), databaseNow);
        }
    }

    @Test
    @DisplayName("freshness is judged on the database clock: the latest is fresh within the age,"
            + " absent past it - the caller fails closed")
    void freshnessIsTheDatabaseClocks() throws Exception {
        String source = freshSource();
        try (Connection app = application()) {
            record(app, source, "1.0812264160", Instant.now().minusSeconds(1));
            app.commit();
            assertThat(store.freshLatest(app, source, EUR_USD, Duration.ofHours(1))).isPresent();
            Thread.sleep(300);
            assertThat(store.freshLatest(app, source, EUR_USD, Duration.ofMillis(100)))
                    .as("received more than 100 ms ago by the database's own clock: stale")
                    .isEmpty();
            assertThat(store.latest(app, source, EUR_USD))
                    .as("the row still exists - staleness is a reading, never a deletion")
                    .isPresent();
            assertThat(store.age(app, source, EUR_USD).orElseThrow())
                    .isGreaterThan(Duration.ofMillis(200));
            assertThat(store.freshLatest(app, freshSource(), EUR_USD, Duration.ofHours(1)))
                    .as("never fetched is never fresh")
                    .isEmpty();
            app.commit();
        }
    }

    @Test
    @DisplayName("a pair outside the declaration, the inverse direction and an observation from the"
            + " future are refused by the database")
    void theDatabaseRefusesWhatTheDeclarationDoesNot() throws SQLException {
        String source = freshSource();
        try (Connection app = application()) {
            assertThatThrownBy(
                            () ->
                                    store.record(
                                            app,
                                            source,
                                            observation("USD", "EUR", "0.9248", Instant.now()),
                                            IDS.next()))
                    .as("the inverse of a canonical pair is not stored - no inversion (ADR-0074)")
                    .isInstanceOf(RateSnapshotStore.ObservationRefusedException.class);
            app.rollback();
            assertThatThrownBy(
                            () ->
                                    store.record(
                                            app,
                                            source,
                                            observation(
                                                    "EUR", "USD", "1.08",
                                                    Instant.now().plus(Duration.ofHours(1))),
                                            IDS.next()))
                    .as("a source clock an hour ahead would freeze the pair")
                    .isInstanceOf(RateSnapshotStore.ObservationRefusedException.class);
            app.rollback();
            assertThat(rows(app, source)).isZero();
            app.rollback();
        }
    }

    @Test
    @DisplayName("a snapshot is never updated or deleted - by the application or the migrator")
    void aSnapshotIsImmutable() throws SQLException {
        String source = freshSource();
        try (Connection app = application(); Connection migrator = DatabaseRoles.migrator()) {
            record(app, source, "1.0812264160", Instant.now().minusSeconds(5));
            app.commit();
            assertThatThrownBy(() -> execute(app, "UPDATE fx.rate_snapshot SET rate = 2"
                            + " WHERE source = '" + source + "'"))
                    .isInstanceOf(SQLException.class)
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(app, "DELETE FROM fx.rate_snapshot"
                            + " WHERE source = '" + source + "'"))
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(migrator, "UPDATE fx.rate_snapshot SET rate = 2"
                            + " WHERE source = '" + source + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            assertThatThrownBy(() -> execute(migrator, "DELETE FROM fx.rate_snapshot"
                            + " WHERE source = '" + source + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            assertThat(rows(app, source)).isEqualTo(1);
            app.rollback();
        }
    }

    @Test
    @DisplayName("the migration's canonical pairs are exactly the declaration's, and the rate column"
            + " is RateColumns.ddl()")
    void theSchemaMatchesTheDeclaration() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            String definition =
                    scalar(
                            migrator,
                            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                    + " WHERE conname = 'rate_snapshot_pair_is_canonical'");
            List<ReferencePair> pairs = new ArrayList<>();
            Matcher pair =
                    Pattern.compile(
                                    "base_currency = '([A-Z]{3})'::bpchar\\) AND \\(quote_currency"
                                            + " = '([A-Z]{3})'::bpchar")
                            .matcher(definition);
            while (pair.find()) {
                pairs.add(ReferencePair.of(pair.group(1), pair.group(2)));
            }
            assertThat(pairs)
                    .as("the CHECK %s", definition)
                    .containsExactlyInAnyOrderElementsOf(ReferenceSourceDeclaration.PAIRS);
            String type =
                    scalar(
                            migrator,
                            "SELECT format_type(atttypid, atttypmod) FROM pg_attribute"
                                    + " WHERE attrelid = 'fx.rate_snapshot'::regclass"
                                    + " AND attname = 'rate'");
            assertThat(type.toUpperCase()).isEqualTo(RateColumns.ddl().toUpperCase());
        }
    }

    // -----------------------------------------------------------------

    private RateSnapshotStore.Recorded record(
            Connection app, String source, String rate, Instant observedAt) {
        return store.record(app, source, observation("EUR", "USD", rate, observedAt), IDS.next());
    }

    private static RateObservation observation(
            String base, String quote, String rate, Instant observedAt) {
        return new RateObservation(
                ExchangeRate.of(CurrencyCode.of(base), CurrencyCode.of(quote), new BigDecimal(rate)),
                observedAt);
    }

    private static int rawInsert(
            Connection app,
            String source,
            String base,
            String quote,
            Instant observedAt,
            Instant claimedReceivedAt)
            throws SQLException {
        try (PreparedStatement insert =
                app.prepareStatement(
                        "INSERT INTO fx.rate_snapshot (id, source, base_currency, quote_currency,"
                                + " rate, observed_at, received_at) VALUES (?, ?, ?, ?, 1.5, ?, ?)")) {
            insert.setObject(1, IDS.next());
            insert.setString(2, source);
            insert.setString(3, base);
            insert.setString(4, quote);
            insert.setTimestamp(5, Timestamp.from(observedAt));
            insert.setTimestamp(6, Timestamp.from(claimedReceivedAt));
            return insert.executeUpdate();
        }
    }

    private static Connection application() throws SQLException {
        Connection app = DatabaseRoles.application();
        app.setAutoCommit(false);
        return app;
    }

    private static String freshSource() {
        return "test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static long rows(Connection connection, String source) throws SQLException {
        return Long.parseLong(
                scalar(connection, "SELECT count(*)::text FROM fx.rate_snapshot WHERE source = '"
                        + source + "'"));
    }

    private static Instant databaseNow(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT statement_timestamp()")) {
            row.next();
            return row.getTimestamp(1).toInstant();
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
