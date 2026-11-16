package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.JournalCreations;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * accounting's {@link JournalCreations} over the ledger's journal (`P16-REM-003`) - in {@code app}, beside
 * {@link LedgerTrialBalanceReads}. One {@code SELECT} of {@code max(created_at)} over the entries dated in a span, which the
 * {@code posting_date} index serves; it names no {@code accounting} table (ADR-0006) and locks nothing.
 */
public final class LedgerJournalCreations implements JournalCreations {

    /** The latest creation instant of an entry dated on or before a day; {@link #AFTER} bounds it below. */
    static final String LATEST = "SELECT max(entry.created_at) AS latest FROM ledger.journal_entry entry WHERE entry.posting_date <= ?";

    static final String AFTER = " AND entry.posting_date > ?";

    @Override
    public Optional<Instant> latestCreated(Connection unitOfWork, Optional<LocalDate> after, LocalDate through) {
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(through, "through");
        try (PreparedStatement read = unitOfWork.prepareStatement(LATEST + (after.isPresent() ? AFTER : ""))) {
            read.setDate(1, Date.valueOf(through));
            if (after.isPresent()) {
                read.setDate(2, Date.valueOf(after.get()));
            }
            try (ResultSet row = read.executeQuery()) {
                Timestamp latest = row.next() ? row.getTimestamp("latest") : null;
                return latest == null ? Optional.empty() : Optional.of(latest.toInstant());
            }
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading when the journal's entries were created", failure));
        }
    }
}
