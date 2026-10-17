package com.finapp.merchant;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The BNPL products a merchant can be priced for (`P12-TSK-006`; OQ-8 - a per-merchant schedule per product), merchant's
 * name for bnpl's {@code BnplProduct}.
 *
 * <p><strong>Not a second product catalogue.</strong> merchant has no build edge to {@code bnpl} (and bnpl none to
 * merchant), so the BNPL fee schedule assignment and the fee pin need the product's name on this side of the seam. What a
 * product <em>is</em> - its currency, plans and terms - stays bnpl's; this enum carries only the names, and the
 * correspondence is checked where both types are visible: {@code app}'s {@code BnplVocabularyTest} holds these to
 * exactly bnpl's products, by name (the credit {@code CreditProduct} / bnpl {@code BnplProduct} precedent).
 */
public enum MerchantBnplProduct {

    /** Pay-in-4: four instalments, the first at purchase. */
    BNPL_PAY_IN_4,

    /** Pay-in-N: 3, 6 or 12 monthly instalments. */
    BNPL_PAY_IN_N;

    /** The products as a SQL literal list, for `V010`'s {@code CHECK}s. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(product -> "'" + product.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
