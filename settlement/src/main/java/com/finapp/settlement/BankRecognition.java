package com.finapp.settlement;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Hop 2's arithmetic, pure (`P8-TSK-016`, ADR-0065 §3, {@code INV-SET-06}): a bank statement's
 * canonical lines to its recognition entry — the ONE way cash moves on the books.
 *
 * <p>With every line signed from the platform's side (a credit {@code +}, a debit and a fee
 * {@code −}), the entry is, per account and netted:
 *
 * <ul>
 *   <li>{@code CASH_AT_BANK} — the whole fold, which the parse already proved is the
 *       statement's closing minus its opening (checked here again against the stored net);
 *   <li>each ATTRIBUTED source's clearing position — the opposite of its lines' fold: the cash
 *       that arrived for that counterparty discharges what the report said it owed;
 *   <li>{@code PROCESSING_COSTS} — the opposite of the fee fold (the bank's charge);
 *   <li>{@code SUSPENSE_UNMATCHED} — a CREDIT line of the unattributed credits and a DEBIT line
 *       of the unattributed debits, NEVER netted (ADR-0070: the two sides are never set against
 *       each other): cash the statement proves arrived (or left) but nobody's pattern explains.
 *       The intake opens one owned {@code BANK_UNATTRIBUTED} suspense item per such line beside
 *       them ({@code INV-REC-09}), each side's items summing to its line exactly — so an entry
 *       exists whenever an unattributed line does, and every suspense item names it.
 * </ul>
 *
 * <p>The folds sum to zero by construction, so the entry balances ({@code INV-LED-01}); an
 * account whose fold is zero takes no line, and an all-zero statement omits the entry honestly.
 * Deterministic over stored rows — the positions ordered by source id — so a re-claim after a
 * crash produces byte-identical lines and the posting key's fingerprint converges. Folded with
 * {@code Money}, never SQL ({@code INV-MON-01}).
 */
public final class BankRecognition {

    private BankRecognition() {}

    /** The recognition's accounts, resolved by the caller from the chart and the register. */
    public record Accounts(
            LedgerAccountId cash,
            LedgerAccountId processingCosts,
            LedgerAccountId suspense,
            Map<UUID, LedgerAccountId> positionByAttributedSource) {

        public Accounts {
            Objects.requireNonNull(cash, "cash must not be null");
            Objects.requireNonNull(processingCosts, "processingCosts must not be null");
            Objects.requireNonNull(suspense, "suspense must not be null");
            Objects.requireNonNull(
                    positionByAttributedSource, "positionByAttributedSource must not be null");
            positionByAttributedSource = Map.copyOf(positionByAttributedSource);
        }
    }

    /** The entry's lines (possibly none) and the cash fold they move. */
    public record Recognition(List<JournalLine> entryLines, Money cash) {

        public Recognition {
            Objects.requireNonNull(entryLines, "entryLines must not be null");
            Objects.requireNonNull(cash, "cash must not be null");
            entryLines = List.copyOf(entryLines);
        }

        /** An all-zero statement posts nothing — recorded as {@code posting_omitted}. */
        public boolean postingOmitted() {
            return entryLines.isEmpty();
        }
    }

    /**
     * The statement's recognition entry.
     *
     * @param lines the statement's canonical lines, as stored (attribution included)
     * @param currency the statement's one currency, at {@code scale}
     * @param declaredNet the stored batch net — closing minus opening — the cash fold must equal
     * @param accounts the four account families, the positions keyed by attributed source
     */
    public static Recognition recognise(
            List<SettlementBatchStore.LineRow> lines,
            CurrencyCode currency,
            int scale,
            Money declaredNet,
            Accounts accounts) {
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(declaredNet, "declaredNet must not be null");
        Objects.requireNonNull(accounts, "accounts must not be null");
        Money zero = Money.ofPersisted(0, currency, scale);
        Money cash = zero;
        Money fees = zero;
        Money unattributedIn = zero;
        Money unattributedOut = zero;
        Map<UUID, Money> bySource = new TreeMap<>();
        for (SettlementBatchStore.LineRow line : lines) {
            if (!line.lineType().isBankLine()) {
                throw new IllegalArgumentException(
                        "a statement carries bank lines only: line " + line.lineNo() + " is "
                                + line.lineType());
            }
            Money amount = Money.ofPersisted(line.amountMinor(), line.currency(), line.scale());
            Money signed = line.direction() == LineDirection.INBOUND ? amount : amount.negated();
            cash = cash.plus(signed);
            if (line.lineType() == SettlementLineType.BANK_FEE) {
                fees = fees.plus(signed);
                continue;
            }
            Optional<UUID> attributed = line.attributedSourceId();
            if (attributed.isPresent()) {
                bySource.merge(attributed.get(), signed, Money::plus);
            } else if (line.direction() == LineDirection.INBOUND) {
                unattributedIn = unattributedIn.plus(amount);
            } else {
                unattributedOut = unattributedOut.plus(amount);
            }
        }
        if (!cash.equals(declaredNet)) {
            // The parse proved opening + lines = closing; a stored net that disagrees is a
            // corrupted parse statement, never something to post around (INV-SET-06).
            throw new IllegalStateException(
                    "a statement's lines fold to its declared net (INV-SET-06)");
        }
        List<JournalLine> entry = new ArrayList<>();
        // Debit-positive: cash is the fold; every other account takes the opposite of its own.
        add(entry, accounts.cash(), cash);
        for (Map.Entry<UUID, Money> source : bySource.entrySet()) {
            LedgerAccountId position = accounts.positionByAttributedSource().get(source.getKey());
            if (position == null) {
                throw new IllegalArgumentException(
                        "no position account resolved for attributed source " + source.getKey());
            }
            add(entry, position, source.getValue().negated());
        }
        add(entry, accounts.processingCosts(), fees.negated());
        // The two sides apart: an unattributed credit is owed back or explained, a debit is
        // recovered or explained - never one set against the other (ADR-0070).
        add(entry, accounts.suspense(), unattributedIn.negated());
        add(entry, accounts.suspense(), unattributedOut);
        if (entry.size() > BatchRecognition.MAX_ENTRY_LINES) {
            throw new IllegalStateException(
                    "a recognition entry is at most " + BatchRecognition.MAX_ENTRY_LINES
                            + " lines (P8-TSK-009): a wider statement redesigns, never drifts");
        }
        return new Recognition(entry, cash);
    }

    private static void add(List<JournalLine> entry, LedgerAccountId account, Money debitPositive) {
        if (debitPositive.minorUnits() == 0) {
            return;
        }
        entry.add(
                debitPositive.minorUnits() > 0
                        ? new JournalLine(account, Direction.DEBIT, debitPositive)
                        : new JournalLine(account, Direction.CREDIT, debitPositive.negated()));
    }
}
