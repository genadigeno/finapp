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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link StatementDerivation} over explicit SQL (ADR-0033), on the caller's connection.
 *
 * <p><strong>Composes the derivation rather than copying it</strong>: the opening balance is
 * {@link BalanceDerivation#derive} at {@code AsOf.postingDate(from - 1)} — the definition,
 * not a restatement of it — and the period fold reuses {@link JournalEntry#sum}'s scale-aware
 * identity, because a second copy of a monetary subtlety is the copy that drifts
 * (`P2-TSK-011`'s rule). The closing is computed from the two — see
 * {@link StatementDerivation}'s consistency argument for why a third read is deliberately
 * not taken.
 *
 * <p><strong>No lock anywhere</strong>: a statement must never contend with the write path it
 * reports on (the {@code BalanceDisplay}/{@code ProjectionVerification} stance). The period
 * lines come from one statement, one snapshot; the entry join filters on
 * {@code posting_date}, served by {@code journal_line_by_account} plus the entry primary key.
 */
public final class JdbcStatementDerivation implements StatementDerivation<Connection> {

    private final BalanceDerivation<Connection> derivation;

    public JdbcStatementDerivation(BalanceDerivation<Connection> derivation) {
        this.derivation = Objects.requireNonNull(derivation, "derivation must not be null");
    }

    @Override
    public List<AccountStatement> statementsFor(
            Connection unitOfWork, UUID ownerRef, LocalDate from, LocalDate to) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(ownerRef, "ownerRef must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (from.isAfter(to)) {
            // The caller's own mistake, refused before any read - and without echoing the
            // values back through an exception message that reaches logs (they are the
            // caller's query parameters, harmless, but the boundary owes the naming).
            throw new IllegalArgumentException(
                    "a statement period must not start after it ends");
        }

        List<AccountStatement> statements = new ArrayList<>();
        for (OwnedAccount owned : accountsOf(unitOfWork, ownerRef)) {
            statements.add(statementOf(unitOfWork, owned, from, to));
        }
        return List.copyOf(statements);
    }

    // -----------------------------------------------------------------

    private record OwnedAccount(
            LedgerAccountId id, CurrencyCode currency, NormalBalance normalBalance) {}

    /** The owner's ledger accounts — the {@code JdbcBalanceDisplay} read, plus the sign. */
    private static List<OwnedAccount> accountsOf(Connection unitOfWork, UUID ownerRef) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, currency, normal_balance FROM ledger.ledger_account"
                                + " WHERE owner_ref = ?"
                                + " ORDER BY currency, id")) {
            read.setObject(1, ownerRef);
            try (ResultSet rows = read.executeQuery()) {
                List<OwnedAccount> accounts = new ArrayList<>();
                while (rows.next()) {
                    accounts.add(
                            new OwnedAccount(
                                    LedgerAccountId.of((UUID) rows.getObject("id")),
                                    CurrencyCode.of(rows.getString("currency").stripTrailing()),
                                    NormalBalance.valueOf(rows.getString("normal_balance"))));
                }
                return accounts;
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "reading the ledger accounts of owner " + ownerRef, failure));
        }
    }

    private AccountStatement statementOf(
            Connection unitOfWork, OwnedAccount owned, LocalDate from, LocalDate to) {
        // The opening: everything up to the day before the period - the definition's own
        // posting-date cut. A period starting at the calendar's floor has no earlier day, and
        // its opening over the empty range is exactly zero (guarding the minusDays underflow).
        Money opening =
                from.isAfter(LocalDate.MIN)
                        ? derivation
                                .derive(unitOfWork, owned.id(), AsOf.postingDate(from.minusDays(1)))
                                .settled()
                        : Money.zero(owned.currency());

        List<StatementLine> lines = new ArrayList<>();
        Money debits = Money.zero(owned.currency());
        Money credits = Money.zero(owned.currency());
        try (PreparedStatement read = periodLines(unitOfWork, owned.id(), from, to);
                ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                CurrencyCode lineCurrency =
                        CurrencyCode.of(rows.getString("currency").stripTrailing());
                if (!lineCurrency.equals(owned.currency())) {
                    // Unstorable under V005's composite FK - guarded anyway, the derivation's
                    // own defence in depth (INV-MON-04).
                    throw new UnderivableBalanceException(
                            owned.id(),
                            "a line's currency " + lineCurrency.code()
                                    + " is foreign to the account's " + owned.currency().code()
                                    + " (INV-MON-04)");
                }
                Money amount =
                        Money.ofPersisted(
                                rows.getLong("amount_minor"), lineCurrency,
                                rows.getShort("scale"));
                Direction direction = Direction.valueOf(rows.getString("direction"));
                if (direction == Direction.DEBIT) {
                    debits = JournalEntry.sum(debits, amount);
                } else {
                    credits = JournalEntry.sum(credits, amount);
                }
                lines.add(
                        new StatementLine(
                                JournalEntryId.of((UUID) rows.getObject("entry_id")),
                                rows.getObject("posting_date", LocalDate.class),
                                rows.getObject("value_date", LocalDate.class),
                                JournalEntryType.valueOf(rows.getString("entry_type")),
                                rows.getString("reference"),
                                direction,
                                amount));
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "reading the statement lines of account " + owned.id(), failure));
        } catch (ScaleMismatchException mixedScales) {
            throw refusedMixedScales(owned);
        } catch (MonetaryOverflowException overflow) {
            throw refusedOverflow(owned);
        }

        // The closing, computed: opening + the period's signed net. An empty period is the
        // opening exactly - no addition, so no zero-scale artefact can touch the figure.
        if (lines.isEmpty()) {
            return new AccountStatement(owned.id(), opening, List.of(), opening);
        }
        try {
            Money net = BalanceDerivation.settle(owned.normalBalance(), debits, credits);
            Money closing = JournalEntry.sum(opening, net);
            return new AccountStatement(owned.id(), opening, lines, closing);
        } catch (ScaleMismatchException mixedScales) {
            // The period's persisted scale differs from the opening history's - the mixed
            // history met at the closing addition, exactly where a full derivation over
            // [start, to] would meet it inside a side (INV-MON-03).
            throw refusedMixedScales(owned);
        } catch (MonetaryOverflowException overflow) {
            throw refusedOverflow(owned);
        }
    }

    /**
     * The period's lines, one statement, one snapshot — ordered by posting date, then entry
     * id (mint order within a day, {@code AsOf}'s recorded caveat: a presentation choice,
     * not a linearisation claim), then the entry's own line sequence.
     */
    private static PreparedStatement periodLines(
            Connection unitOfWork, LedgerAccountId account, LocalDate from, LocalDate to)
            throws SQLException {
        PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT line.entry_id, line.direction, line.amount_minor,"
                                + " line.currency, line.scale,"
                                + " entry.posting_date, entry.value_date, entry.entry_type,"
                                + " entry.reference"
                                + " FROM ledger.journal_line line"
                                + " JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                                + " WHERE line.ledger_account_id = ?"
                                + " AND entry.posting_date BETWEEN ? AND ?"
                                + " ORDER BY entry.posting_date, entry.id, line.seq");
        read.setObject(1, account.value());
        read.setObject(2, from);
        read.setObject(3, to);
        return read;
    }

    private static UnderivableBalanceException refusedMixedScales(OwnedAccount owned) {
        return new UnderivableBalanceException(
                owned.id(),
                "its history mixes scales within " + owned.currency().code()
                        + ", and a statement summed across scales would be an implicit"
                        + " rescale (INV-MON-03)");
    }

    private static UnderivableBalanceException refusedOverflow(OwnedAccount owned) {
        return new UnderivableBalanceException(
                owned.id(),
                "a period sum in " + owned.currency().code()
                        + " left the representable range (INV-MON-06)");
    }
}
