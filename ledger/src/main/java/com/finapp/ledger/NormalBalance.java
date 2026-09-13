package com.finapp.ledger;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The side on which an account grows (ADR-0040, {@code LEDGER_MODEL.md} §1).
 *
 * <p>A debit is not "money in" and a credit is not "money out": which of those a debit
 * <em>means</em> depends on the account's {@link AccountType#normalBalance() normal balance},
 * which is where the two vocabularies meet. A journal line carries a direction and a positive
 * amount; the sign of its effect on a balance is this enum's to decide, per account.
 *
 * <p>Persisted values, in a generated {@code CHECK} and the coherence rule
 * {@link AccountType#sqlNormalBalanceRule()} ({@code LedgerAccountMigrationTest} reconciles).
 */
public enum NormalBalance {
    DEBIT,
    CREDIT;

    /** The sides as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(side -> "'" + side.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
