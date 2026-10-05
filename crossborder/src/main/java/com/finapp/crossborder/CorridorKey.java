package com.finapp.crossborder;

import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A corridor's stable identity across policy versions (`P9-TSK-015`): the customer pays in
 * {@code source}, the beneficiary receives {@code destination} in {@code country}. Its {@link #code()}
 * - {@code EUR-USD-US} - keys availability, which outlives every version.
 */
public record CorridorKey(CurrencyCode source, CurrencyCode destination, CountryCode country) {

    private static final Pattern CODE = Pattern.compile("([A-Z]{3})-([A-Z]{3})-([A-Z]{2})");

    public CorridorKey {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(country, "country must not be null");
        if (source.equals(destination)) {
            throw new IllegalArgumentException("a corridor converts: its source and destination currencies differ");
        }
    }

    /** {@code S-D-CC}, the shape {@code crossborder V002} holds. */
    public String code() {
        return source.code() + "-" + destination.code() + "-" + country.code();
    }

    /** Parses {@code S-D-CC}; a malformed or unknown code is refused. */
    public static CorridorKey parse(String code) {
        Objects.requireNonNull(code, "code must not be null");
        Matcher matcher = CODE.matcher(code);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("a corridor is named S-D-CC, for example EUR-USD-US");
        }
        return new CorridorKey(
                CurrencyCode.of(matcher.group(1)), CurrencyCode.of(matcher.group(2)), CountryCode.of(matcher.group(3)));
    }

    @Override
    public String toString() {
        return code();
    }
}
