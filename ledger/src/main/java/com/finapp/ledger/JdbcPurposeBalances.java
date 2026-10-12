package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryOverflowException;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.ScaleMismatchException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link PurposeBalances} over plain JDBC (`P11-TSK-004`, ADR-0033): each read ONE statement, the
 * lines folded through {@link JournalEntry#sum}'s scale-aware identity - never a SQL {@code SUM}
 * ({@link BalanceDerivation}'s rule). A failure names the operation and the SQLSTATE, never a row
 * or an amount ({@code INV-AUD-02}).
 */
public final class JdbcPurposeBalances implements PurposeBalances<Connection> {

    @Override
    public Money netDebit(Connection unitOfWork, Set<AccountPurpose> purposes, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purposes, "purposes must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (purposes.isEmpty()) {
            throw new IllegalArgumentException("name at least one purpose");
        }
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT line.direction, line.amount_minor, line.scale"
                                + " FROM ledger.journal_line line"
                                + " JOIN ledger.ledger_account account"
                                + " ON account.id = line.ledger_account_id"
                                + " WHERE account.purpose = ANY (?) AND account.currency = ?")) {
            Array names =
                    unitOfWork.createArrayOf(
                            "text", purposes.stream().map(Enum::name).sorted().toArray());
            select.setArray(1, names);
            select.setString(2, currency.code());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    Money amount =
                            Money.ofPersisted(row.getLong("amount_minor"), currency, row.getShort("scale"));
                    if (Direction.valueOf(row.getString("direction")) == Direction.DEBIT) {
                        debits = JournalEntry.sum(debits, amount);
                    } else {
                        credits = JournalEntry.sum(credits, amount);
                    }
                }
            }
            return BalanceDerivation.settle(NormalBalance.DEBIT, debits, credits);
        } catch (ScaleMismatchException | MonetaryOverflowException underivable) {
            throw new LedgerStorageException(
                    "the lines of " + purposes + " in " + currency.code()
                            + " cannot be folded: mixed scales or overflow (INV-MON-03, INV-MON-06)");
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("folding the lines of " + purposes + " in " + currency.code(), failure));
        }
    }

    @Override
    public List<EntryLine> linesOn(Connection unitOfWork, LedgerAccountId account) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT line.entry_id, line.direction, line.amount_minor, line.currency, line.scale"
                                + " FROM ledger.journal_line line"
                                + " WHERE line.ledger_account_id = ? ORDER BY line.entry_id")) {
            select.setObject(1, account.value());
            List<EntryLine> lines = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    lines.add(
                            new EntryLine(
                                    JournalEntryId.of(row.getObject("entry_id", UUID.class)),
                                    Direction.valueOf(row.getString("direction")),
                                    Money.ofPersisted(
                                            row.getLong("amount_minor"),
                                            CurrencyCode.of(row.getString("currency").stripTrailing()),
                                            row.getShort("scale"))));
                }
            }
            return List.copyOf(lines);
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("reading the lines of account " + account, failure));
        }
    }

    @Override
    public List<DatedLine> datedLinesOn(
            Connection unitOfWork, Set<LedgerAccountId> accounts, LocalDate valueDateThrough) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(accounts, "accounts must not be null");
        Objects.requireNonNull(valueDateThrough, "valueDateThrough must not be null");
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("name at least one account");
        }
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT line.entry_id, line.ledger_account_id, entry.value_date, line.direction,"
                                + " line.amount_minor, line.currency, line.scale"
                                + " FROM ledger.journal_line line"
                                + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                                + " WHERE line.ledger_account_id = ANY (?) AND entry.value_date <= ?"
                                + " ORDER BY entry.value_date, line.entry_id")) {
            Array ids =
                    unitOfWork.createArrayOf(
                            "uuid", accounts.stream().map(LedgerAccountId::value).toArray());
            select.setArray(1, ids);
            select.setObject(2, valueDateThrough);
            List<DatedLine> lines = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    lines.add(
                            new DatedLine(
                                    JournalEntryId.of(row.getObject("entry_id", UUID.class)),
                                    LedgerAccountId.of(row.getObject("ledger_account_id", UUID.class)),
                                    row.getObject("value_date", LocalDate.class),
                                    Direction.valueOf(row.getString("direction")),
                                    Money.ofPersisted(
                                            row.getLong("amount_minor"),
                                            CurrencyCode.of(row.getString("currency").stripTrailing()),
                                            row.getShort("scale"))));
                }
            }
            return List.copyOf(lines);
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("reading the dated lines of " + accounts.size() + " accounts", failure));
        }
    }
}
