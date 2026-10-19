package com.finapp.bnpl;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What the three-way proof reads beyond bnpl's own rows (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 12.12) - a port
 * {@code app} implements over {@code merchant}, the ledger, lending and credit, so bnpl gains no build edge to them
 * ({@code BnplModuleIsolationTest}). Every method runs on the caller's ONE read-only {@code REPEATABLE READ} snapshot, so
 * the payable, the trial balance, the capital proof and the exposure census are the same state as the BNPL rows they are
 * judged beside. Read-only; nothing here decides or writes.
 */
public interface BnplProofReads {

    /**
     * The merchant's {@code MERCHANT_PAYABLE} in {@code currency} as the merchant leg reads it: every line on it whose
     * entry's posting key begins {@code bnpl.} - the financing's and the refunds' namespaces, and any other a raw posting
     * might use - and whether the payable still satisfies {@code INV-MER-02}'s equation with the BNPL terms (the drill-down's
     * terms sum to its position, and its four BNPL terms to the BNPL lines' net). Empty when the merchant has no payable in
     * the currency.
     */
    Optional<PayableLeg> payableLeg(Connection snapshot, UUID merchant, CurrencyCode currency);

    /** Whether the journal's trial balance is zero in {@code currency} - DR = CR over every line, the scales exact. */
    boolean trialBalanced(Connection snapshot, CurrencyCode currency);

    /** Whether lending's capital proof - the BNPL principal term among its outstanding ({@code -005}) - holds in {@code currency}. */
    boolean capitalHolds(Connection snapshot, CurrencyCode currency);

    /** Whether credit's exposure census - its BNPL arm ({@code -012}) - holds for every party. */
    boolean exposureHolds(Connection snapshot);

    /** A merchant payable's BNPL-keyed lines and its equation's verdict. */
    record PayableLeg(LedgerAccountId account, List<KeyedLine> lines, boolean equationHolds) {

        public PayableLeg {
            Objects.requireNonNull(account, "account must not be null");
            lines = List.copyOf(lines);
        }

        @Override
        public String toString() {
            return "PayableLeg[" + account.value() + ", " + lines.size() + " lines]";
        }
    }

    /** One line of the payable under a {@code bnpl.} key: its entry, direction, amount and the key's namespace. */
    record KeyedLine(JournalEntryId entry, Direction direction, Money amount, String namespace) {

        public KeyedLine {
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(namespace, "namespace must not be null");
        }

        @Override
        public String toString() {
            return "KeyedLine[" + entry.value() + ", " + namespace + ", " + direction + "]";
        }
    }
}
