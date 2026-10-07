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

/** {@link FxProvenanceStore} over {@code fx V005}-{@code V007} (`P9-TSK-013`): one read. */
public final class JdbcFxProvenanceStore implements FxProvenanceStore {

    @Override
    public Optional<Provenance> provenance(Connection unitOfWork, FxTradeId tradeId) {
        Objects.requireNonNull(tradeId, "tradeId must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT t.id, t.quote_id, t.purpose, t.status, t.fixed_side, t.source_currency, t.destination_currency,"
                        + " t.source_scale, t.destination_scale, t.customer_source_minor, t.customer_destination_minor,"
                        + " t.position_source_minor, t.position_destination_minor, t.margin_minor, t.spread_margin_minor,"
                        + " t.markup_margin_minor, t.residual_minor, t.customer_rate, t.booked_at, t.journal_entry_id,"
                        + " q.pricing_policy_version_id, q.reference_snapshot_id, q.reference_rate, q.provider_code,"
                        + " q.provider_quote_reference, q.provider_rate, q.provider_value_date, q.internal_rate,"
                        + " q.disclosed_margin, c.id AS cover_id, c.status AS cover_status, c.attempts,"
                        + " e.provider_trade_ref, e.sold_minor, e.sold_scale, e.bought_minor, e.bought_scale,"
                        + " e.executed_rate, e.value_date, e.realised_sold_minor, e.realised_bought_minor,"
                        + " e.executed_off_plan, e.journal_entry_id AS cover_entry_id"
                        + " FROM fx.trade t JOIN fx.quote q ON q.id = t.quote_id"
                        + " LEFT JOIN fx.cover c ON c.quote_id = t.quote_id AND c.kind = 'COVER'"
                        + " LEFT JOIN fx.cover_execution e ON e.cover_id = c.id"
                        + " WHERE t.id = ?")) {
            select.setObject(1, tradeId.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(provenanceOf(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("reading a trade's provenance", failure));
        }
    }

    private static Provenance provenanceOf(ResultSet row) throws SQLException {
        CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
        CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
        int sourceScale = row.getInt("source_scale");
        int destinationScale = row.getInt("destination_scale");
        boolean bySource = "FIXED_SOURCE".equals(row.getString("fixed_side"));
        CurrencyCode computed = bySource ? destination : source;
        int computedScale = bySource ? destinationScale : sourceScale;
        Optional<Cover> cover = Optional.empty();
        UUID coverId = row.getObject("cover_id", UUID.class);
        if (coverId != null) {
            Optional<Execution> execution = Optional.empty();
            if (row.getString("provider_trade_ref") != null) {
                execution = Optional.of(new Execution(
                        row.getString("provider_trade_ref"),
                        Money.ofPersisted(row.getLong("sold_minor"), source, row.getInt("sold_scale")),
                        Money.ofPersisted(row.getLong("bought_minor"), destination, row.getInt("bought_scale")),
                        row.getBigDecimal("executed_rate"),
                        row.getObject("value_date", java.time.LocalDate.class),
                        Money.ofPersisted(row.getLong("realised_sold_minor"), source, sourceScale),
                        Money.ofPersisted(row.getLong("realised_bought_minor"), destination, destinationScale),
                        row.getBoolean("executed_off_plan"),
                        row.getObject("cover_entry_id", UUID.class)));
            }
            cover = Optional.of(new Cover(coverId, CoverStatus.valueOf(row.getString("cover_status")),
                    row.getInt("attempts"), execution));
        }
        return new Provenance(
                FxTradeId.of(row.getObject("id", UUID.class)),
                FxQuoteId.of(row.getObject("quote_id", UUID.class)),
                PricingPurpose.valueOf(row.getString("purpose")),
                TradeStatus.valueOf(row.getString("status")),
                FixedSide.valueOf(row.getString("fixed_side")),
                PricingPolicyId.of(row.getObject("pricing_policy_version_id", UUID.class)),
                row.getObject("reference_snapshot_id", UUID.class),
                row.getBigDecimal("reference_rate"),
                row.getString("provider_code"),
                row.getString("provider_quote_reference"),
                row.getBigDecimal("provider_rate"),
                row.getObject("provider_value_date", java.time.LocalDate.class),
                row.getBigDecimal("internal_rate"),
                row.getBigDecimal("customer_rate"),
                row.getBigDecimal("disclosed_margin"),
                Money.ofPersisted(row.getLong("customer_source_minor"), source, sourceScale),
                Money.ofPersisted(row.getLong("customer_destination_minor"), destination, destinationScale),
                Money.ofPersisted(row.getLong("position_source_minor"), source, sourceScale),
                Money.ofPersisted(row.getLong("position_destination_minor"), destination, destinationScale),
                Money.ofPersisted(row.getLong("margin_minor"), computed, computedScale),
                Money.ofPersisted(row.getLong("spread_margin_minor"), computed, computedScale),
                Money.ofPersisted(row.getLong("markup_margin_minor"), computed, computedScale),
                Money.ofPersisted(row.getLong("residual_minor"), computed, computedScale),
                row.getTimestamp("booked_at").toInstant(),
                row.getObject("journal_entry_id", UUID.class),
                cover);
    }
}
