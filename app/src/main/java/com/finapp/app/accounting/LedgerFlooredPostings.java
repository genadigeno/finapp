package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.CutoffReaction;
import com.finapp.accounting.PostingRule;
import com.finapp.accounting.PostingRuleRegister;
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
 * The journal's entries valued at or before a period's end and posted after it (`P14-TSK-011`; `P14-TSK-006`'s review, I-1 and
 * M-2; owner decision C3; {@code LedgerJournalShapes}' precedent of an {@code app} read over the ledger's own tables and of reading
 * the command key from {@code idempotency_scope}). The journal's three dates hold a floor - posting date floored, value date the economic
 * one - and nothing else marks it on the journal; the entry's audit record carries {@code flooredFrom=}.
 *
 * <ul>
 *   <li>{@link #accruals}: lending's {@code LND-2}, whose posting date equals its value date unless floored - so these <em>are</em> the
 *       interest the period is short by, per currency and scale, the income line's amount ({@code SUM(bigint)} exact).
 *   <li>{@link #others}: the other floored rules' entries, counted per key namespace - {@code RECONCILIATION_DISCLOSURE}'s note that
 *       the journal holds them in a later period while a report by stated date may not.
 * </ul>
 *
 * <p>{@code SELECT} only, on the caller's snapshot; no reference, reason or actor is read. The posting date's own range: {@code
 * journal_entry (posting_date, id)} (ledger {@code V029}).
 */
public final class LedgerFlooredPostings {

    /** Lending's accrual entries' scope - {@code ledger.post:lending.accrual:<loan>:<date>}. */
    static final String ACCRUAL_SCOPE = PostingService.IDEMPOTENCY_SCOPE + ":" + LoanServicing.POSTING_KEY_PREFIX + "%";

    /** The accruals past the cutoff: the income line of each - exactly one per entry, by the rule's shape. */
    static final String ACCRUALS_SQL = "SELECT line.currency, line.scale, count(*) AS entries,"
            + " sum(line.amount_minor) AS interest FROM ledger.journal_entry entry"
            + " JOIN ledger.journal_line line ON line.entry_id = entry.id AND line.direction = 'CREDIT'"
            + " WHERE entry.posting_date > ? AND entry.value_date <= ? AND entry.idempotency_scope LIKE ?"
            + " GROUP BY line.currency, line.scale ORDER BY line.currency, line.scale";

    /** Every posted entry past the cutoff per key namespace - what is reported is filtered to the floored rules. */
    static final String BY_NAMESPACE_SQL = "SELECT split_part(entry.idempotency_scope, ':', 2) AS namespace, count(*) AS entries"
            + " FROM ledger.journal_entry entry WHERE entry.posting_date > ? AND entry.value_date <= ?"
            + " AND entry.idempotency_scope LIKE ? GROUP BY 1 ORDER BY 1";

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

    /** {@code LND-2}'s entries past {@code periodEnd} - the interest the period is short by. */
    public List<AccrualCompleteness.Floored> accruals(Connection snapshot, LocalDate periodEnd) {
        Objects.requireNonNull(periodEnd, "periodEnd");
        try (PreparedStatement read = snapshot.prepareStatement(ACCRUALS_SQL)) {
            read.setDate(1, Date.valueOf(periodEnd));
            read.setDate(2, Date.valueOf(periodEnd));
            read.setString(3, ACCRUAL_SCOPE);
            List<AccrualCompleteness.Floored> floored = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    floored.add(new AccrualCompleteness.Floored(AccrualCompleteness.Accruer.LENDING,
                            row.getString("currency").stripTrailing(), row.getInt("scale"), row.getLong("entries"),
                            row.getBigDecimal("interest").toBigIntegerExact()));
                }
            }
            return floored;
        } catch (SQLException failure) {
            throw failed("reading the accrual entries floored past a cutoff", failure);
        }
    }

    /** The other floored rules' entries past {@code periodEnd}, per key namespace - a namespace with none absent. */
    public SortedMap<String, Long> others(Connection snapshot, LocalDate periodEnd) {
        Objects.requireNonNull(periodEnd, "periodEnd");
        try (PreparedStatement read = snapshot.prepareStatement(BY_NAMESPACE_SQL)) {
            read.setDate(1, Date.valueOf(periodEnd));
            read.setDate(2, Date.valueOf(periodEnd));
            read.setString(3, PostingService.IDEMPOTENCY_SCOPE + ":%");
            SortedMap<String, Long> counts = new TreeMap<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    String namespace = row.getString("namespace");
                    if (flooredNamespaces.contains(namespace)) {
                        counts.put(namespace, row.getLong("entries"));
                    }
                }
            }
            return counts;
        } catch (SQLException failure) {
            throw failed("reading the floored rules' entries past a cutoff", failure);
        }
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
