package com.finapp.ledger;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Whose account a ledger account is (ADR-0040, ADR-0042).
 *
 * <p>The distinction is not decoration: <strong>mixing a customer's money and the platform's own
 * is how a shortfall becomes invisible</strong> ({@code LEDGER_MODEL.md} §1). Every customer
 * credit is a platform liability — both sides of a double entry are accounts, and a model with
 * only customer accounts cannot post anything.
 *
 * <p>The kind is <em>derived from the purpose</em> ({@link AccountPurpose#ownerKind()}) and
 * stored, the {@code normal_balance} pattern: a wallet is a customer's by definition and an FX
 * position the platform's, so letting a caller pick the pair independently only creates invalid
 * states for a {@code CHECK} to refuse.
 *
 * <p>Persisted values, in a generated {@code CHECK} ({@code LedgerAccountMigrationTest}
 * reconciles).
 */
public enum OwnerKind {

    /** Owned by a customer account (the product). {@code owner_ref} names it — required. */
    CUSTOMER,

    /** The platform's own position: clearing, fees, FX, rounding. No owner, ever. */
    OPERATIONAL,

    /**
     * Value parked because it could not be attributed — the Phase 8 seam ({@code INV-REC-05}).
     * Deliberately not {@code OPERATIONAL}: suspense is a question awaiting an answer, not a
     * position the platform holds on purpose, and folding the two together is how parked value
     * stops being tracked and aged.
     */
    SUSPENSE;

    /** True when an account of this kind names the customer account it belongs to. */
    public boolean requiresOwnerRef() {
        return this == CUSTOMER;
    }

    /** The kinds as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(kind -> "'" + kind.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
