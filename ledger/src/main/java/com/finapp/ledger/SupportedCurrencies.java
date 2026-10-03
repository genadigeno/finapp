package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * The minor units every currency the platform posts or prices is PINNED to (`P9-TSK-002`,
     * ADR-0074 §9) - EUR 2, GBP 2, USD 2, JPY 0, BHD 3 - covering {@link #ALL} and the two
     * currencies Phase 9 makes postable (`P9-TSK-003`).
     *
     * <p><strong>Why a pin, when {@code CurrencyCode.minorUnits()} already answers.</strong> Its
     * answer is the running JDK's ISO 4217 data, which changes between JDK versions. A stored
     * amount keeps its own scale ({@code INV-MON-05}), so a JDK that moved a currency's minor units
     * would make every new amount disagree with history, and {@code Money.plus} would throw
     * {@code ScaleMismatchException} on the first sum across the boundary. The pin turns that
     * silent drift into a refused startup ({@code SupportedCurrencyMinorUnitsGuard}) and a failed
     * build ({@code SupportedCurrencyMinorUnitsArePinnedTest}). There is deliberately no currency
     * table: the minor units ARE the JDK's, checked, never copied into a second source of truth.
     */
    public static final Map<CurrencyCode, Integer> PINNED_MINOR_UNITS = pinned();

    private SupportedCurrencies() {}

    private static Map<CurrencyCode, Integer> pinned() {
        Map<CurrencyCode, Integer> pins = new LinkedHashMap<>();
        pins.put(CurrencyCode.of("EUR"), 2);
        pins.put(CurrencyCode.of("GBP"), 2);
        pins.put(CurrencyCode.of("USD"), 2);
        pins.put(CurrencyCode.of("JPY"), 0);
        pins.put(CurrencyCode.of("BHD"), 3);
        return java.util.Collections.unmodifiableMap(pins);
    }
}
