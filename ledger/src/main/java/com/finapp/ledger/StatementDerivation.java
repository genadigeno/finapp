package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The period statement, derived from postings (`P3-TSK-018`, {@code INV-ACC-02}'s drill-down
 * shape three phases early): what an account held at the start of a period, every line inside
 * it, and what it held at the end — with the three figures reconciling <strong>by
 * construction</strong>.
 *
 * <h2>Derived, never the projection</h2>
 *
 * <p>The statement is evidence-shaped: its figures must be reproducible from the journal, so
 * the opening balance comes from {@link BalanceDerivation} — the definition — and the closing
 * is computed as {@code opening + settle(periodDebits, periodCredits)}. It deliberately reads
 * nothing from {@code ledger.account_balance}: the projection is display bookkeeping
 * (ADR-0041), and a statement whose closing came from a different mechanism than its lines
 * could disagree with them, which is the one thing a statement must never do.
 *
 * <h2>Why the closing is computed rather than derived a second time</h2>
 *
 * <p>Under {@code READ COMMITTED} each statement sees its own snapshot, so a third read
 * ("derive as of {@code to}") could include a posting that committed between the opening read
 * and the lines read — and the statement would not reconcile to itself. The opening's range
 * ({@code posting_date <= from-1}) and the lines' range ({@code [from, to]}) are
 * <strong>disjoint predicates</strong>, so no interleaved commit can land in both or between
 * them, and {@code opening + lines = closing} holds structurally under any concurrency. A
 * posting committed mid-request may be absent from the statement entirely; that is what a
 * snapshot means, and a re-request sees it.
 *
 * <h2>Disclosure is the surface's</h2>
 *
 * <p>This port computes; it does not decide who may see. The caller (`app`'s account slice)
 * reaches it only through the caller's own product ({@code CustomerAccountStore.findOwnedBy},
 * ownership in the statement — ADR-0031). Two deliberate exclusions are the port's own:
 * a line carries no counterparty account identifier (an entry's other lines may touch other
 * parties' accounts), and no {@code reason} (free text written by a person,
 * {@code RESTRICTED-PII} — audit material, never statement material).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface StatementDerivation<T> {

    /**
     * One line of the statement: the account's own side of one journal entry.
     *
     * <p>{@code entry} is the drill-down key ({@code INV-ACC-02}): the figure traces to the
     * journal row that composes it. {@code reference} is the originating economic event —
     * the account owner's own transaction identifier from Phase 4 on.
     */
    record StatementLine(
            JournalEntryId entry,
            LocalDate postingDate,
            LocalDate valueDate,
            JournalEntryType entryType,
            String reference,
            Direction direction,
            Money amount) {

        public StatementLine {
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(postingDate, "postingDate must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            Objects.requireNonNull(entryType, "entryType must not be null");
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }

    /**
     * One ledger account's statement for the period. {@code opening} and {@code closing} are
     * <strong>settled</strong> balances signed by the account's normal balance
     * ({@link DerivedBalance}'s convention); the currency travels inside {@link Money}, so
     * even an empty statement says what kind of number it carries ({@code INV-MON-02}).
     */
    record AccountStatement(
            LedgerAccountId account, Money opening, List<StatementLine> lines, Money closing) {

        public AccountStatement {
            Objects.requireNonNull(account, "account must not be null");
            Objects.requireNonNull(opening, "opening must not be null");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
            Objects.requireNonNull(closing, "closing must not be null");
        }
    }

    /**
     * The statements of every ledger account owned by {@code ownerRef} for
     * {@code [from, to]}, both ends inclusive — "the statement to the 30th" means with the
     * 30th's postings in it, the {@link AsOf.PostingDate} reading. For a Customer Account,
     * one statement per currency (ADR-0042). Lines are ordered by posting date, then entry
     * id (mint order within a day — a presentation choice), then line sequence.
     *
     * @throws IllegalArgumentException if {@code from} is after {@code to} — the caller's
     *     own mistake, refused before any read
     * @throws UnderivableBalanceException the derivation's own regime: an underivable
     *     history must refuse loudly, never render a plausible statement
     */
    List<AccountStatement> statementsFor(T unitOfWork, UUID ownerRef, LocalDate from, LocalDate to);
}
