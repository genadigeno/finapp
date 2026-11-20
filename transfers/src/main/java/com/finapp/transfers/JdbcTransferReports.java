package com.finapp.transfers;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link TransferReports} over JDBC (`P17-TSK-039`, ADR-0033: explicit SQL, no ORM): one grouped read of the window's identifier
 * range on the primary key, the sums PostgreSQL's exact {@code numeric} over the stored minor units, grouped by the stored scale
 * so two scales are never added.
 */
public final class JdbcTransferReports implements TransferReports {

    private static final String OUTCOMES =
            "SELECT status, failure_reason, currency, scale, count(*) AS transfers, sum(amount_minor) AS minor_total"
                    + " FROM transfers.transfer WHERE id >= ? AND id < ?"
                    + " GROUP BY status, failure_reason, currency, scale"
                    + " ORDER BY status, failure_reason NULLS FIRST, currency, scale";

    @Override
    public List<OutcomeSum> outcomes(Connection unitOfWork, Instant from, Instant to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(OUTCOMES)) {
            select.setObject(1, TransferReports.firstIdAt(from));
            select.setObject(2, TransferReports.firstIdAt(to));
            List<OutcomeSum> sums = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    sums.add(new OutcomeSum(
                            TransferStatus.valueOf(row.getString("status")),
                            Optional.ofNullable(row.getString("failure_reason")).map(FailureReason::valueOf),
                            CurrencyCode.of(row.getString("currency")),
                            row.getShort("scale"),
                            row.getLong("transfers"),
                            row.getBigDecimal("minor_total")));
                }
            }
            return List.copyOf(sums);
        } catch (SQLException failure) {
            throw new TransfersStorageException(DatabaseFailure.describe("summing the transfers", failure));
        }
    }
}
