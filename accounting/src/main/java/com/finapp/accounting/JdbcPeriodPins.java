package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link GlPins.PeriodPins} over plain JDBC (`P14-TSK-007`, {@code accounting V003}): one month's period row - its status
 * and pinned version - by its unique {@code period_start}, a plain {@code SELECT} (no {@code FOR SHARE}: the GL reads in
 * a read-only transaction, which refuses a row lock, and decides nothing that a lock would protect). A failure names the
 * operation and the SQLSTATE only.
 */
public final class JdbcPeriodPins implements GlPins.PeriodPins {

    static final String SELECT = "SELECT status, pinned_mapping_version_id FROM accounting.accounting_period"
            + " WHERE period_start = ?";

    @Override
    public Optional<GlPins.PeriodPin> of(Connection snapshot, YearMonth month) {
        try (PreparedStatement read = snapshot.prepareStatement(SELECT)) {
            read.setDate(1, Date.valueOf(month.atDay(1)));
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID pinned = row.getObject("pinned_mapping_version_id", UUID.class);
                return Optional.of(new GlPins.PeriodPin(PeriodStatus.valueOf(row.getString("status")),
                        pinned == null ? Optional.empty() : Optional.of(GlMappingVersionId.of(pinned))));
            }
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading an accounting period's pin", failure));
        }
    }
}
