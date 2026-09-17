package com.finapp.accounts;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The kind of product a {@link CustomerAccount} is — the axis of "one live account per customer
 * per product type" (`P3-TSK-012`), so it is part of the partial unique index's key.
 *
 * <p><strong>One value, deliberately.</strong> Phase 3's customer product is the stored-value
 * account: {@code AccountPurpose.CUSTOMER_WALLET} is the only customer-owned ledger purpose the
 * phase defines, and `PHASE_3_PLAN.md` §4 gives the wallet no funding rails — top-up and
 * withdrawal are Phase 4/5's. A second product type is a decision, made loud by the generated
 * {@code CHECK}: adding a constant without its migration fails
 * {@code CustomerAccountMigrationTest}.
 *
 * <p><strong>Why a product type and not a second aggregate.</strong> ADR-0042 sketches Wallet
 * as its own aggregate <em>when it acquires a lifecycle that is not the account's</em> — that is
 * the module note's recorded split trigger. Today it has none: a second aggregate would carry an
 * identical machine over an identical table shape, which is the premature boundary the ADR
 * itself rejects at module level, one level down. The type is the seam the split would start
 * from (`P3-TSK-012`'s recorded decision; the plan carries the provenance note).
 */
public enum ProductType {

    /** Stored value the platform holds for the customer (`AccountPurpose.CUSTOMER_WALLET`). */
    WALLET;

    /** The types as a SQL literal list, for the {@code CHECK} constraint — one definition. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(type -> "'" + type.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
