package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * One account, one direction, one positive amount (`P3-TSK-004`, {@code LEDGER_MODEL.md} §1).
 *
 * <p><strong>Not a signed number.</strong> The amount is strictly positive and
 * {@link Direction} carries the sign — a negative amount would be a credit wearing a debit's
 * clothes, and a zero amount asserts nothing about anything, so both are refused at
 * construction. The schema restates the rule for writers that never run this code
 * ({@code CHECK (amount_minor > 0)}, `P3-TSK-005`).
 *
 * <p>Two lines may name the same account — a split posting is ordinary accounting — and the
 * line's position in its entry ({@code seq}) is a persistence artefact, not a domain fact: the
 * entry's list order is the order.
 *
 * @param account the ledger account posted to, by identifier — the line does not hold the
 *     account, because validating the account's state (open, matching currency) is the posting
 *     command's job against authoritative rows, not a construction-time check against a copy
 * @param direction which side the amount posts on
 * @param amount strictly positive; currency and scale explicit ({@code INV-MON-02})
 */
public record JournalLine(LedgerAccountId account, Direction direction, Money amount) {

    public JournalLine {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "a journal line's amount must be strictly positive: the direction carries"
                            + " the sign, and a zero line asserts nothing");
        }
    }

    /**
     * No amount, deliberately ({@code INV-AUD-02}): a record's generated {@code toString}
     * prints every component, and a journal amount is {@code RESTRICTED-FINANCIAL} — one
     * incautious log statement away from a log aggregator with months of retention.
     */
    @Override
    public String toString() {
        return "JournalLine[" + account + ", " + direction + ", " + amount.currency() + "]";
    }
}
