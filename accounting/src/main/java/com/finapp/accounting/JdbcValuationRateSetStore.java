package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ValuationRateSetStore} over plain JDBC (`P14-TSK-014`, ADR-0033; {@code JdbcAccountingPeriodStore}'s shape):
 * the set and its rates inserted in the caller's transaction, every stamp the database's ({@code accounting V005}'s
 * trigger), the one move a conditional {@code UPDATE} of the decision columns, every failure its operation and SQLSTATE
 * only ({@link AccountingStorageException}). The rate is bound and read as a {@code BigDecimal} - never a
 * floating-point value - so what fx stored is what the set holds. The period's month is read beside the row (a
 * same-schema join).
 */
public final class JdbcValuationRateSetStore implements ValuationRateSetStore {

    private static final String ROW = "SELECT s.id, s.period_id, p.period_start, s.as_of, s.max_age_hours, s.rate_count,"
            + " s.status, s.captured_by, s.captured_at, s.decided_by, s.decided_at FROM accounting.valuation_rate_set s"
            + " JOIN accounting.accounting_period p ON p.id = s.period_id";

    /** A period's sets per status - no bound: the database counts them all. */
    static final String COUNT_BY_STATUS_SQL = "SELECT status, count(*) FROM accounting.valuation_rate_set WHERE period_id = ?"
            + " GROUP BY status";

    @Override
    public void insert(Connection unitOfWork, NewSet set) {
        ValuationRateSet content = set.content();
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO accounting.valuation_rate_set (id,"
                + " period_id, functional_currency, as_of, max_age_hours, rate_count, status, captured_by,"
                + " captured_by_type, captured_at, capture_reason, correlation_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?, ?)")) {
            insert.setObject(1, set.id().value());
            insert.setObject(2, content.period().value());
            insert.setString(3, ValuationRates.FUNCTIONAL.code());
            insert.setTimestamp(4, Timestamp.from(content.asOf()));
            insert.setInt(5, content.staleness().hours());
            insert.setInt(6, content.rates().size());
            insert.setString(7, set.capturedBy().id());
            insert.setString(8, set.capturedBy().type().name());
            insert.setString(9, set.reason().orElse(null));
            insert.setString(10, set.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("capturing a valuation rate set", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO accounting.valuation_rate (set_id,"
                + " position, base_currency, quote_currency, rate, source, snapshot_id, observed_at, received_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int position = 0;
            for (ValuationRate rate : content.rates()) {
                insert.setObject(1, set.id().value());
                insert.setInt(2, ++position);
                insert.setString(3, rate.base().code());
                insert.setString(4, rate.quote().code());
                insert.setBigDecimal(5, rate.rate());
                insert.setString(6, rate.source());
                insert.setObject(7, rate.snapshotId());
                insert.setTimestamp(8, Timestamp.from(rate.observedAt()));
                insert.setTimestamp(9, Timestamp.from(rate.receivedAt()));
                insert.executeUpdate();
            }
        } catch (SQLException failure) {
            throw failed("capturing a valuation rate", failure);
        }
    }

    @Override
    public Optional<SetRow> find(Connection unitOfWork, ValuationRateSetId id) {
        return byId(unitOfWork, ROW + " WHERE s.id = ?", id, "reading a valuation rate set");
    }

    @Override
    public Optional<SetRow> lock(Connection unitOfWork, ValuationRateSetId id) {
        return byId(unitOfWork, ROW + " WHERE s.id = ? FOR UPDATE OF s", id, "locking a valuation rate set");
    }

    @Override
    public Optional<SetRow> approvedFor(Connection unitOfWork, AccountingPeriodId period) {
        return byId(unitOfWork, ROW + " WHERE s.period_id = ? AND s.status = 'APPROVED'", period,
                "reading a period's approved valuation rate set");
    }

    @Override
    public List<SetRow> ofPeriod(Connection unitOfWork, AccountingPeriodId period, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ROW + " WHERE s.period_id = ? ORDER BY s.id LIMIT ?")) {
            read.setObject(1, period.value());
            read.setInt(2, limit);
            List<SetRow> sets = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    sets.add(row(row));
                }
            }
            return sets;
        } catch (SQLException failure) {
            throw failed("reading a period's valuation rate sets", failure);
        }
    }

    /** Newest first: the id is a UUIDv7, so its order is its birth's. */
    static final String PROPOSED_OF_SQL = ROW + " WHERE s.period_id = ? AND s.status = 'PROPOSED' ORDER BY s.id DESC LIMIT ?";

    @Override
    public List<SetRow> proposedOf(Connection unitOfWork, AccountingPeriodId period, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(PROPOSED_OF_SQL)) {
            read.setObject(1, period.value());
            read.setInt(2, limit);
            List<SetRow> sets = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    sets.add(row(row));
                }
            }
            return sets;
        } catch (SQLException failure) {
            throw failed("reading a period's proposed valuation rate sets", failure);
        }
    }

    @Override
    public Map<ValuationRateSetStatus, Long> countByStatus(Connection unitOfWork, AccountingPeriodId period) {
        try (PreparedStatement read = unitOfWork.prepareStatement(COUNT_BY_STATUS_SQL)) {
            read.setObject(1, period.value());
            Map<ValuationRateSetStatus, Long> counts = new EnumMap<>(ValuationRateSetStatus.class);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    counts.put(ValuationRateSetStatus.valueOf(row.getString(1)), row.getLong(2));
                }
            }
            return counts;
        } catch (SQLException failure) {
            throw failed("counting a period's valuation rate sets", failure);
        }
    }

    @Override
    public List<ValuationRate> rates(Connection unitOfWork, ValuationRateSetId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement("SELECT base_currency, quote_currency, rate, source,"
                + " snapshot_id, observed_at, received_at FROM accounting.valuation_rate WHERE set_id = ?"
                + " ORDER BY position")) {
            read.setObject(1, id.value());
            List<ValuationRate> rates = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rates.add(new ValuationRate(
                            CurrencyCode.of(row.getString("base_currency")),
                            CurrencyCode.of(row.getString("quote_currency")),
                            row.getBigDecimal("rate"),
                            row.getString("source"),
                            row.getObject("snapshot_id", UUID.class),
                            row.getTimestamp("observed_at").toInstant(),
                            row.getTimestamp("received_at").toInstant()));
                }
            }
            return rates;
        } catch (SQLException failure) {
            throw failed("reading a valuation rate set's rates", failure);
        }
    }

    @Override
    public boolean decide(
            Connection unitOfWork, ValuationRateSetId id, ValuationRateSetStatus to, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE accounting.valuation_rate_set"
                + " SET status = ?, decided_by = ?, decided_by_type = ?, decided_at = clock_timestamp(),"
                + " decision_reason = ? WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, to.name());
            update.setString(2, actor.id());
            update.setString(3, actor.type().name());
            update.setString(4, reason);
            update.setObject(5, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("deciding a valuation rate set", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<SetRow> byId(Connection unitOfWork, String sql, EntityId id, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(row(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static SetRow row(ResultSet row) throws SQLException {
        Timestamp decidedAt = row.getTimestamp("decided_at");
        return new SetRow(
                ValuationRateSetId.of(row.getObject("id", UUID.class)),
                AccountingPeriodId.of(row.getObject("period_id", UUID.class)),
                YearMonth.from(row.getDate("period_start").toLocalDate()),
                row.getTimestamp("as_of").toInstant(),
                row.getInt("max_age_hours"),
                row.getInt("rate_count"),
                ValuationRateSetStatus.valueOf(row.getString("status")),
                row.getString("captured_by"),
                row.getTimestamp("captured_at").toInstant(),
                Optional.ofNullable(row.getString("decided_by")),
                decidedAt == null ? Optional.<Instant>empty() : Optional.of(decidedAt.toInstant()));
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
