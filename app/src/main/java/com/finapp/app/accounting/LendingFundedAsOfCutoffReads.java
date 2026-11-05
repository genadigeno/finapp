package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.platform.persistence.DatabaseFailure;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * accounting's {@link LendingFundedAsOfCutoff} over lending's frozen rows and the ledger's journal (`P14-TSK-019`; ADR-0096 section 6; {@code INV-ACC-04}, {@code INV-ACC-14}) - in {@code app}, the one module that sees both, and holding <strong>no type of {@code com.finapp.lending}</strong>, so {@code ReportingNeverWritesTest#noReportPortAdapterWrites} holds it: lending's live reports stay out of reach, and {@code
 * LendingCapitalHeadroom.principalPurposes} is pinned to the port's names by a test instead.
 *
 * <p><strong>Each statement names one module's schema</strong>, and the definition combines them in Java (ADR-0006). {@code SELECT} only, on the definition's snapshot, one statement each, no lock.
 *
 * <p><strong>{@link #COMMITTED_FACTS} reads no status.</strong> Every column it selects is frozen by a trigger for every writer: a loan's identity and {@code opened_at} (lending {@code V006}, {@code V032}), agreement version 1 (INSERT-only), an offer's terms version, the term set, and a disbursement's {@code value_date}, {@code decided_at} and {@code failure_reason} (NULL while {@code PENDING}, frozen once terminal). A later decision has a later date,
 * so no later act can change what {@link LendingFundedAsOfCutoff.LoanFacts#stoodCommitted} decides of a past cutoff. Driven from {@code loan_disbursement}: one row per instalment loan, born beside it and required at its commit by a deferred check, so the inner joins lose no loan, a credit line - which commits no capital (A28) - has no row, and the where clause's one predicate bounds the read to what could count.
 *
 * <p><strong>NULL.</strong> {@code value_date} is the one nullable column compared in SQL, and the predicate names both branches. Everything else nullable is decided in Java, on an {@link Optional}. The statements' behaviour against PostgreSQL is {@code SafeguardingReturnDatabaseTest}'s (owed, C5).
 */
public final class LendingFundedAsOfCutoffReads implements LendingFundedAsOfCutoff {

    /** Each instalment loan not disbursed by the cutoff: its frozen facts. */
    static final String COMMITTED_FACTS = "SELECT loan.currency, agreement.scale, agreement.amount_minor, loan.opened_at,"
            + " lending.business_date(loan.opened_at, terms.servicing_zone) AS opened_on,"
            + " disbursement.value_date, disbursement.decided_at,"
            + " CASE WHEN disbursement.failure_reason IS NOT NULL"
            + " THEN lending.business_date(disbursement.decided_at, terms.servicing_zone) END AS cancelled_on"
            + " FROM lending.loan_disbursement disbursement"
            + " JOIN lending.loan loan ON loan.id = disbursement.loan_id"
            + " JOIN lending.loan_agreement agreement ON agreement.loan_id = loan.id AND agreement.version = 1"
            + " JOIN lending.loan_offer offer ON offer.id = loan.offer_id"
            + " JOIN lending.loan_terms terms ON terms.terms_version_id = offer.terms_version_id"
            + " WHERE disbursement.value_date IS NULL OR disbursement.value_date > ?";

    /** The outstanding term's purposes' lines of the entries holding a line on a write-off expense, per currency, scale and side. The names are this codebase's own constants, and a statement never carries a value from anywhere else. */
    static final String WRITTEN_OFF = "SELECT line.currency, line.scale, line.direction, SUM(line.amount_minor) AS total, COUNT(*) AS lines"
            + " FROM ledger.journal_entry entry"
            + " JOIN ledger.journal_line line ON line.entry_id = entry.id"
            + " JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
            + " WHERE entry.posting_date <= ? AND account.purpose IN (" + quoted(PRINCIPAL_PURPOSES) + ")"
            + " AND EXISTS (SELECT 1 FROM ledger.journal_line beside"
            + " JOIN ledger.ledger_account beside_account ON beside_account.id = beside.ledger_account_id"
            + " WHERE beside.entry_id = line.entry_id AND beside_account.purpose IN (" + quoted(WRITE_OFF_EXPENSES) + "))";

    /** A PROVISIONAL run's cut: entries created at or before its claim. */
    static final String AT_THE_CUT = " AND entry.created_at <= ?";

    static final String WRITTEN_OFF_GROUPS = " GROUP BY line.currency, line.scale, line.direction ORDER BY line.currency, line.scale, line.direction";

    private static String quoted(Set<String> names) {
        return names.stream().sorted().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
    }

    @Override
    public List<Committed> committed(Connection snapshot, LocalDate cutoff, Optional<Instant> cut) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(cutoff, "cutoff");
        Objects.requireNonNull(cut, "cut");
        try (PreparedStatement read = snapshot.prepareStatement(COMMITTED_FACTS)) {
            read.setDate(1, Date.valueOf(cutoff));
            List<LoanFacts> facts = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    facts.add(new LoanFacts(row.getString("currency").stripTrailing(), row.getInt("scale"), BigInteger.valueOf(row.getLong("amount_minor")), date(row, "opened_on").orElseThrow(),
                            instant(row, "opened_at").orElseThrow(), date(row, "value_date"), date(row, "cancelled_on"), instant(row, "decided_at")));
                }
            }
            return LendingFundedAsOfCutoff.committedAt(facts, cutoff, cut);
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading the loans committed as of the cutoff", failure));
        }
    }

    @Override
    public List<WrittenOff> writtenOff(Connection snapshot, LocalDate cutoff, Optional<Instant> cut) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(cutoff, "cutoff");
        Objects.requireNonNull(cut, "cut");
        try (PreparedStatement read = snapshot.prepareStatement(WRITTEN_OFF + (cut.isPresent() ? AT_THE_CUT : "") + WRITTEN_OFF_GROUPS)) {
            read.setDate(1, Date.valueOf(cutoff));
            if (cut.isPresent()) {
                read.setTimestamp(2, Timestamp.from(cut.get()));
            }
            Map<String, WrittenOff> folded = new TreeMap<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    String currency = row.getString("currency").stripTrailing();
                    int scale = row.getInt("scale");
                    BigDecimal total = row.getBigDecimal("total");
                    BigInteger signed = "CREDIT".equals(row.getString("direction")) ? total.toBigIntegerExact() : total.toBigIntegerExact().negate();
                    folded.merge(currency + ":" + scale, new WrittenOff(currency, scale, signed, row.getLong("lines")),
                            (held, one) -> new WrittenOff(currency, scale, held.minor().add(one.minor()), held.lines() + one.lines()));
                }
            }
            return new ArrayList<>(folded.values());
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading the principal written off as of the cutoff", failure));
        }
    }

    private static Optional<LocalDate> date(ResultSet row, String column) throws SQLException {
        Date held = row.getDate(column);
        return held == null ? Optional.empty() : Optional.of(held.toLocalDate());
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp held = row.getTimestamp(column);
        return held == null ? Optional.empty() : Optional.of(held.toInstant());
    }
}
