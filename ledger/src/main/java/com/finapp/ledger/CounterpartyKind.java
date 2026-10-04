package com.finapp.ledger;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What a registered counterparty is (`P9-TSK-010`, ADR-0078 section 2). Persisted values, in a
 * generated {@code CHECK} on {@code ledger.counterparty} ({@code LedgerAccountMigrationTest}
 * reconciles).
 */
public enum CounterpartyKind {

    /** A provider the platform covers conversions with (ADR-0075, ADR-0077). */
    FX_PROVIDER,

    /** A provider the platform sends cross-border credits through (ADR-0080). */
    CORRIDOR_PROVIDER;

    /** The kinds as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(kind -> "'" + kind.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
