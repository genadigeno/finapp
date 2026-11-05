package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link ReturnsAReopenOvertakes} over plain JDBC (`P14-TSK-023`, ADR-0033): one plain {@code SELECT}, no lock, so the rows and the count are one snapshot. {@code regulatory_submission_of_period (period_id, status)} serves it. {@code submitted_at} is {@code NOT NULL} for both statuses ({@code regulatory_submission_sending_coherent}) and the id breaks a tie, so the order is total. {@code count(*) OVER ()} is taken before the {@code LIMIT}; a month with none returns no row, read as an empty list and zero. Against PostgreSQL: {@code PeriodReopenDatabaseTest} (owed, C5). A failure is its operation and SQLSTATE only.
 */
public final class JdbcReturnsAReopenOvertakes implements ReturnsAReopenOvertakes {

    static final String SQL = "SELECT s.id, s.form_code, s.status, count(*) OVER () AS total FROM accounting.regulatory_submission s WHERE s.period_id = ? AND s.status IN ('SUBMITTED', 'ACKNOWLEDGED') ORDER BY s.submitted_at DESC, s.id DESC LIMIT ?";

    @Override
    public Overtaken of(Connection unitOfWork, AccountingPeriodId period) {
        try (PreparedStatement read = unitOfWork.prepareStatement(SQL)) {
            read.setObject(1, period.value());
            read.setInt(2, NEWEST);
            try (ResultSet row = read.executeQuery()) {
                List<Item> newest = new ArrayList<>();
                long total = 0;
                while (row.next()) {
                    newest.add(new Item(RegulatorySubmissionId.of(row.getObject("id", UUID.class)), row.getString("form_code"), SubmissionStatus.valueOf(row.getString("status"))));
                    total = row.getLong("total");
                }
                return new Overtaken(newest, total);
            }
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading the returns a reopen overtakes", failure));
        }
    }
}
