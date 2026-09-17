package com.finapp.ledger;

/**
 * The side a journal line posts on (`P3-TSK-004`, {@code LEDGER_MODEL.md} §1).
 *
 * <p><strong>Direction is an enum, never a signed amount</strong> — the decision the plan states
 * outright (§5): a signed amount makes "unbalanced" a subtraction that happens to be non-zero,
 * while a direction makes it two sums that must be equal, which is the property
 * {@code INV-LED-01} actually states and the one a database {@code CHECK} can carry
 * (`P3-TSK-005`). Amounts are therefore always positive, and this enum is where the sign lives.
 *
 * <p>A debit is not "money in": which of those it means depends on the account's
 * {@link NormalBalance}. The two vocabularies meet at the balance derivation
 * (`P3-TSK-008`), never here.
 *
 * <p>{@code opposite()} arrived with its promised first caller — reversal (`P3-TSK-016`), a
 * new entry whose lines are the original's with directions swapped; {@code sqlValueList()}
 * arrived with `P3-TSK-005`'s {@code CHECK}. Each waited for the caller whose design earned
 * it (the {@code P1-TSK-013} finding).
 */
public enum Direction {
    DEBIT,
    CREDIT;

    /** The other side — {@code DEBIT.opposite()} is {@code CREDIT} and vice versa. */
    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    /** The sides as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return java.util.Arrays.stream(values())
                .map(side -> "'" + side.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
