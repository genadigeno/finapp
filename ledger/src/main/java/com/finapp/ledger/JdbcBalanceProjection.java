package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The projection updater over plain JDBC (ADR-0033, `P3-TSK-009`).
 *
 * <p><strong>The delta is a Money fold; the accumulation is one guarded SQL addition.</strong>
 * Per account, the entry's debit and credit lines fold through {@link JournalEntry#sum} and
 * the sign convention stays {@link BalanceDerivation#settle}'s — stated once, never restated
 * here. The row's {@code posted_minor + delta} then happens in SQL, which is admissible where
 * a history-wide {@code SUM} was not (`P3-TSK-008`) because both failure modes that argument
 * names are structurally closed at this one addition: cross-scale accumulation is refused by
 * the update's scale-match condition, and {@code bigint} overflow <em>raises</em> in
 * PostgreSQL rather than wrapping ({@code INV-MON-06}).
 *
 * <p><strong>Not read-modify-write.</strong> {@code SET posted_minor = balance.posted_minor
 * + EXCLUDED.posted_minor} re-reads the row under the exclusive lock the {@code UPDATE}
 * takes, so concurrent postings serialise on the row and compose — the atomic-increment
 * idiom (`P1-TSK-011`'s counter shape). The first posting races through
 * {@code ON CONFLICT}: one instance inserts, the loser converges to the increment.
 *
 * <p><strong>Rows are locked in one fixed order.</strong> An entry touching several accounts
 * applies them sorted by account id, so two multi-account postings can never hold projection
 * row locks in opposite orders. This is a lock-ordering discipline only — any total order
 * agreed across instances serves — not a semantic id comparison, so {@link AsOf}'s
 * SQL-only-comparison rule (about range cuts against PostgreSQL's byte order) is not
 * implicated.
 */
public final class JdbcBalanceProjection implements BalanceProjection<Connection> {

    private static final String ACCOUNT_TABLE = "ledger.ledger_account";
    private static final String BALANCE_TABLE = "ledger.account_balance";

    @Override
    public void apply(Connection unitOfWork, JournalEntry entry) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(entry, "entry must not be null");

        // One currency per account (V005) and one scale per currency per entry (the domain's
        // own validation), so per-account sides fold without a mixed-scale or cross-currency
        // case to meet - and a per-account sub-sum of positive amounts cannot overflow where
        // the entry's already-validated per-currency sum did not.
        Map<LedgerAccountId, Money> debits = new HashMap<>();
        Map<LedgerAccountId, Money> credits = new HashMap<>();
        for (JournalLine line : entry.lines()) {
            Map<LedgerAccountId, Money> side =
                    line.direction() == Direction.DEBIT ? debits : credits;
            side.merge(line.account(), line.amount(), JournalEntry::sum);
        }
        List<LedgerAccountId> accounts = new ArrayList<>();
        for (JournalLine line : entry.lines()) {
            if (!accounts.contains(line.account())) {
                accounts.add(line.account());
            }
        }
        accounts.sort(Comparator.comparing(LedgerAccountId::value));

        try {
            for (LedgerAccountId account : accounts) {
                Money anySide = debits.getOrDefault(account, credits.get(account));
                CurrencyCode currency = anySide.currency();
                NormalBalance normalBalance = normalBalanceOf(unitOfWork, account);
                Money delta =
                        BalanceDerivation.settle(
                                normalBalance,
                                debits.getOrDefault(account, Money.zero(currency)),
                                credits.getOrDefault(account, Money.zero(currency)));
                upsert(unitOfWork, account, delta, entry);
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "projecting balance for entry " + entry.id(), failure));
        }
    }

    @Override
    public void adjustHolds(
            Connection unitOfWork,
            LedgerAccountId account,
            Money delta,
            java.time.Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(delta, "delta must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement adjust =
                unitOfWork.prepareStatement(
                        // The guarded atomic addition, posted_minor's idiom: re-reads under
                        // the row's own lock, scale/currency-matched so a cross-scale
                        // adjustment is refused wholly (INV-MON-03), and V006's
                        // holds_minor >= 0 CHECK raises on any over-release.
                        "UPDATE " + BALANCE_TABLE + " SET"
                                + " holds_minor = holds_minor + ?,"
                                + " updated_at = ?"
                                + " WHERE ledger_account_id = ?"
                                + " AND scale = ? AND currency = ?")) {
            adjust.setLong(1, delta.minorUnits());
            adjust.setTimestamp(2, java.sql.Timestamp.from(at));
            adjust.setObject(3, account.value());
            adjust.setInt(4, delta.scale());
            adjust.setString(5, delta.currency().code());
            if (adjust.executeUpdate() == 0) {
                // Loud, never converged: a hold is only ever accepted against settled money,
                // so the row exists at the entry's scale - absence or a scale mismatch is a
                // broken invariant. Amount-free (INV-AUD-02).
                throw new UnderivableBalanceException(
                        account,
                        "its projection row is absent or persisted at a different scale, so"
                                + " a hold adjustment cannot follow it (INV-MON-03)");
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "adjusting the holds of account " + account, failure));
        }
    }

    private static NormalBalance normalBalanceOf(Connection unitOfWork, LedgerAccountId account)
            throws SQLException {
        // Safe as a separate read for the derivation's own reason: the classification is
        // frozen once posted to, and this entry's lines are already inserted on this unit of
        // work. The application role cannot write normal_balance at all (V002's grant).
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT normal_balance FROM " + ACCOUNT_TABLE + " WHERE id = ?")) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    // Unreachable: the lines' FK proved the account exists in this
                    // transaction. Loud anyway, never a default.
                    throw new IllegalStateException(
                            "no ledger account " + account + " for a line that referenced it");
                }
                return NormalBalance.valueOf(row.getString("normal_balance"));
            }
        }
    }

    private void upsert(
            Connection unitOfWork, LedgerAccountId account, Money delta, JournalEntry entry)
            throws SQLException {
        try (PreparedStatement upsert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + BALANCE_TABLE + " AS balance"
                                + " (ledger_account_id, currency, posted_minor, holds_minor,"
                                + " scale, last_entry_seq, updated_at)"
                                + " VALUES (?, ?, ?, 0, ?, 1, ?)"
                                + " ON CONFLICT (ledger_account_id) DO UPDATE SET"
                                + " posted_minor = balance.posted_minor"
                                + " + EXCLUDED.posted_minor,"
                                + " last_entry_seq = balance.last_entry_seq + 1,"
                                + " updated_at = EXCLUDED.updated_at"
                                // The scale guard: applying across scales would be the
                                // implicit rescale INV-MON-03 forbids, so the row is left
                                // untouched and the zero row count below refuses the posting
                                // wholly. Currency agreement is the composite FK's already;
                                // the clause is defence in depth (INV-MON-04).
                                + " WHERE balance.scale = EXCLUDED.scale"
                                + " AND balance.currency = EXCLUDED.currency")) {
            upsert.setObject(1, account.value());
            upsert.setString(2, delta.currency().code());
            upsert.setLong(3, delta.minorUnits());
            upsert.setInt(4, delta.scale());
            upsert.setTimestamp(5, Timestamp.from(entry.createdAt()));
            if (upsert.executeUpdate() == 0) {
                // Refuse loudly, never a sum in the message (INV-AUD-02). The whole posting
                // rolls back with this: a posting the projection cannot follow must not
                // commit beside a projection now permanently behind (ADR-0041 rule 1).
                throw new UnderivableBalanceException(
                        account,
                        "its projection is persisted at a different scale, and applying this"
                                + " posting would be an implicit rescale (INV-MON-03)");
            }
        }
    }
}
