package com.finapp.fx;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link FxProofStore} over {@code fx V005}-{@code V009} (`P9-TSK-013`): reads only. Since `P9-TSK-025` the trades'
 * terms count only {@code BOOKED} trades - a {@code REVERSED} one's exact mirror cancels its lines in the ledger, which
 * is the plan's "- reversal" term.
 */
public final class JdbcFxProofStore implements FxProofStore {

    @Override
    public Expected expected(Connection unitOfWork) {
        Map<String, Long> position = sums(unitOfWork,
                "SELECT currency, SUM(amount) FROM ("
                        + " SELECT source_currency AS currency, -position_source_minor AS amount FROM fx.trade WHERE status = 'BOOKED'"
                        + " UNION ALL SELECT destination_currency, position_destination_minor FROM fx.trade WHERE status = 'BOOKED'"
                        + " UNION ALL SELECT sold_currency, plan_sold_minor FROM fx.cover_execution"
                        + " UNION ALL SELECT bought_currency, -plan_bought_minor FROM fx.cover_execution"
                        + ") legs GROUP BY currency");
        Map<String, Long> margin = sums(unitOfWork,
                "SELECT CASE fixed_side WHEN 'FIXED_SOURCE' THEN destination_currency ELSE source_currency END,"
                        + " SUM(margin_minor) FROM fx.trade WHERE status = 'BOOKED' GROUP BY 1");
        Map<String, Long> residual = sums(unitOfWork,
                "SELECT CASE fixed_side WHEN 'FIXED_SOURCE' THEN destination_currency ELSE source_currency END,"
                        + " -SUM(residual_minor) FROM fx.trade WHERE status = 'BOOKED' GROUP BY 1");
        Map<String, Long> gains = sums(unitOfWork,
                "SELECT currency, SUM(amount) FROM ("
                        + " SELECT sold_currency AS currency, GREATEST(realised_sold_minor, 0) AS amount FROM fx.cover_execution"
                        + " UNION ALL SELECT bought_currency, GREATEST(realised_bought_minor, 0) FROM fx.cover_execution"
                        + ") r GROUP BY currency");
        Map<String, Long> losses = sums(unitOfWork,
                "SELECT currency, SUM(amount) FROM ("
                        + " SELECT sold_currency AS currency, GREATEST(-realised_sold_minor, 0) AS amount FROM fx.cover_execution"
                        + " UNION ALL SELECT bought_currency, GREATEST(-realised_bought_minor, 0) FROM fx.cover_execution"
                        + ") r GROUP BY currency");
        return new Expected(position, margin, residual, gains, losses);
    }

    @Override
    public List<ReplayRow> replayRows(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                        "SELECT t.id, t.journal_entry_id, q.id AS quote_id, q.pricing_policy_version_id, q.fixed_side,"
                                + " q.source_currency, q.destination_currency, q.source_scale, q.destination_scale,"
                                + " q.provider_rate, q.provider_valid_for_ms, q.reference_rate, q.customer_rate,"
                                + " q.internal_rate, q.disclosed_margin, q.spread, q.markup, q.rate_scale, q.rate_rounding,"
                                + " q.amount_rounding, q.margin_rounding, t.customer_source_minor,"
                                + " t.customer_destination_minor, t.position_source_minor, t.position_destination_minor,"
                                + " t.margin_minor, t.spread_margin_minor, t.markup_margin_minor, t.residual_minor"
                                + " FROM fx.trade t JOIN fx.quote q ON q.id = t.quote_id ORDER BY t.booked_at, t.id");
                ResultSet row = select.executeQuery()) {
            List<ReplayRow> rows = new ArrayList<>();
            while (row.next()) {
                rows.add(new ReplayRow(
                        FxTradeId.of(row.getObject("id", UUID.class)),
                        row.getObject("journal_entry_id", UUID.class),
                        FxQuoteId.of(row.getObject("quote_id", UUID.class)),
                        PricingPolicyId.of(row.getObject("pricing_policy_version_id", UUID.class)),
                        FixedSide.valueOf(row.getString("fixed_side")),
                        row.getString("source_currency"),
                        row.getString("destination_currency"),
                        row.getInt("source_scale"),
                        row.getInt("destination_scale"),
                        row.getBigDecimal("provider_rate"),
                        row.getLong("provider_valid_for_ms"),
                        row.getBigDecimal("reference_rate"),
                        row.getBigDecimal("customer_rate"),
                        row.getBigDecimal("internal_rate"),
                        row.getBigDecimal("disclosed_margin"),
                        row.getBigDecimal("spread"),
                        row.getBigDecimal("markup"),
                        row.getInt("rate_scale"),
                        row.getString("rate_rounding"),
                        row.getString("amount_rounding"),
                        row.getString("margin_rounding"),
                        row.getLong("customer_source_minor"),
                        row.getLong("customer_destination_minor"),
                        row.getLong("position_source_minor"),
                        row.getLong("position_destination_minor"),
                        row.getLong("margin_minor"),
                        row.getLong("spread_margin_minor"),
                        row.getLong("markup_margin_minor"),
                        row.getLong("residual_minor")));
            }
            return List.copyOf(rows);
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("reading the trades to replay", failure), failure);
        }
    }

    private static Map<String, Long> sums(Connection unitOfWork, String sql) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql); ResultSet row = select.executeQuery()) {
            Map<String, Long> sums = new HashMap<>();
            while (row.next()) {
                sums.put(row.getString(1), row.getLong(2));
            }
            return sums;
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("reading the FX books' expectations", failure), failure);
        }
    }
}
