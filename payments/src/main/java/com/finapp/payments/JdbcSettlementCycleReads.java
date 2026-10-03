package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SettlementCycleReads} over JDBC (`P8-TSK-021`, ADR-0033: explicit SQL, no ORM): the
 * pay-ins', withdrawals' and parkings' stored {@code settlement_cycle}, folded to each token's
 * earliest record, one keyset page at a time.
 */
public final class JdbcSettlementCycleReads implements SettlementCycleReads<Connection> {

    @Override
    public List<SeenCycle> cyclesSince(
            Connection unitOfWork, Instant since, Optional<String> after, int limit) {
        Objects.requireNonNull(since, "since must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("a cycle page holds at least one: " + limit);
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT token, min(seen) FROM ("
                                + " SELECT settlement_cycle AS token, created_at AS seen"
                                + "   FROM payments.payment_attempt"
                                + "   WHERE settlement_cycle > ? AND created_at >= ?"
                                + " UNION ALL"
                                + " SELECT settlement_cycle, created_at FROM payments.withdrawal"
                                + "   WHERE settlement_cycle > ? AND created_at >= ?"
                                + " UNION ALL"
                                + " SELECT settlement_cycle, received_at"
                                + "   FROM payments.unmatched_confirmation"
                                + "   WHERE settlement_cycle > ? AND received_at >= ?"
                                + ") cycles GROUP BY token ORDER BY token LIMIT ?")) {
            Timestamp from = Timestamp.from(since);
            // A stored cycle is never empty (its CHECK), so "" admits every token - and the
            // comparison excludes NULL as the old IS NOT NULL did.
            String keyset = after.orElse("");
            for (int branch = 0; branch < 3; branch++) {
                read.setString(branch * 2 + 1, keyset);
                read.setTimestamp(branch * 2 + 2, from);
            }
            read.setInt(7, limit);
            List<SeenCycle> cycles = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    cycles.add(new SeenCycle(row.getString(1), row.getTimestamp(2).toInstant()));
                }
            }
            return cycles;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the settlement cycles named", failure));
        }
    }
}
