package com.finapp.ledger;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The five classifications double-entry accounting has (ADR-0040).
 *
 * <p><strong>This enum is fixed by accounting, not by us.</strong> Assets, liabilities, equity,
 * revenue and expenses are the whole of the balance sheet and the income statement; a sixth
 * member is not a modelling decision this platform can take. What grows over phases is
 * {@link AccountPurpose} — the engineer's question of <em>which</em> account to post to — never
 * this, the accountant's question of which side of the balance sheet it sits on.
 *
 * <p><strong>The normal balance is derived here, in one function.</strong> Which direction
 * increases an account is a fact about its type — assets and expenses grow by debit, the rest by
 * credit — and {@code INV-LED-06} requires it stored and immutable once posted to, because a
 * derived value cannot be constrained. So it is derived once at construction, stored, and the
 * schema {@code CHECK} generated from {@link #sqlNormalBalanceRule()} keeps the stored pair
 * honest even against a writer that never ran this code.
 *
 * <p>Persisted values, in a generated {@code CHECK} ({@code P0-TSK-022};
 * {@code LedgerAccountMigrationTest} reconciles).
 */
public enum AccountType {

    /** Value the platform holds or is owed. Grows by debit. */
    ASSET,

    /** Value the platform owes — every customer credit is one. Grows by credit. */
    LIABILITY,

    /** The residual: assets minus liabilities. Grows by credit. */
    EQUITY,

    /** Value earned. Grows by credit. */
    REVENUE,

    /** Value consumed. Grows by debit. */
    EXPENSE;

    /** The direction that increases an account of this type. One definition, stored per row. */
    public NormalBalance normalBalance() {
        return switch (this) {
            case ASSET, EXPENSE -> NormalBalance.DEBIT;
            case LIABILITY, EQUITY, REVENUE -> NormalBalance.CREDIT;
        };
    }

    /** The types as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(type -> "'" + type.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The type→normal-balance derivation as a SQL predicate, for the coherence {@code CHECK}.
     *
     * <p>Generated so the derivation has one definition with two artefacts: a stored pair the
     * domain never produced — an {@code ASSET} that grows by credit — is refused at the write,
     * whoever the writer is.
     */
    public static String sqlNormalBalanceRule() {
        return Arrays.stream(values())
                .map(
                        type ->
                                "(account_type = '"
                                        + type.name()
                                        + "' AND normal_balance = '"
                                        + type.normalBalance().name()
                                        + "')")
                .collect(Collectors.joining(" OR "));
    }
}
