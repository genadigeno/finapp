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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link PositionBreakdown} over plain JDBC (`P6-TSK-010`, ADR-0033) —
 * {@link JdbcBalanceDerivation}'s fold, with each line carrying one more column.
 *
 * <p><strong>One statement for the lines</strong>, and the counterparty's direction is a
 * correlated subquery inside it rather than a second read, so every line and its classification
 * come from the same snapshot. The account's currency and normal balance are read separately,
 * which is safe for the reason the derivation records: both are frozen once posted to, so no
 * interleaving commit can change what the lines mean between the two reads.
 *
 * <p>An entry that touched a counterparty purpose in BOTH directions, or several of the named
 * purposes at once, is not a shape any composer on this platform produces; the subquery takes
 * the first by the caller's precedence and then by line sequence, so the answer is
 * deterministic, and the position is unaffected either way — every line is folded exactly
 * once, whatever bucket its label puts it in.
 */
public final class JdbcPositionBreakdown implements PositionBreakdown<Connection> {

    @Override
    public Breakdown breakdown(
            Connection unitOfWork, LedgerAccountId account, List<AccountPurpose> precedence) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(precedence, "precedence must not be null");
        if (precedence.isEmpty() || Set.copyOf(precedence).size() != precedence.size()) {
            throw new IllegalArgumentException(
                    "the counterparty precedence must name at least one purpose, each once: "
                            + precedence);
        }
        try {
            CurrencyCode currency;
            NormalBalance normalBalance;
            try (PreparedStatement select =
                    unitOfWork.prepareStatement(
                            "SELECT currency, normal_balance FROM ledger.ledger_account"
                                    + " WHERE id = ?")) {
                select.setObject(1, account.value());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        throw new UnderivableBalanceException(account, "no such ledger account");
                    }
                    currency = CurrencyCode.of(row.getString("currency").stripTrailing());
                    normalBalance = NormalBalance.valueOf(row.getString("normal_balance"));
                }
            }

            Map<String, Bucket> buckets = new LinkedHashMap<>();
            Money debits = Money.zero(currency);
            Money credits = Money.zero(currency);
            try (PreparedStatement select =
                            unitOfWork.prepareStatement(
                                    "SELECT line.direction, line.amount_minor, line.currency,"
                                            + " line.scale,"
                                            + " EXISTS (SELECT 1 FROM ledger.adjustment_proposal p"
                                            + "          WHERE p.journal_entry_id = line.entry_id"
                                            + "            AND p.origin = 'RECONCILIATION')"
                                            + "   AS attributed,"
                                            + " (SELECT other_account.purpose || '|'"
                                            + "         || other.direction"
                                            + "    FROM ledger.journal_line other"
                                            + "    JOIN ledger.ledger_account other_account"
                                            + "      ON other_account.id = other.ledger_account_id"
                                            + "   WHERE other.entry_id = line.entry_id"
                                            + "     AND other_account.purpose = ANY (?)"
                                            + "   ORDER BY array_position(?, other_account.purpose),"
                                            + "            other.seq"
                                            + "   LIMIT 1) AS counterparty"
                                            + " FROM ledger.journal_line line"
                                            + " WHERE line.ledger_account_id = ?");
                    ResultSet row = executed(unitOfWork, select, precedence, account)) {
                while (row.next()) {
                    CurrencyCode lineCurrency =
                            CurrencyCode.of(row.getString("currency").stripTrailing());
                    if (!lineCurrency.equals(currency)) {
                        throw new UnderivableBalanceException(
                                account,
                                "a line's currency " + lineCurrency.code()
                                        + " is foreign to the account's " + currency.code()
                                        + " (INV-MON-04)");
                    }
                    Money amount =
                            Money.ofPersisted(
                                    row.getLong("amount_minor"), lineCurrency,
                                    row.getShort("scale"));
                    Direction direction = Direction.valueOf(row.getString("direction"));
                    // The origin rule first (P8-TSK-015, ADR-0073 section 6): a resolution's
                    // attribution is never read by what it faces.
                    boolean attributed = row.getBoolean("attributed");
                    Optional<Counterparty> other =
                            attributed
                                    ? Optional.empty()
                                    : Optional.ofNullable(row.getString("counterparty"))
                                            .map(JdbcPositionBreakdown::counterparty);

                    if (direction == Direction.DEBIT) {
                        debits = JournalEntry.sum(debits, amount);
                    } else {
                        credits = JournalEntry.sum(credits, amount);
                    }
                    String key =
                            direction + "|"
                                    + (attributed
                                            ? "RECONCILIATION"
                                            : other.map(c -> c.purpose() + ":" + c.direction())
                                                    .orElse("NONE"));
                    Bucket so = buckets.get(key);
                    buckets.put(
                            key,
                            new Bucket(
                                    direction,
                                    other,
                                    so == null ? amount : JournalEntry.sum(so.total(), amount),
                                    attributed));
                }
            } catch (ScaleMismatchException mixedScales) {
                throw refused(account, "its history mixes scales within " + currency.code()
                        + " (INV-MON-03)");
            } catch (MonetaryOverflowException overflow) {
                throw refused(account, "a side's sum in " + currency.code()
                        + " left the representable range (INV-MON-06)");
            }

            try {
                // The SAME fold the derivation performs, over the SAME lines the buckets were
                // built from - which is what makes "the buckets sum to the position" an identity
                // rather than a hope.
                return new Breakdown(
                        account,
                        BalanceDerivation.settle(normalBalance, debits, credits),
                        new ArrayList<>(buckets.values()));
            } catch (ScaleMismatchException mixedScales) {
                throw refused(account, "its two sides were summed at different scales"
                        + " (INV-MON-03)");
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("breaking down the position of " + account, failure));
        }
    }

    private static ResultSet executed(
            Connection unitOfWork,
            PreparedStatement select,
            List<AccountPurpose> precedence,
            LedgerAccountId account)
            throws SQLException {
        Array purposes =
                unitOfWork.createArrayOf(
                        "text", precedence.stream().map(AccountPurpose::name).toArray());
        select.setArray(1, purposes);
        select.setArray(2, purposes);
        select.setObject(3, account.value());
        return select.executeQuery();
    }

    /** Parses the subquery's {@code PURPOSE|DIRECTION} label, both halves the ledger's own. */
    private static Counterparty counterparty(String label) {
        int bar = label.indexOf('|');
        return new Counterparty(
                AccountPurpose.valueOf(label.substring(0, bar)),
                Direction.valueOf(label.substring(bar + 1)));
    }

    private static UnderivableBalanceException refused(LedgerAccountId account, String why) {
        return new UnderivableBalanceException(account, why);
    }
}
