package com.finapp.reconciliation;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link WaitingPayoutReturns} over JDBC (`P8-TSK-019`, ADR-0033: explicit SQL, no ORM) - scoped to
 * the sources its worker is handed ({@code i.source_id = ANY(?)}, `P9-TSK-014`).
 */
public final class JdbcWaitingPayoutReturns implements WaitingPayoutReturns {

    private final SourceScope scope;

    public JdbcWaitingPayoutReturns(SourceScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
    }

    private static final String SELECT =
            "SELECT i.id, i.run_id, r.batch_id, i.source_id, i.amount_minor, i.currency,"
                    + " i.scale, COALESCE(i.settlement_date, i.business_date) AS settlement_date,"
                    + " i.correlation_id,"
                    + " (SELECT k.key_value FROM reconciliation.external_item_key k"
                    + " WHERE k.item_id = i.id AND k.key_kind = 'PAYOUT_PROVIDER_REF')"
                    + " AS provider_ref,"
                    + " (SELECT k.key_value FROM reconciliation.external_item_key k"
                    + " WHERE k.item_id = i.id AND k.key_kind = 'OUR_REF') AS our_ref"
                    + " FROM reconciliation.external_item i"
                    + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id"
                    + " WHERE i.status = 'UNMATCHED' AND i.line_type = 'PAYOUT_RETURNED'"
                    + " AND r.batch_id IS NOT NULL AND i.source_id = ANY(?)";

    @Override
    public List<WaitingReturn> page(Connection unitOfWork, Optional<UUID> after, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one return: " + limit);
        }
        // The keyset: strictly after the named item's own place in claimant order, read from
        // its row - still there whatever became of its status.
        String keyset =
                after.isEmpty()
                        ? ""
                        : " AND (r.source_sequence, i.line_no, i.id) > (SELECT"
                                + " ar.source_sequence, ai.line_no, ai.id"
                                + " FROM reconciliation.external_item ai"
                                + " JOIN reconciliation.reconciliation_batch ar"
                                + " ON ar.id = ai.run_id WHERE ai.id = ?)";
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        SELECT + keyset + " ORDER BY r.source_sequence, i.line_no, i.id"
                                + " LIMIT ?")) {
            int parameter = 1;
            read.setArray(parameter++, scoped(unitOfWork));
            if (after.isPresent()) {
                read.setObject(parameter++, after.get());
            }
            read.setInt(parameter, limit);
            List<WaitingReturn> page = new ArrayList<>();
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    page.add(map(rows));
                }
            }
            return page;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the payout returns waiting for their worker", failure);
        }
    }

    @Override
    public Optional<WaitingReturn> lockWaiting(Connection unitOfWork, UUID itemId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(SELECT + " AND i.id = ? FOR SHARE OF i")) {
            read.setArray(1, scoped(unitOfWork));
            read.setObject(2, itemId);
            try (ResultSet rows = read.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not re-read a waiting payout return under its share lock", failure);
        }
    }

    /** The worker's sources as a {@code uuid[]} - empty scopes read nothing, never everything. */
    private java.sql.Array scoped(Connection unitOfWork) throws SQLException {
        return unitOfWork.createArrayOf("uuid", scope.sourceIds(unitOfWork).toArray());
    }

    private static WaitingReturn map(ResultSet row) throws SQLException {
        return new WaitingReturn(
                row.getObject("id", UUID.class),
                row.getObject("run_id", UUID.class),
                row.getObject("batch_id", UUID.class),
                row.getObject("source_id", UUID.class),
                Money.ofPersisted(
                        row.getLong("amount_minor"),
                        CurrencyCode.of(row.getString("currency").stripTrailing()),
                        row.getShort("scale")),
                row.getDate("settlement_date").toLocalDate(),
                Optional.ofNullable(row.getString("provider_ref")),
                Optional.ofNullable(row.getString("our_ref")),
                CorrelationId.of(row.getString("correlation_id")));
    }
}
