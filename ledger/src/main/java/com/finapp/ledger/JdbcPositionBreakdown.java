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
import java.util.List;
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
 *
 * <p><strong>The whole history, always</strong> (`P16-REM-001`): this is the fold the proofs read
 * ({@code ProductSourceProofs}, the BNPL three-way), so no checkpoint ever verifies itself.
 * {@link CheckpointedPositionBreakdown} answers the merchant API's read from a checkpoint and the
 * lines since, with this class's classification ({@link #CLASSIFIED_LINES}, {@link #line}) and
 * {@link BucketFold}'s arithmetic.
 */
public final class JdbcPositionBreakdown implements PositionBreakdown<Connection> {

    /**
     * Every column a line is classified by, from the lines of one account: its amount, the origin of the proposal that posted its
     * entry, the first named counterparty purpose the entry touched, and the first named posting-key namespace its key begins with.
     * Bound by {@link #bind}: the purposes twice, the namespaces, the posting scope, then the account. Part of the classification
     * rules a checkpoint's version names ({@code CheckpointedPositionBreakdown#CLASSIFICATION_VERSION}).
     */
    static final String CLASSIFIED_LINES =
            "SELECT line.direction, line.amount_minor, line.currency,"
                    + " line.scale,"
                    // The origin of the proposal that posted the entry, if
                    // one did: journal_entry_id is UNIQUE (V010), so one row
                    // at most, and origin is NOT NULL since V015. A proposal
                    // not approved holds it NULL and matches nothing, and a
                    // reversal's entry no proposal posted, so NULL here is
                    // an entry no proposal posted.
                    + " (SELECT p.origin FROM ledger.adjustment_proposal p"
                    + "   WHERE p.journal_entry_id = line.entry_id) AS origin,"
                    + " (SELECT other_account.purpose || '|'"
                    + "         || other.direction"
                    + "    FROM ledger.journal_line other"
                    + "    JOIN ledger.ledger_account other_account"
                    + "      ON other_account.id = other.ledger_account_id"
                    + "   WHERE other.entry_id = line.entry_id"
                    + "     AND other_account.purpose = ANY (?)"
                    + "   ORDER BY array_position(?, other_account.purpose),"
                    + "            other.seq"
                    + "   LIMIT 1) AS counterparty,"
                    // The posting-key namespace (P12-TSK-016): the
                    // first caller-named prefix the entry's recorded
                    // command key begins with - PostingService's scope
                    // only, so no reversal or adjustment can carry one.
                    + " (SELECT ns.prefix"
                    + "    FROM unnest(?::text[]) WITH ORDINALITY"
                    + "         AS ns(prefix, ord)"
                    + "   WHERE starts_with(entry.idempotency_scope,"
                    + "                     ?::text || ns.prefix)"
                    + "   ORDER BY ns.ord"
                    + "   LIMIT 1) AS namespace";

    /** The lines of one account, the fifth parameter. */
    static final String OF_THE_ACCOUNT =
            " FROM ledger.journal_line line"
                    + " JOIN ledger.journal_entry entry"
                    + "   ON entry.id = line.entry_id"
                    + " WHERE line.ledger_account_id = ?";

    @Override
    public Breakdown breakdown(
            Connection unitOfWork,
            LedgerAccountId account,
            List<AccountPurpose> precedence,
            List<String> namespaces) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        checkedPrecedence(precedence);
        List<String> keyPrefixes = PositionBreakdown.checkedNamespaces(namespaces);
        try {
            AccountTerms terms = accountTerms(unitOfWork, account);
            BucketFold fold = new BucketFold(terms.currency());
            try (PreparedStatement select = unitOfWork.prepareStatement(CLASSIFIED_LINES + OF_THE_ACCOUNT)) {
                bind(unitOfWork, select, precedence, keyPrefixes, account);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        fold.add(line(row, account, terms.currency()));
                    }
                }
            } catch (ScaleMismatchException mixedScales) {
                throw refused(account, "its history mixes scales within " + terms.currency().code()
                        + " (INV-MON-03)");
            } catch (MonetaryOverflowException overflow) {
                throw refused(account, "a side's sum in " + terms.currency().code()
                        + " left the representable range (INV-MON-06)");
            }
            return settled(fold, account, terms);
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("breaking down the position of " + account, failure));
        }
    }

    /** What a line's amount means: the account's currency and normal balance, both frozen once posted to. */
    record AccountTerms(CurrencyCode currency, NormalBalance normalBalance) {}

    static void checkedPrecedence(List<AccountPurpose> precedence) {
        Objects.requireNonNull(precedence, "precedence must not be null");
        if (precedence.isEmpty() || Set.copyOf(precedence).size() != precedence.size()) {
            throw new IllegalArgumentException(
                    "the counterparty precedence must name at least one purpose, each once: "
                            + precedence);
        }
    }

    static AccountTerms accountTerms(Connection unitOfWork, LedgerAccountId account) throws SQLException {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT currency, normal_balance FROM ledger.ledger_account"
                                + " WHERE id = ?")) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    throw new UnderivableBalanceException(account, "no such ledger account");
                }
                return new AccountTerms(
                        CurrencyCode.of(row.getString("currency").stripTrailing()),
                        NormalBalance.valueOf(row.getString("normal_balance")));
            }
        }
    }

    /**
     * One line of {@link #CLASSIFIED_LINES}, as the bucket it belongs to. Part of the classification rules a checkpoint's version
     * names.
     */
    static Bucket line(ResultSet row, LedgerAccountId account, CurrencyCode currency) throws SQLException {
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
        // The origin rule first (P8-TSK-015, ADR-0073 section 6; P14-TSK-022, P14-TSK-023):
        // a line of any proposal, a resolution's attribution, a prior-period adjustment or
        // an operator's, is never read by what it faces. Bucket.of states it. An ADJUSTMENT
        // never matches PostingService's scope, and of() keeps the Bucket's own refusal
        // unreachable rather than trusting the data to.
        return Bucket.of(
                direction,
                amount,
                // By name, so an origin this build does not know reads as
                // none: by its counterparty, the sum holding (never a throw).
                Optional.ofNullable(row.getString("origin"))
                        .flatMap(AdjustmentOrigin::named),
                Optional.ofNullable(row.getString("counterparty"))
                        .map(JdbcPositionBreakdown::counterparty),
                Optional.ofNullable(row.getString("namespace")));
    }

    /**
     * The SAME fold the derivation performs, over the SAME lines the buckets were built from - which is what makes "the buckets sum to
     * the position" an identity rather than a hope.
     */
    static Breakdown settled(BucketFold fold, LedgerAccountId account, AccountTerms terms) {
        try {
            return fold.breakdown(account, terms.normalBalance());
        } catch (ScaleMismatchException mixedScales) {
            throw refused(account, "its two sides were summed at different scales"
                    + " (INV-MON-03)");
        }
    }

    static void bind(
            Connection unitOfWork,
            PreparedStatement select,
            List<AccountPurpose> precedence,
            List<String> namespaces,
            LedgerAccountId account)
            throws SQLException {
        Array purposes =
                unitOfWork.createArrayOf(
                        "text", precedence.stream().map(AccountPurpose::name).toArray());
        select.setArray(1, purposes);
        select.setArray(2, purposes);
        select.setArray(3, unitOfWork.createArrayOf("text", namespaces.toArray()));
        select.setString(4, POSTING_SCOPE);
        select.setObject(5, account.value());
    }

    /**
     * What {@code journal_entry.idempotency_scope} begins with for an entry PostingService wrote:
     * its scope and the separator, ahead of the command's own key.
     */
    static final String POSTING_SCOPE = PostingService.IDEMPOTENCY_SCOPE + ":";

    /** Parses the subquery's {@code PURPOSE|DIRECTION} label, both halves the ledger's own. */
    static Counterparty counterparty(String label) {
        int bar = label.indexOf('|');
        return new Counterparty(
                AccountPurpose.valueOf(label.substring(0, bar)),
                Direction.valueOf(label.substring(bar + 1)));
    }

    static UnderivableBalanceException refused(LedgerAccountId account, String why) {
        return new UnderivableBalanceException(account, why);
    }
}
