package com.finapp.app.bnpl;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@link BnplReportReads} over bnpl's tables and the ledger's journal (`P12-TSK-026`) - the statements behind BNPL's
 * operations reports, every one read-only and run on the caller's snapshot; lending's {@code LendingReports} statements'
 * shape. An obligation's business date is the database clock in its pinned servicing zone ({@code bnpl.business_date});
 * an amount is the lines whose entry's value date is on or before the date (every bnpl posting is dated its obligation's
 * business date). Every statement is this class's own constant text - nothing is built from input but the bound date.
 * Proven by {@code BnplReportsDatabaseTest} (a database of its own).
 */
public final class JdbcBnplReportReads implements BnplReportReads {

    /** Each obligation born by the date ({@code ?1}), with its status at the end of it ({@code ?2}). */
    private static final String STATED = "SELECT o.id, o.product, o.currency, CASE WHEN o.closed_at IS NOT NULL AND"
            + " bnpl.business_date(o.closed_at, o.servicing_zone) <= ? THEN 'CLOSED' ELSE 'ACTIVE' END AS status"
            + " FROM bnpl.bnpl_obligation o WHERE o.purchase_date <= ?";

    /** Signed DR - CR. */
    private static final String SIGNED = "sum(CASE WHEN ln.direction = 'DEBIT' THEN ln.amount_minor ELSE -ln.amount_minor END)";

    /** Every line on a {@code BNPL_OBLIGATION}-owned account whose entry's value date is on or before {@code ?}. */
    private static final String BOOK_LINES = " FROM ledger.ledger_account a"
            + " JOIN ledger.journal_line ln ON ln.ledger_account_id = a.id"
            + " JOIN ledger.journal_entry e ON e.id = ln.entry_id"
            + " WHERE a.owner_kind = 'BNPL_OBLIGATION' AND e.value_date <= ?";

    /**
     * Each {@code ACTIVE} obligation at the end of the date with its latest recorded bucket and default flag on or before
     * it ({@code ?1}, {@code ?2} the status, {@code ?3}, {@code ?4} the conditions).
     */
    private static final String CONDITIONED = "SELECT s.id, s.product, s.currency,"
            + " COALESCE((SELECT c.bucket FROM bnpl.bnpl_condition_event c WHERE c.obligation_id = s.id"
            + " AND c.kind = 'DELINQUENCY' AND c.business_date <= ? ORDER BY c.business_date DESC LIMIT 1),"
            + " 'CURRENT') AS bucket,"
            + " COALESCE((SELECT c.defaulted FROM bnpl.bnpl_condition_event c WHERE c.obligation_id = s.id"
            + " AND c.kind = 'DEFAULT' AND c.business_date <= ? ORDER BY c.business_date DESC LIMIT 1),"
            + " false) AS defaulted"
            + " FROM (" + STATED + ") s WHERE s.status = 'ACTIVE'";

    @Override
    public boolean reportable(Connection snapshot, LocalDate day) {
        try (PreparedStatement select = snapshot.prepareStatement(
                "SELECT ? <= ((statement_timestamp() AT TIME ZONE 'UTC') + interval '14 hours')::date")) {
            select.setObject(1, day);
            try (ResultSet found = select.executeQuery()) {
                found.next();
                return found.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed(failure);
        }
    }

    @Override
    public List<ObligationCount> obligations(Connection snapshot, LocalDate day) {
        List<ObligationCount> counts = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement(
                "SELECT s.product, s.currency, s.status, count(*) FROM (" + STATED + ") s GROUP BY 1, 2, 3")) {
            select.setObject(1, day);
            select.setObject(2, day);
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    counts.add(new ObligationCount(found.getString(1), found.getString(2).strip(), found.getString(3),
                            found.getLong(4)));
                }
            }
            return counts;
        } catch (SQLException failure) {
            throw failed(failure);
        }
    }

    @Override
    public List<Balance> obligationBalances(Connection snapshot, LocalDate day) {
        return balances(snapshot, "SELECT o.product, a.currency, ln.scale, a.purpose, " + SIGNED
                + BOOK_LINES.replace(" WHERE ", " JOIN bnpl.bnpl_obligation o ON o.id = a.owner_ref WHERE ")
                + " GROUP BY 1, 2, 3, 4", day);
    }

    @Override
    public List<Balance> bookBalances(Connection snapshot, LocalDate day) {
        return balances(snapshot, "SELECT '' AS book, a.currency, ln.scale, a.purpose, " + SIGNED + BOOK_LINES
                + " GROUP BY 1, 2, 3, 4", day);
    }

    @Override
    public Map<String, Boolean> trialBalance(Connection snapshot, LocalDate day) {
        Map<String, BigDecimal> difference = new TreeMap<>();
        try (PreparedStatement select = snapshot.prepareStatement("SELECT ln.currency, ln.scale, " + SIGNED
                + " FROM ledger.journal_line ln JOIN ledger.journal_entry e ON e.id = ln.entry_id WHERE e.value_date <= ?"
                + " GROUP BY 1, 2")) {
            select.setObject(1, day);
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    difference.merge(found.getString(1).strip(), found.getBigDecimal(3).movePointLeft(found.getInt(2)),
                            BigDecimal::add);
                }
            }
        } catch (SQLException failure) {
            throw failed(failure);
        }
        Map<String, Boolean> zero = new TreeMap<>();
        difference.forEach((currency, value) -> zero.put(currency, value.signum() == 0));
        return zero;
    }

    @Override
    public List<Financing> financing(Connection snapshot, LocalDate day) {
        List<Financing> rows = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement("SELECT f.currency, f.scale,"
                + " count(*), sum(f.gross_minor), sum(f.fee_minor), sum(f.net_minor),"
                + " COALESCE((SELECT count(*) FROM bnpl.bnpl_refund r WHERE r.currency = f.currency AND r.scale = f.scale"
                + " AND r.value_date <= ?), 0),"
                + " COALESCE((SELECT sum(r.amount_minor) FROM bnpl.bnpl_refund r WHERE r.currency = f.currency"
                + " AND r.scale = f.scale AND r.value_date <= ?), 0),"
                + " COALESCE((SELECT sum(r.fee_returned_minor) FROM bnpl.bnpl_refund r WHERE r.currency = f.currency"
                + " AND r.scale = f.scale AND r.value_date <= ?), 0),"
                + " COALESCE((SELECT sum(r.payable_debit_minor) FROM bnpl.bnpl_refund r WHERE r.currency = f.currency"
                + " AND r.scale = f.scale AND r.value_date <= ?), 0)"
                + " FROM bnpl.merchant_financing f JOIN bnpl.purchase p ON p.id = f.purchase_id"
                + " WHERE p.business_date <= ? GROUP BY f.currency, f.scale ORDER BY 1, 2")) {
            for (int parameter = 1; parameter <= 5; parameter++) {
                select.setObject(parameter, day);
            }
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    rows.add(new Financing(found.getString(1).strip(), found.getInt(2), found.getLong(3),
                            found.getBigDecimal(4), found.getBigDecimal(5), found.getBigDecimal(6), found.getLong(7),
                            found.getBigDecimal(8), found.getBigDecimal(9), found.getBigDecimal(10)));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw failed(failure);
        }
    }

    @Override
    public List<Balance> payableLines(Connection snapshot, LocalDate day) {
        return balances(snapshot, "SELECT '' AS book, a.currency, ln.scale, a.purpose,"
                + " sum(CASE WHEN ln.direction = 'CREDIT' THEN ln.amount_minor ELSE -ln.amount_minor END)"
                + " FROM ledger.ledger_account a JOIN ledger.journal_line ln ON ln.ledger_account_id = a.id"
                + " JOIN ledger.journal_entry e ON e.id = ln.entry_id"
                + " WHERE a.purpose = 'MERCHANT_PAYABLE' AND e.value_date <= ?"
                + " AND (ln.entry_id IN (SELECT f.journal_entry_id FROM bnpl.merchant_financing f)"
                + " OR ln.entry_id IN (SELECT r.journal_entry_id FROM bnpl.bnpl_refund r))"
                + " GROUP BY 1, 2, 3, 4", day);
    }

    @Override
    public List<RefundSum> refunds(Connection snapshot, LocalDate day) {
        String onReceivables = "(SELECT sum(CASE WHEN ln.direction = 'CREDIT' THEN ln.amount_minor ELSE -ln.amount_minor"
                + " END) FROM ledger.journal_line ln JOIN ledger.ledger_account a ON a.id = ln.ledger_account_id"
                + " AND a.owner_kind = 'BNPL_OBLIGATION' AND a.owner_ref = r.obligation_id AND a.purpose IN (%s)"
                + " WHERE ln.entry_id = r.journal_entry_id)";
        String receivables = String.format(onReceivables,
                "'BNPL_PRINCIPAL', 'BNPL_PRINCIPAL_DUE', 'BNPL_INTEREST_ACCRUED', 'BNPL_INTEREST_DUE'");
        String fees = String.format(onReceivables, "'BNPL_FEES_DUE'");
        List<RefundSum> rows = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement("SELECT o.product, r.currency, r.scale,"
                + " CASE WHEN r.refunded_before_minor + r.amount_minor = f.gross_minor THEN 'FULL' ELSE 'PARTIAL' END,"
                + " r.door, count(*), sum(r.amount_minor), sum(r.cancelled_unbilled_minor + r.cancelled_billed_minor),"
                + " sum(r.interest_accrued_released_minor + r.interest_due_released_minor),"
                + " sum(CASE WHEN r.excess_to = 'WALLET' THEN r.excess_minor ELSE 0 END),"
                + " sum(CASE WHEN r.excess_to = 'CREDIT_BALANCE' THEN r.excess_minor ELSE 0 END),"
                + " sum(r.fee_returned_minor), sum(r.payable_debit_minor),"
                + " COALESCE(sum(" + receivables + "), 0), COALESCE(sum(" + fees + "), 0)"
                + " FROM bnpl.bnpl_refund r JOIN bnpl.bnpl_obligation o ON o.id = r.obligation_id"
                + " JOIN bnpl.merchant_financing f ON f.purchase_id = r.purchase_id"
                + " WHERE r.value_date = ? GROUP BY 1, 2, 3, 4, 5 ORDER BY 1, 2, 3, 4, 5")) {
            select.setObject(1, day);
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    rows.add(new RefundSum(found.getString(1), found.getString(2).strip(), found.getInt(3),
                            found.getString(4), found.getString(5), found.getLong(6), found.getBigDecimal(7),
                            found.getBigDecimal(8), found.getBigDecimal(9), found.getBigDecimal(10),
                            found.getBigDecimal(11), found.getBigDecimal(12), found.getBigDecimal(13),
                            found.getBigDecimal(14), found.getBigDecimal(15)));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw failed(failure);
        }
    }

    @Override
    public List<DelinquencyCount> delinquency(Connection snapshot, LocalDate day) {
        List<DelinquencyCount> rows = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement("SELECT d.product, d.currency, d.bucket, count(*),"
                + " count(*) FILTER (WHERE d.defaulted) FROM (" + CONDITIONED + ") d GROUP BY 1, 2, 3 ORDER BY 1, 2, 3")) {
            for (int parameter = 1; parameter <= 4; parameter++) {
                select.setObject(parameter, day);
            }
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    rows.add(new DelinquencyCount(found.getString(1), found.getString(2).strip(), found.getString(3),
                            found.getLong(4), found.getLong(5)));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw failed(failure);
        }
    }

    @Override
    public List<Balance> dues(Connection snapshot, LocalDate day) {
        List<Balance> rows = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement("SELECT d.product, d.bucket, d.currency, ln.scale,"
                + " a.purpose, " + SIGNED + " FROM (" + CONDITIONED + ") d JOIN ledger.ledger_account a"
                + " ON a.owner_kind = 'BNPL_OBLIGATION' AND a.owner_ref = d.id"
                + " AND a.purpose IN ('BNPL_PRINCIPAL_DUE', 'BNPL_INTEREST_DUE', 'BNPL_FEES_DUE')"
                + " JOIN ledger.journal_line ln ON ln.ledger_account_id = a.id"
                + " JOIN ledger.journal_entry e ON e.id = ln.entry_id WHERE e.value_date <= ? GROUP BY 1, 2, 3, 4, 5")) {
            for (int parameter = 1; parameter <= 5; parameter++) {
                select.setObject(parameter, day);
            }
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    rows.add(new Balance(found.getString(1) + "|" + found.getString(2), found.getString(3).strip(),
                            found.getInt(4), found.getString(5), found.getBigDecimal(6)));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw failed(failure);
        }
    }

    @Override
    public Map<UUID, UUID> agreementsOf(Connection snapshot, Collection<UUID> obligations) {
        Map<UUID, UUID> agreements = new TreeMap<>();
        try (PreparedStatement select = snapshot.prepareStatement(
                "SELECT id, agreement_id FROM bnpl.bnpl_obligation WHERE id = ANY (?)")) {
            select.setArray(1, snapshot.createArrayOf("uuid", obligations.toArray()));
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    agreements.put(found.getObject(1, UUID.class), found.getObject(2, UUID.class));
                }
            }
            return agreements;
        } catch (SQLException failure) {
            throw failed(failure);
        }
    }

    /** {@code sql}'s rows as balances - its one parameter the date. */
    private static List<Balance> balances(Connection snapshot, String sql, LocalDate day) {
        List<Balance> rows = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement(sql)) {
            select.setObject(1, day);
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    rows.add(new Balance(found.getString(1), found.getString(2).strip(), found.getInt(3),
                            found.getString(4), found.getBigDecimal(5)));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw failed(failure);
        }
    }

    private static IllegalStateException failed(SQLException failure) {
        return new IllegalStateException("a BNPL report could not be read: " + failure.getSQLState());
    }
}
