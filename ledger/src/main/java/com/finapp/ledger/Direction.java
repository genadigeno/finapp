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
 * <p>Deliberately no {@code opposite()} yet: reversal (`P3-TSK-016`) is its first caller, and
 * a method with no caller is dead code carrying confident javadoc (the {@code P1-TSK-013}
 * finding). {@code sqlValueList()} arrived with its own first caller, `P3-TSK-005`'s
 * {@code CHECK}.
 */
public enum Direction {
    DEBIT,
    CREDIT;

    /** The sides as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return java.util.Arrays.stream(values())
                .map(side -> "'" + side.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
