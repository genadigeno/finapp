package com.finapp.sharedkernel.money;

import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * An ISO 3166-1 alpha-2 country, as a value (`P9-TSK-002`, ADR-0074 §10).
 *
 * <p><strong>Why the kernel, and why beside {@link CurrencyCode}.</strong> A corridor is
 * (source currency, destination currency, destination country), and a beneficiary is known by
 * its country beside its currency: the two ISO codes travel together wherever money crosses a
 * border, and every module that will carry one - crossborder, kyc's screening, reporting - would
 * otherwise need a sibling edge or a copy. It is the same representation-primitive argument
 * ADR-0074 records once for {@link ExchangeRate}. It carries no rule: which countries a corridor
 * serves is policy, never this type's.
 *
 * <p><strong>Case is not normalised</strong>, exactly as {@link CurrencyCode} refuses
 * {@code "usd"}: {@code "de"} is refused rather than uppercased.
 *
 * @param code the two-letter uppercase ISO 3166-1 alpha-2 code
 */
public record CountryCode(String code) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Set<String> ISO_3166_ALPHA_2 = Set.of(Locale.getISOCountries());

    public CountryCode {
        Objects.requireNonNull(code, "country code must not be null");
        if (!ISO_3166_ALPHA_2.contains(code)) {
            throw new IllegalArgumentException(
                    "Country code must be an uppercase ISO 3166-1 alpha-2 code, but was: '" + code
                            + "'");
        }
    }

    /** Factory mirroring the canonical constructor, for readability at call sites. */
    public static CountryCode of(String code) {
        return new CountryCode(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
