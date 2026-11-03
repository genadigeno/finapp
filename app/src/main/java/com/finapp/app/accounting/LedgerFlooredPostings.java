package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.CutoffReaction;
import com.finapp.accounting.PastTheCutoffPostings;
import com.finapp.accounting.PostingRule;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.bnpl.BnplAccruals;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LoanServicing;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The journal's entries valued at or before a period's end and posted after it (`P14-TSK-011`; `P14-TSK-006`'s review,
 * I-1 and M-2; owner decision C3; {@code LedgerJournalShapes}' precedent of an {@code app} read over the ledger's own
 * tables and of reading the command key from {@code idempotency_scope}). The journal's three dates hold a floor -
 * posting date floored, value date the economic one - and nothing else marks it on the journal; the entry's audit
 * record carries {@code flooredFrom=}.
 *
 * <ul>
 *   <li>{@link #accruals}: lending's {@code LND-2}, whose posting date equals its value date unless floored, and bnpl's
 * {@code BNPL-F2a}, which posts today valued the accrual date (`P14-TSK-011`'s review, C-1) - so these <em>are</em> the
 * interest the period is short by, per accruer, currency and scale, the income line's amount ({@code SUM(bigint)} exact),
 * the period's last day apart from anything older.
 *   <li>{@link #pastTheCutoff}: the other floored rules' entries per key namespace, each told floored, unrecorded or its rule's own dating - the one read {@code
 * RECONCILIATION_DISCLOSURE} judges and {@code RECONCILIATION_STATUS} reports (the owner's decision (b) of 2026-11-03, `P14-TSK-012`).
 * </ul>
 *
 * <p>{@code SELECT} only, on the caller's snapshot; no reference, reason or actor is read. The posting date's own
 * range: {@code journal_entry (posting_date, id)} (ledger {@code V029}).
 */
public final class LedgerFlooredPostings implements PastTheCutoffPostings {

    /** Lending's accrual entries' scope - {@code ledger.post:lending.accrual:<loan>:<date>}. */
    static final String LENDING_SCOPE = PostingService.IDEMPOTENCY_SCOPE + ":" + LoanServicing.POSTING_KEY_PREFIX + "%";

    /** Bnpl's accrual entries' scope - {@code ledger.post:bnpl.accrual:<obligation>:<date>}; a correction's is its own. */
    static final String BNPL_SCOPE = PostingService.IDEMPOTENCY_SCOPE + ":" + BnplAccruals.KEY_PREFIX + "%";

    /**
     * The accruals past the cutoff, in one statement: the income line of each - exactly one per entry, by each rule's
     * shape - with those valued the period's last day counted apart.
     */
    static final String ACCRUALS_SQL = "SELECT split_part(entry.idempotency_scope, ':', 2) AS namespace, line.currency, line.scale,"
            + " count(*) AS entries, sum(line.amount_minor) AS interest,"
            + " count(*) FILTER (WHERE entry.value_date = ?) AS last_day_entries,"
            + " coalesce(sum(line.amount_minor) FILTER (WHERE entry.value_date = ?), 0) AS last_day_interest"
            + " FROM ledger.journal_entry entry"
            + " JOIN ledger.journal_line line ON line.entry_id = entry.id AND line.direction = 'CREDIT'"
            + " WHERE entry.posting_date > ? AND entry.value_date <= ?"
            + " AND (entry.idempotency_scope LIKE ? OR entry.idempotency_scope LIKE ?)"
            + " GROUP BY 1, line.currency, line.scale ORDER BY 1, line.currency, line.scale";

    /**
     * The posting's own audit record: {@code audit_record_by_target} holds the GL door's reads under this target type too ({@code GeneralLedgerDesk.ENTRY_TARGET}), and a read says
     * nothing about a floor.
     */
    private static final String POSTING_RECORD = "SELECT 1 FROM platform.audit_record audit"
            + " WHERE audit.target_type = 'journal_entry' AND audit.target_id = entry.id::text"
            + " AND audit.operation IN ('ledger.JournalEntryPosted', 'ledger.AdjustmentPosted')";

    /**
     * Every posted entry past the cutoff per key namespace, each told floored or not (`P14-TSK-017`; `P14-TSK-011`'s review, M-2; `P14-TSK-012`): the ledger names the stated date on the
     * entry's own audit record ({@code flooredFrom=}, {@code PostingService}), nothing on the journal does. <strong>"Record present, no mark" and "no record" are told apart</strong>, because
     * a rule's own dating is inferred from the mark's absence, which only a record present can show (the owner's decision (b), 2026-11-03). What is reported is filtered to the floored rules.
     * Two probes of {@code audit_record_by_target} per entry passing the dates' filter; the entries from {@code journal_entry_by_posting_date}.
     */
    static final String PAST_THE_CUTOFF_SQL = "SELECT past.namespace, count(*) AS entries,"
            + " count(*) FILTER (WHERE past.floored) AS floored, count(*) FILTER (WHERE NOT past.recorded) AS unrecorded FROM ("
            + "SELECT split_part(entry.idempotency_scope, ':', 2) AS namespace,"
            + " EXISTS (" + POSTING_RECORD + ") AS recorded,"
            + " EXISTS (" + POSTING_RECORD + " AND audit.change_summary LIKE '%flooredFrom=%') AS floored"
            + " FROM ledger.journal_entry entry WHERE entry.posting_date > ? AND entry.value_date <= ?"
            + " AND entry.idempotency_scope LIKE ?) past GROUP BY past.namespace ORDER BY past.namespace";

    private final Set<String> flooredNamespaces;

    /** Over the current register's floored rules, {@code LND-2} left to {@link #accruals}. */
    public LedgerFlooredPostings() {
        this(PostingRuleRegister.CURRENT.rules());
    }

    LedgerFlooredPostings(List<PostingRule> rules) {
        this.flooredNamespaces = rules.stream()
                .filter(rule -> rule.cutoff() == CutoffReaction.FLOORED_AT_FIRST_OPEN)
                .map(PostingRule::namespace)
                .filter(namespace -> !namespace.equals(LoanServicing.POSTING_KEY_PREFIX))
                .map(namespace -> namespace.substring(0, namespace.length() - 1))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** {@code LND-2}'s and {@code BNPL-F2a}'s entries past {@code periodEnd} - the interest the period is short by. */
    public List<AccrualCompleteness.Floored> accruals(Connection snapshot, LocalDate periodEnd) {
        Objects.requireNonNull(periodEnd, "periodEnd");
        try (PreparedStatement read = snapshot.prepareStatement(ACCRUALS_SQL)) {
            for (int bound = 1; bound <= 4; bound++) {
                read.setDate(bound, Date.valueOf(periodEnd));
            }
            read.setString(5, LENDING_SCOPE);
            read.setString(6, BNPL_SCOPE);
            List<AccrualCompleteness.Floored> floored = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    floored.add(new AccrualCompleteness.Floored(accruer(row.getString("namespace")),
                            row.getString("currency").stripTrailing(), row.getInt("scale"), row.getLong("entries"),
                            row.getBigDecimal("interest").toBigIntegerExact(), row.getLong("last_day_entries"),
                            row.getBigDecimal("last_day_interest").toBigIntegerExact()));
                }
            }
            return floored;
        } catch (SQLException failure) {
            throw failed("reading the accrual entries floored past a cutoff", failure);
        }
    }

    @Override
    public SortedMap<String, PastTheCutoffPostings.Split> pastTheCutoff(Connection snapshot, LocalDate cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        try (PreparedStatement read = snapshot.prepareStatement(PAST_THE_CUTOFF_SQL)) {
            read.setDate(1, Date.valueOf(cutoff));
            read.setDate(2, Date.valueOf(cutoff));
            read.setString(3, PostingService.IDEMPOTENCY_SCOPE + ":%");
            SortedMap<String, PastTheCutoffPostings.Split> splits = new TreeMap<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    String namespace = row.getString("namespace");
                    if (flooredNamespaces.contains(namespace)) {
                        splits.put(namespace, new PastTheCutoffPostings.Split(row.getLong("entries"), row.getLong("floored"), row.getLong("unrecorded")));
                    }
                }
            }
            return splits;
        } catch (SQLException failure) {
            throw failed("reading which entries past a cutoff were floored", failure);
        }
    }

    /** The accruer a namespace of {@link #ACCRUALS_SQL}'s belongs to; anything else is this read's defect, never a figure. */
    private static AccrualCompleteness.Accruer accruer(String namespace) {
        if ((namespace + ":").equals(LoanServicing.POSTING_KEY_PREFIX)) {
            return AccrualCompleteness.Accruer.LENDING;
        }
        if ((namespace + ":").equals(BnplAccruals.KEY_PREFIX)) {
            return AccrualCompleteness.Accruer.BNPL;
        }
        throw new IllegalStateException("not an accrual namespace: " + namespace);
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
