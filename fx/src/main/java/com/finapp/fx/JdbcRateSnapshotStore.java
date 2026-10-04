package com.finapp.fx;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain-JDBC storage for reference snapshots (`P9-TSK-005`, ADR-0033).
 *
 * <p>The newer-than-latest rule and {@code received_at} are {@code fx V002}'s trigger's, not this
 * class's: the insert is {@code ON CONFLICT DO NOTHING}, and a row the trigger declines comes back
 * as no row - {@link Recorded#NOT_NEWER}. Every read takes "latest" as the highest
 * {@code observed_at}, and judges freshness on that row's {@code received_at} against
 * {@code statement_timestamp()}.
 */
public final class JdbcRateSnapshotStore implements RateSnapshotStore<Connection> {

    private static final String COLUMNS =
            "id, source, base_currency, quote_currency, rate, observed_at, received_at";

    /** The pair's latest observation, by the source's own observation instant. */
    private static final String LATEST =
            "SELECT " + COLUMNS + " FROM fx.rate_snapshot"
                    + " WHERE source = ? AND base_currency = ? AND quote_currency = ?"
                    + " ORDER BY observed_at DESC LIMIT 1";

    /** PostgreSQL SQLStates the row itself can earn - check and numeric range. */
    private static final String CHECK_VIOLATION = "23514";

    private static final String NUMERIC_OUT_OF_RANGE = "22003";

    @Override
    public Recorded record(
            Connection unitOfWork, String source, RateObservation observation, UUID id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(observation, "observation must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO fx.rate_snapshot (id, source, base_currency,"
                                + " quote_currency, rate, observed_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT (source, base_currency, quote_currency,"
                                + " observed_at) DO NOTHING RETURNING id")) {
            insert.setObject(1, id);
            insert.setString(2, source);
            insert.setString(3, observation.rate().source().code());
            insert.setString(4, observation.rate().destination().code());
            insert.setBigDecimal(5, observation.rate().value());
            insert.setTimestamp(6, Timestamp.from(observation.observedAt()));
            try (ResultSet returned = insert.executeQuery()) {
                return returned.next() ? Recorded.STORED : Recorded.NOT_NEWER;
            }
        } catch (SQLException failure) {
            if (CHECK_VIOLATION.equals(failure.getSQLState())
                    || NUMERIC_OUT_OF_RANGE.equals(failure.getSQLState())) {
                throw new ObservationRefusedException(
                        "the database refused a " + observation.pair() + " observation", failure);
            }
            throw new FxStorageException(
                    DatabaseFailure.describe("recording a reference observation", failure),
                    failure);
        }
    }

    @Override
    public Optional<RateSnapshot> latest(
            Connection unitOfWork, String source, ReferencePair pair) {
        return read(unitOfWork, LATEST, source, pair, Optional.empty());
    }

    @Override
    public Optional<RateSnapshot> freshLatest(
            Connection unitOfWork, String source, ReferencePair pair, Duration maxAge) {
        Objects.requireNonNull(maxAge, "maxAge must not be null");
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("a reference maximum age is positive: " + maxAge);
        }
        // The LATEST row, judged on the database clock - never "some fresh row": a fresher
        // received_at on an older observation cannot exist (the trigger refuses it), and this
        // shape would not consult it if it did.
        return read(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM (" + LATEST + ") latest"
                        + " WHERE received_at > statement_timestamp() - CAST(? AS interval)",
                source,
                pair,
                Optional.of(maxAge));
    }

    @Override
    public Optional<Duration> age(Connection unitOfWork, String source, ReferencePair pair) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        // Whole microseconds, as an integer: no floating point leaves the
                        // database (the NoFloatingPointMoneyRulesTest discipline).
                        "SELECT (EXTRACT(EPOCH FROM statement_timestamp() - received_at)"
                                + " * 1000000)::bigint FROM (" + LATEST + ") latest")) {
            bindPair(select, source, pair);
            try (ResultSet row = select.executeQuery()) {
                return row.next()
                        ? Optional.of(Duration.ofNanos(row.getLong(1) * 1_000L))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new FxStorageException(
                    DatabaseFailure.describe("reading a reference's age", failure), failure);
        }
    }

    private static Optional<RateSnapshot> read(
            Connection unitOfWork,
            String sql,
            String source,
            ReferencePair pair,
            Optional<Duration> maxAge) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            bindPair(select, source, pair);
            if (maxAge.isPresent()) {
                select.setString(4, maxAge.get().toMillis() + " milliseconds");
            }
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new RateSnapshot(
                                row.getObject("id", UUID.class),
                                row.getString("source"),
                                ExchangeRate.of(
                                        CurrencyCode.of(row.getString("base_currency")),
                                        CurrencyCode.of(row.getString("quote_currency")),
                                        // NUMERIC(20,10) reads back at scale 10; stripped so
                                        // the value is the rate, not its column's padding.
                                        row.getBigDecimal("rate").stripTrailingZeros()),
                                row.getTimestamp("observed_at").toInstant(),
                                row.getTimestamp("received_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw new FxStorageException(
                    DatabaseFailure.describe("reading a reference snapshot", failure), failure);
        }
    }

    private static void bindPair(PreparedStatement statement, String source, ReferencePair pair)
            throws SQLException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(pair, "pair must not be null");
        statement.setString(1, source);
        statement.setString(2, pair.base().code());
        statement.setString(3, pair.quote().code());
    }
}
