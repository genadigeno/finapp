package com.finapp.fx;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link TradeStore} over {@code fx V006} (`P9-TSK-009`). The database stamps the booking and
 * the cover's first permit; this class takes no clock.
 */
public final class JdbcTradeStore implements TradeStore {

    @Override
    public Booked insert(Connection unitOfWork, FxTradeId id, QuoteStore.PlanRow plan, String correlationId) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO fx.trade (id, quote_id, owner_party_id, purpose, source_currency, destination_currency,"
                        + " fixed_side, pricing_policy_version_id, provider_code, customer_rate, executed_rate,"
                        + " source_scale, destination_scale, customer_source_minor, customer_destination_minor,"
                        + " position_source_minor, position_destination_minor, margin_minor, spread_margin_minor,"
                        + " markup_margin_minor, residual_minor, status, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BOOKED', ?)"
                        + " RETURNING booked_at, booked_on")) {
            int i = 1;
            insert.setObject(i++, id.value());
            insert.setObject(i++, plan.id().value());
            insert.setObject(i++, plan.owner());
            insert.setString(i++, plan.purpose().name());
            insert.setString(i++, plan.source().code());
            insert.setString(i++, plan.destination().code());
            insert.setString(i++, plan.fixedSide().name());
            insert.setObject(i++, plan.version().value());
            insert.setString(i++, plan.providerCode());
            insert.setBigDecimal(i++, plan.customerRate());
            insert.setBigDecimal(i++, plan.customerRate());
            insert.setInt(i++, plan.sourceScale());
            insert.setInt(i++, plan.destinationScale());
            insert.setLong(i++, plan.customerSourceMinor());
            insert.setLong(i++, plan.customerDestinationMinor());
            insert.setLong(i++, plan.positionSourceMinor());
            insert.setLong(i++, plan.positionDestinationMinor());
            insert.setLong(i++, plan.marginMinor());
            insert.setLong(i++, plan.spreadMarginMinor());
            insert.setLong(i++, plan.markupMarginMinor());
            insert.setLong(i++, plan.residualMinor());
            insert.setString(i, correlationId);
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return new Booked(row.getTimestamp(1).toInstant(), row.getDate(2).toLocalDate());
            }
        } catch (SQLException failure) {
            throw failure("booking a trade", failure);
        }
    }

    @Override
    public void attachEntry(Connection unitOfWork, FxTradeId id, UUID journalEntryId) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE fx.trade SET journal_entry_id = ? WHERE id = ? AND journal_entry_id IS NULL")) {
            update.setObject(1, journalEntryId);
            update.setObject(2, id.value());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("the trade's entry was already attached - the booking ran twice");
            }
        } catch (SQLException failure) {
            throw failure("attaching a trade's entry", failure);
        }
    }

    @Override
    public Optional<TradeRow> findOwned(Connection unitOfWork, FxTradeId id, UUID owner) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, quote_id, owner_party_id, purpose, fixed_side, status, source_currency, destination_currency,"
                        + " source_scale, destination_scale, customer_source_minor, customer_destination_minor,"
                        + " executed_rate, booked_at, booked_on, journal_entry_id"
                        + " FROM fx.trade WHERE id = ? AND owner_party_id = ?")) {
            select.setObject(1, id.value());
            select.setObject(2, owner);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
                CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
                return Optional.of(new TradeRow(
                        FxTradeId.of(row.getObject("id", UUID.class)),
                        FxQuoteId.of(row.getObject("quote_id", UUID.class)),
                        row.getObject("owner_party_id", UUID.class),
                        PricingPurpose.valueOf(row.getString("purpose")),
                        FixedSide.valueOf(row.getString("fixed_side")),
                        TradeStatus.valueOf(row.getString("status")),
                        Money.ofPersisted(row.getLong("customer_source_minor"), source, row.getInt("source_scale")),
                        Money.ofPersisted(row.getLong("customer_destination_minor"), destination,
                                row.getInt("destination_scale")),
                        row.getBigDecimal("executed_rate").stripTrailingZeros(),
                        row.getTimestamp("booked_at").toInstant(),
                        row.getDate("booked_on").toLocalDate(),
                        row.getObject("journal_entry_id", UUID.class)));
            }
        } catch (SQLException failure) {
            throw failure("reading a trade", failure);
        }
    }

    @Override
    public Optional<CoverByReference> coverByClientReference(Connection unitOfWork, String clientReference) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(clientReference, "clientReference must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT c.id, c.status FROM fx.cover_attempt a JOIN fx.cover c ON c.id = a.cover_id"
                        + " WHERE a.client_reference = ?")) {
            select.setString(1, clientReference);
            try (ResultSet row = select.executeQuery()) {
                return row.next()
                        ? Optional.of(new CoverByReference(
                                row.getObject("id", UUID.class), CoverStatus.valueOf(row.getString("status"))))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(DatabaseFailure.describe("reading a cover by its reference", failure), failure);
        }
    }

    @Override
    public void insertCover(Connection unitOfWork, CoverDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        try (PreparedStatement cover = unitOfWork.prepareStatement(
                        "INSERT INTO fx.cover (id, quote_id, kind, status, provider_code, source_currency,"
                                + " destination_currency, fixed_side, fixed_amount_minor, fixed_scale, attempts,"
                                + " last_dispatched_at, correlation_id)"
                                + " VALUES (?, ?, ?, 'DISPATCHED', ?, ?, ?, ?, ?, ?, 1, statement_timestamp(), ?)");
                PreparedStatement attempt = unitOfWork.prepareStatement(
                        "INSERT INTO fx.cover_attempt (cover_id, attempt, client_reference, provider_quote_ref)"
                                + " VALUES (?, 1, ?, ?)")) {
            int i = 1;
            cover.setObject(i++, draft.id());
            cover.setObject(i++, draft.quoteId().value());
            cover.setString(i++, draft.kind().name());
            cover.setString(i++, draft.providerCode());
            cover.setString(i++, draft.source().code());
            cover.setString(i++, draft.destination().code());
            cover.setString(i++, draft.fixedSide().name());
            cover.setLong(i++, draft.fixedAmount().minorUnits());
            cover.setInt(i++, draft.fixedAmount().scale());
            cover.setString(i, draft.correlationId());
            cover.executeUpdate();
            attempt.setObject(1, draft.id());
            attempt.setString(2, draft.clientReference());
            attempt.setString(3, draft.providerQuoteReference());
            attempt.executeUpdate();
        } catch (SQLException failure) {
            throw failure("recording the cover a trade wants", failure);
        }
    }

    private static FxStorageException failure(String operation, SQLException failure) {
        return new FxStorageException(DatabaseFailure.describe(operation, failure), failure);
    }
}
