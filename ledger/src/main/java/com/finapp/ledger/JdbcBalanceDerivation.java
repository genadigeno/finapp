package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryOverflowException;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.ScaleMismatchException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * The balance derivation over plain JDBC (ADR-0033, `P3-TSK-008`).
 *
 * <p><strong>The lines are aggregated by one statement and folded through {@link Money}.</strong>
 * One statement, so under {@code READ COMMITTED} the whole history is read from one snapshot —
 * the derivation is internally consistent and sees committed postings only, which is what a
 * read of the authoritative record should mean. The fold streams row by row through
 * {@link JournalEntry#sum}'s scale-aware identity rather than materialising the history, and
 * never through a SQL {@code SUM} — the reasoning is {@link BalanceDerivation}'s.
 *
 * <p>The account's currency and normal balance are read in a separate statement, which is safe
 * only because both are frozen — currency unconditionally, classification once posted to
 * (`P3-TSK-002`) — so no interleaving commit can change what the lines mean between the two
 * reads.
 *
 * <p>The monetary kernel's refusals are translated to {@link UnderivableBalanceException}
 * rather than propagated, because {@code Money}'s diagnostics render amounts and this class's
 * failures describe an account's whole history ({@code INV-AUD-02}); see that exception's
 * javadoc.
 */
public final class JdbcBalanceDerivation implements BalanceDerivation<Connection> {

    private static final String ACCOUNT_TABLE = "ledger.ledger_account";
    private static final String LINE_TABLE = "ledger.journal_line";
    private static final String ENTRY_TABLE = "ledger.journal_entry";

    @Override
    public DerivedBalance derive(Connection unitOfWork, LedgerAccountId account, AsOf asOf) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");
        try {
            CurrencyCode currency;
            NormalBalance normalBalance;
            try (PreparedStatement select =
                    unitOfWork.prepareStatement(
                            "SELECT currency, normal_balance FROM " + ACCOUNT_TABLE
                                    + " WHERE id = ?")) {
                select.setObject(1, account.value());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        // Loud, never an empty Optional a caller forgets: an account that does
                        // not exist has no balance, and the caller asking is a defect
                        // (the ChartOfAccounts refusal, applied to the derivation).
                        throw new UnderivableBalanceException(
                                account, "no such ledger account");
                    }
                    currency = CurrencyCode.of(row.getString("currency").stripTrailing());
                    normalBalance = NormalBalance.valueOf(row.getString("normal_balance"));
                }
            }

            Money debits = Money.zero(currency);
            Money credits = Money.zero(currency);
            try (PreparedStatement select = linesInRange(unitOfWork, account, asOf);
                    ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    CurrencyCode lineCurrency =
                            CurrencyCode.of(row.getString("currency").stripTrailing());
                    if (!lineCurrency.equals(currency)) {
                        // Unstorable under V005's composite FK - guarded anyway, because the
                        // scale-aware zero identity below deliberately bypasses Money.plus's
                        // currency check for a zero, and the schema and this guard are two
                        // controls blind in different directions (INV-MON-04).
                        throw new UnderivableBalanceException(
                                account,
                                "a line's currency " + lineCurrency.code()
                                        + " is foreign to the account's " + currency.code()
                                        + " (INV-MON-04)");
                    }
                    // ofPersisted, exactly: a stored amount reads back as the amount it was,
                    // whatever the currency's minor units say today (INV-MON-05).
                    Money amount =
                            Money.ofPersisted(
                                    row.getLong("amount_minor"), lineCurrency,
                                    row.getShort("scale"));
                    if (Direction.valueOf(row.getString("direction")) == Direction.DEBIT) {
                        debits = JournalEntry.sum(debits, amount);
                    } else {
                        credits = JournalEntry.sum(credits, amount);
                    }
                }
            } catch (ScaleMismatchException mixedScales) {
                throw refusedMixedScales(account, currency);
            } catch (MonetaryOverflowException overflow) {
                throw refusedOverflow(account, currency);
            }

            try {
                return new DerivedBalance(
                        account, BalanceDerivation.settle(normalBalance, debits, credits));
            } catch (ScaleMismatchException mixedScales) {
                // The two sides summed at different persisted scales - a mixed history met at
                // the subtraction rather than inside a side.
                throw refusedMixedScales(account, currency);
            } catch (MonetaryOverflowException overflow) {
                throw refusedOverflow(account, currency);
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("deriving balance of account " + account, failure));
        }
    }

    /**
     * The one statement the derivation reads lines with, shaped by the cut. The entry cut's
     * {@code entry_id <= ?} comparison is deliberately SQL's and never Java's — see
     * {@link AsOf} for the {@code UUID.compareTo} trap.
     */
    private static PreparedStatement linesInRange(
            Connection unitOfWork, LedgerAccountId account, AsOf asOf) throws SQLException {
        String columns = "SELECT line.direction, line.amount_minor, line.currency, line.scale"
                + " FROM " + LINE_TABLE + " line";
        return switch (asOf) {
            case AsOf.Latest ignored -> {
                PreparedStatement select =
                        unitOfWork.prepareStatement(
                                columns + " WHERE line.ledger_account_id = ?");
                select.setObject(1, account.value());
                yield select;
            }
            case AsOf.PostingDate cut -> {
                PreparedStatement select =
                        unitOfWork.prepareStatement(
                                columns + " JOIN " + ENTRY_TABLE + " entry"
                                        + " ON entry.id = line.entry_id"
                                        + " WHERE line.ledger_account_id = ?"
                                        + " AND entry.posting_date <= ?");
                select.setObject(1, account.value());
                select.setObject(2, cut.date());
                yield select;
            }
            case AsOf.ThroughEntry cut -> {
                PreparedStatement select =
                        unitOfWork.prepareStatement(
                                columns + " WHERE line.ledger_account_id = ?"
                                        + " AND line.entry_id <= ?");
                select.setObject(1, account.value());
                select.setObject(2, cut.entry().value());
                yield select;
            }
        };
    }

    private static UnderivableBalanceException refusedMixedScales(
            LedgerAccountId account, CurrencyCode currency) {
        return new UnderivableBalanceException(
                account,
                "its history mixes scales within " + currency.code()
                        + ", and summing across scales would be an implicit rescale"
                        + " (INV-MON-03)");
    }

    private static UnderivableBalanceException refusedOverflow(
            LedgerAccountId account, CurrencyCode currency) {
        return new UnderivableBalanceException(
                account,
                "a side's sum in " + currency.code()
                        + " left the representable range (INV-MON-06)");
    }
}
