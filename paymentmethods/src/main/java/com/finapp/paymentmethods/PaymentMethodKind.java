package com.finapp.paymentmethods;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of instrument a {@link PaymentMethod} row holds (`P7-TSK-007`, ADR-0062 §2) — the
 * frozen birth fact every per-kind rule keys on, in the aggregate's constructor and in
 * {@code V003}'s coherence {@code CHECK}s alike.
 *
 * <p><strong>Deliberately the same words as {@code payments}' routing vocabulary</strong>
 * ({@code InstrumentKind.CARD_TOKEN}/{@code BANK_ACCOUNT}), and deliberately a separate enum:
 * this module sees no business sibling in either direction (the PCI build-graph decision
 * {@link TokenReference} records), so the vocabulary is restated, not imported — matching
 * words keep the `P7-TSK-008` seam a rename-free mapping. {@code WALLET} is absent on
 * purpose: the wallet is the platform's own book, never a stored instrument row.
 */
public enum PaymentMethodKind {

    /** A tokenised card (`P5-TSK-004`): token, brand and expiry present, bank facts absent. */
    CARD_TOKEN,

    /**
     * An external bank account through the grant exchange (`P7-TSK-007`, {@code INV-RAIL-03}):
     * destination reference and payee check present, card facts absent.
     */
    BANK_ACCOUNT;

    /** The values as a SQL literal list — {@code V003}'s kind {@code CHECK} is generated
     * from this, and {@code PaymentMethodMigrationTest} fails the build on disagreement. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
