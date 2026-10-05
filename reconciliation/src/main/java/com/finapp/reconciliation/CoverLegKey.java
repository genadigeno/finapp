package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A cover leg's key is qualified by the leg's own currency (`P9-TSK-012`, carried from
 * `P9-TSK-011`; PHASE_9_PLAN.md section 12.9.1). A cover's two legs quote the SAME references - our
 * {@code T} and the provider's trade reference - in two single-currency files of one source, and
 * {@code expectation_key_once UNIQUE (source_id, key_kind, key_value)} admits a value once per
 * source: unqualified, the second leg's key would be a {@code KEY_COLLISION} and its line would
 * park. Qualified as {@code T-...:EUR} and {@code T-...:USD} - the SAME rule applied by
 * reconciliation's own constructors at both sides, the expectation's from its amount's currency
 * ({@link NewExpectation}) and the item's from its own ({@link ExternalItems.NewItem}) - each leg
 * holds its key, the unique keeps its full strength, and an item can only ever reach the leg in
 * its own currency (reconciliation never converts, {@code INV-REC-08}). The settlement evidence
 * keeps the provider's reference verbatim; this is reconciliation's matching key only.
 */
public final class CoverLegKey {

    private static final char SEPARATOR = ':';

    private CoverLegKey() {}

    /** {@code value} qualified by {@code currency}; already-qualified values are left as they are. */
    public static String qualify(String value, CurrencyCode currency) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        String suffix = SEPARATOR + currency.code();
        return value.endsWith(suffix) ? value : value + suffix;
    }

    /** The reference a qualified key was made from - what the provider and the platform call it. */
    public static String reference(String key) {
        Objects.requireNonNull(key, "key must not be null");
        int at = key.lastIndexOf(SEPARATOR);
        return at > 0 && key.length() - at == 4 ? key.substring(0, at) : key;
    }

    /** Whether an expectation of {@code kind} keys its legs by currency: a cover's two legs. */
    static boolean qualifies(ExpectationKind kind) {
        return kind == ExpectationKind.FX_SELL_LEG || kind == ExpectationKind.FX_BUY_LEG;
    }

    /** Whether a {@code kind} key of a cover leg is qualified: the references both legs share. */
    static boolean qualifies(KeyKind kind) {
        return kind == KeyKind.COVER_REF || kind == KeyKind.FX_TRADE_REF;
    }

    /**
     * Whether an item of {@code lineType} carries cover-leg references: the FX provider's lines -
     * the legs, and the fee naming its leg by {@code ORIGINAL_REF}.
     */
    static boolean qualifies(ExternalLineType lineType) {
        return ExternalLineType.fxVocabulary().contains(lineType);
    }

    /** An FX provider item's keys, its cover references qualified by its currency. */
    static Map<ItemKeyKind, String> qualifyItemKeys(Map<ItemKeyKind, String> keys, CurrencyCode currency) {
        Map<ItemKeyKind, String> qualified = new EnumMap<>(ItemKeyKind.class);
        keys.forEach((kind, value) -> qualified.put(kind,
                kind == ItemKeyKind.COVER_REF || kind == ItemKeyKind.FX_TRADE_REF || kind == ItemKeyKind.ORIGINAL_REF
                        ? qualify(value, currency)
                        : value));
        return qualified;
    }

    /** The currency a qualified key names, if it is one. */
    public static Optional<String> currencyOf(String key) {
        String reference = reference(Objects.requireNonNull(key, "key must not be null"));
        return reference.length() == key.length() ? Optional.empty() : Optional.of(key.substring(reference.length() + 1));
    }
}
