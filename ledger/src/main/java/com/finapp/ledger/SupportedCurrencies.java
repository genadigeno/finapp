package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.List;

/**
 * The currencies the ledger operates in (`P3-TSK-003`).
 *
 * <p><strong>This set is the one definition, and the seed migration derives from it.</strong>
 * Every currency here has the full operational chart — clearing, fee revenue, FX position,
 * rounding residual, suspense — because a posting in a currency with no residual account has
 * nowhere to put what allocation leaves over ({@code INV-BAL-03}), and one with no suspense
 * account gives Phase 8 nowhere to park what it cannot match ({@code INV-REC-05}). "Supported"
 * therefore means <em>postable</em>, not merely representable: {@code CurrencyCode} accepts any
 * ISO 4217 currency, and that stays true — a historical row in a currency this list no longer
 * names must still read back ({@code INV-MON-05}).
 *
 * <p><strong>Three, deliberately, and jurisdiction-neutral</strong> ({@code PRODUCT_VISION.md}):
 * one currency would let per-purpose assumptions creep in unexercised — the per-currency
 * structure is the Phase 9 seam — and a national default would be a jurisdiction decision Phase
 * 3 has no business taking. <strong>Growing the set is a new seed migration</strong>, a reviewed
 * act (the {@code AccountPurpose} growth pattern): add the currency here, seed its operational
 * accounts in the same change, and {@code OperationalChartMigrationTest} refuses the build until
 * both halves agree.
 */
public final class SupportedCurrencies {

    /** Ordered for stable iteration in tests and generated artefacts; the order means nothing. */
    public static final List<CurrencyCode> ALL =
            List.of(CurrencyCode.of("EUR"), CurrencyCode.of("GBP"), CurrencyCode.of("USD"));

    private SupportedCurrencies() {}
}
