package com.finapp.fx;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CoverStore} over {@code fx V006}/{@code V007} (`P9-TSK-012`). The database stamps every
 * permit and the execution fact; this class takes no clock.
 */
public final class JdbcCoverStore implements CoverStore {

    /** The backoff's ceiling: a refused requote waits at most {@code 2^6} base intervals. */
    static final int MAX_BACKOFF_DOUBLINGS = 6;

    private static final String COLUMNS =
            "c.id, c.quote_id, c.kind, c.status, c.provider_code, c.source_currency, c.destination_currency,"
                    + " c.fixed_side, c.fixed_amount_minor, c.fixed_scale, c.attempts, c.last_dispatched_at,"
                    + " c.requote_failures, c.caused_by_event_id, c.created_at, c.correlation_id";

    private static final String RETURNING =
            " RETURNING id, quote_id, kind, status, provider_code, source_currency, destination_currency,"
                    + " fixed_side, fixed_amount_minor, fixed_scale, attempts, last_dispatched_at,"
                    + " requote_failures, caused_by_event_id, created_at, correlation_id";

    @Override
    public List<CoverRow> claimDue(Connection unitOfWork, Duration resendAfter, Duration requoteBase, int limit) {
        Objects.requireNonNull(resendAfter, "resendAfter must not be null");
        Objects.requireNonNull(requoteBase, "requoteBase must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE fx.cover SET last_dispatched_at = statement_timestamp()"
                        + " WHERE id IN (SELECT id FROM fx.cover"
                        + " WHERE (status IN ('DISPATCHED', 'UNKNOWN')"
                        + " AND last_dispatched_at <= statement_timestamp() - ? * INTERVAL '1 millisecond')"
                        + " OR (status = 'REJECTED' AND last_dispatched_at <= statement_timestamp()"
                        + " - ? * (1 << least(requote_failures, " + MAX_BACKOFF_DOUBLINGS + ")) * INTERVAL '1 millisecond')"
                        + " ORDER BY last_dispatched_at LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + RETURNING)) {
            claim.setLong(1, resendAfter.toMillis());
            claim.setLong(2, requoteBase.toMillis());
            claim.setInt(3, limit);
            return readAll(claim);
        } catch (SQLException failure) {
            throw failure("claiming the due covers", failure);
        }
    }

    @Override
    public Optional<CoverRow> claim(Connection unitOfWork, UUID coverId) {
        Objects.requireNonNull(coverId, "coverId must not be null");
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE fx.cover SET last_dispatched_at = statement_timestamp()"
                        + " WHERE id = ? AND status IN ('DISPATCHED', 'UNKNOWN', 'REJECTED')" + RETURNING)) {
            claim.setObject(1, coverId);
            List<CoverRow> rows = readAll(claim);
            return rows.stream().findFirst();
        } catch (SQLException failure) {
            throw failure("claiming a cover's permit", failure);
        }
    }

    @Override
    public Optional<CoverRow> find(Connection unitOfWork, UUID coverId) {
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM fx.cover c WHERE c.id = ?", coverId, "reading a cover");
    }

    @Override
    public Optional<CoverRow> lock(Connection unitOfWork, UUID coverId) {
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM fx.cover c WHERE c.id = ? FOR UPDATE", coverId, "locking a cover");
    }

    @Override
    public Wanted lockWanted(Connection unitOfWork, FxQuoteId quoteId) {
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        try (PreparedStatement quote = unitOfWork.prepareStatement(
                        "SELECT status FROM fx.quote WHERE id = ? FOR SHARE");
                PreparedStatement trade = unitOfWork.prepareStatement(
                        "SELECT status FROM fx.trade WHERE quote_id = ? FOR SHARE")) {
            quote.setObject(1, quoteId.value());
            QuoteStatus quoteStatus;
            try (ResultSet row = quote.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("a cover's quote exists by its foreign key");
                }
                quoteStatus = QuoteStatus.valueOf(row.getString("status"));
            }
            trade.setObject(1, quoteId.value());
            try (ResultSet row = trade.executeQuery()) {
                return new Wanted(quoteStatus,
                        row.next() ? Optional.of(TradeStatus.valueOf(row.getString("status"))) : Optional.empty());
            }
        } catch (SQLException failure) {
            throw failure("reading whether a quote wants its cover", failure);
        }
    }

    @Override
    public Optional<AttemptRow> attempt(Connection unitOfWork, UUID coverId, int attempt) {
        Objects.requireNonNull(coverId, "coverId must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT cover_id, attempt, client_reference, provider_quote_ref FROM fx.cover_attempt"
                        + " WHERE cover_id = ? AND attempt = ?")) {
            select.setObject(1, coverId);
            select.setInt(2, attempt);
            return attemptOf(select);
        } catch (SQLException failure) {
            throw failure("reading a cover attempt", failure);
        }
    }

    @Override
    public Optional<AttemptRow> attemptByReference(Connection unitOfWork, String clientReference) {
        Objects.requireNonNull(clientReference, "clientReference must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT cover_id, attempt, client_reference, provider_quote_ref FROM fx.cover_attempt"
                        + " WHERE client_reference = ?")) {
            select.setString(1, clientReference);
            return attemptOf(select);
        } catch (SQLException failure) {
            throw failure("reading a cover attempt by its reference", failure);
        }
    }

    @Override
    public boolean transition(Connection unitOfWork, UUID coverId, int attempt, CoverStatus from, CoverStatus to) {
        Objects.requireNonNull(coverId, "coverId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE fx.cover SET status = ? WHERE id = ? AND status = ? AND attempts = ?")) {
            update.setString(1, to.name());
            update.setObject(2, coverId);
            update.setString(3, from.name());
            update.setInt(4, attempt);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failure("moving a cover along its machine", failure);
        }
    }

    @Override
    public boolean requote(
            Connection unitOfWork, UUID coverId, int fromAttempt, String clientReference, String providerQuoteReference) {
        Objects.requireNonNull(coverId, "coverId must not be null");
        Objects.requireNonNull(clientReference, "clientReference must not be null");
        Objects.requireNonNull(providerQuoteReference, "providerQuoteReference must not be null");
        try (PreparedStatement attempt = unitOfWork.prepareStatement(
                        "INSERT INTO fx.cover_attempt (cover_id, attempt, client_reference, provider_quote_ref)"
                                + " VALUES (?, ?, ?, ?) ON CONFLICT (cover_id, attempt) DO NOTHING");
                PreparedStatement update = unitOfWork.prepareStatement(
                        "UPDATE fx.cover SET status = 'DISPATCHED', attempts = attempts + 1, requote_failures = 0,"
                                + " last_dispatched_at = statement_timestamp()"
                                + " WHERE id = ? AND status = 'REJECTED' AND attempts = ?")) {
            attempt.setObject(1, coverId);
            attempt.setInt(2, fromAttempt + 1);
            attempt.setString(3, clientReference);
            attempt.setString(4, providerQuoteReference);
            if (attempt.executeUpdate() != 1) {
                return false;
            }
            update.setObject(1, coverId);
            update.setInt(2, fromAttempt);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("a requote's new attempt was stored for a cover that did not advance");
            }
            return true;
        } catch (SQLException failure) {
            throw failure("requoting a cover", failure);
        }
    }

    @Override
    public boolean recordRequoteFailure(Connection unitOfWork, UUID coverId, int attempt) {
        Objects.requireNonNull(coverId, "coverId must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE fx.cover SET requote_failures = requote_failures + 1"
                        + " WHERE id = ? AND status = 'REJECTED' AND attempts = ?")) {
            update.setObject(1, coverId);
            update.setInt(2, attempt);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failure("recording a refused requote", failure);
        }
    }

    @Override
    public Recorded insertExecution(Connection unitOfWork, ExecutionDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        CoverLines.Realised realised = CoverLines.realised(draft.plan(), new CoverLines.Execution(draft.sold(), draft.bought()));
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO fx.cover_execution (cover_id, attempt, client_reference, provider_code,"
                        + " provider_trade_ref, fixed_side, sold_currency, sold_minor, sold_scale, bought_currency,"
                        + " bought_minor, bought_scale, executed_rate, value_date, plan_sold_minor, plan_bought_minor,"
                        + " realised_sold_minor, realised_bought_minor, executed_off_plan, recorded_on, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_DATE, ?)"
                        + " RETURNING recorded_at, recorded_on")) {
            int i = 1;
            insert.setObject(i++, draft.coverId());
            insert.setInt(i++, draft.attempt());
            insert.setString(i++, draft.clientReference());
            insert.setString(i++, draft.providerCode());
            insert.setString(i++, draft.providerTradeReference());
            insert.setString(i++, draft.fixedSide().name());
            insert.setString(i++, draft.sold().currency().code());
            insert.setLong(i++, draft.sold().minorUnits());
            insert.setInt(i++, draft.sold().scale());
            insert.setString(i++, draft.bought().currency().code());
            insert.setLong(i++, draft.bought().minorUnits());
            insert.setInt(i++, draft.bought().scale());
            insert.setBigDecimal(i++, draft.executedRate().value());
            insert.setObject(i++, draft.valueDate());
            insert.setLong(i++, draft.plan().sold().minorUnits());
            insert.setLong(i++, draft.plan().bought().minorUnits());
            insert.setLong(i++, realised.soldMinor());
            insert.setLong(i++, realised.boughtMinor());
            insert.setBoolean(i++, realised.offPlan());
            insert.setString(i, draft.correlationId());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return new Recorded(
                        row.getTimestamp("recorded_at").toInstant(),
                        row.getObject("recorded_on", java.time.LocalDate.class));
            }
        } catch (SQLException failure) {
            throw failure("recording a cover's execution", failure);
        }
    }

    @Override
    public void attachExecutionEntry(Connection unitOfWork, UUID coverId, UUID journalEntryId) {
        Objects.requireNonNull(coverId, "coverId must not be null");
        Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE fx.cover_execution SET journal_entry_id = ? WHERE cover_id = ? AND journal_entry_id IS NULL")) {
            update.setObject(1, journalEntryId);
            update.setObject(2, coverId);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("a cover execution's entry is attached once, in its own transaction");
            }
        } catch (SQLException failure) {
            throw failure("attaching a cover's entry", failure);
        }
    }

    @Override
    public UnknownBoard unknownBoard(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                        "SELECT count(*) AS active, extract(epoch FROM statement_timestamp() - min(last_dispatched_at)) AS age"
                                + " FROM fx.cover WHERE status = 'UNKNOWN'");
                ResultSet row = select.executeQuery()) {
            row.next();
            return new UnknownBoard(row.getLong("active"), seconds(row, "age"));
        } catch (SQLException failure) {
            throw failure("reading the unknown covers", failure);
        }
    }

    @Override
    public Optional<Duration> oldestOpenAge(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                        "SELECT extract(epoch FROM statement_timestamp() - min(created_at)) AS age FROM fx.cover"
                                + " WHERE status IN ('DISPATCHED', 'UNKNOWN', 'REJECTED')");
                ResultSet row = select.executeQuery()) {
            row.next();
            return seconds(row, "age");
        } catch (SQLException failure) {
            throw failure("reading the oldest open cover", failure);
        }
    }

    // -----------------------------------------------------------------

    private static Optional<CoverRow> one(Connection unitOfWork, String sql, UUID id, String operation) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, id);
            return readAll(select).stream().findFirst();
        } catch (SQLException failure) {
            throw failure(operation, failure);
        }
    }

    private static List<CoverRow> readAll(PreparedStatement statement) throws SQLException {
        List<CoverRow> rows = new ArrayList<>();
        try (ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                rows.add(row(row));
            }
        }
        return rows;
    }

    private static CoverRow row(ResultSet row) throws SQLException {
        FixedSide fixedSide = FixedSide.valueOf(row.getString("fixed_side"));
        CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
        CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
        Timestamp created = row.getTimestamp("created_at");
        return new CoverRow(
                row.getObject("id", UUID.class),
                FxQuoteId.of(row.getObject("quote_id", UUID.class)),
                CoverKind.valueOf(row.getString("kind")),
                CoverStatus.valueOf(row.getString("status")),
                row.getString("provider_code"),
                source,
                destination,
                fixedSide,
                Money.ofPersisted(row.getLong("fixed_amount_minor"),
                        fixedSide == FixedSide.FIXED_SOURCE ? source : destination, row.getInt("fixed_scale")),
                row.getInt("attempts"),
                row.getTimestamp("last_dispatched_at").toInstant(),
                row.getInt("requote_failures"),
                row.getObject("caused_by_event_id", UUID.class),
                created.toInstant(),
                row.getString("correlation_id"));
    }

    private static Optional<AttemptRow> attemptOf(PreparedStatement select) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            return row.next()
                    ? Optional.of(new AttemptRow(row.getObject("cover_id", UUID.class), row.getInt("attempt"),
                            row.getString("client_reference"), row.getString("provider_quote_ref")))
                    : Optional.empty();
        }
    }

    private static Optional<Duration> seconds(ResultSet row, String column) throws SQLException {
        java.math.BigDecimal seconds = row.getBigDecimal(column);
        return seconds == null
                ? Optional.empty()
                : Optional.of(Duration.ofNanos(seconds.movePointRight(9).longValue()));
    }

    private static FxStorageException failure(String operation, SQLException failure) {
        return new FxStorageException(DatabaseFailure.describe(operation, failure), failure);
    }
}
