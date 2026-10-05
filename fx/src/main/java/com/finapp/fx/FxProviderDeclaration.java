package com.finapp.fx;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * What a running build declares about one FX provider (`P9-TSK-006`, ADR-0075): its code, the
 * version of this declaration (sourcing steps will record which they judged), the ordered pairs
 * it quotes, the currencies it settles, and the longest firm-quote validity the platform accepts
 * from it. Data, written on the adapter (the {@code SimulatedCardPspAdapter.RAIL} precedent),
 * never a provider detail leaking into the core.
 */
public record FxProviderDeclaration(
        String code,
        int version,
        Set<QuotedPair> pairs,
        Set<CurrencyCode> settledCurrencies,
        Duration maxValidFor) {

    /** An ordered pair the provider quotes: source sold, destination bought. */
    public record QuotedPair(CurrencyCode source, CurrencyCode destination) {
        public QuotedPair {
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            if (source.equals(destination)) {
                throw new IllegalArgumentException("a pair converts between two currencies");
            }
        }
    }

    public FxProviderDeclaration {
        Objects.requireNonNull(code, "code must not be null");
        if (!code.matches("^[a-z][a-z0-9-]{0,31}$")) {
            throw new IllegalArgumentException("a provider code is lower-case kebab: " + code);
        }
        if (version < 1) {
            throw new IllegalArgumentException("a declaration version is positive");
        }
        pairs = Set.copyOf(pairs);
        settledCurrencies = Set.copyOf(settledCurrencies);
        Objects.requireNonNull(maxValidFor, "maxValidFor must not be null");
        if (pairs.isEmpty() || settledCurrencies.isEmpty()) {
            throw new IllegalArgumentException("a provider quotes and settles something");
        }
        if (maxValidFor.isNegative() || maxValidFor.isZero()) {
            throw new IllegalArgumentException("a quote validity bound is positive");
        }
        for (QuotedPair pair : pairs) {
            if (!settledCurrencies.contains(pair.source())
                    || !settledCurrencies.contains(pair.destination())) {
                throw new IllegalArgumentException(
                        "a provider quotes only pairs it settles: " + pair);
            }
        }
    }

    /**
     * The counterparty-owned purpose this provider settles on (`P9-TSK-011`, ADR-0078 section 6):
     * what the provider owes the platform, per currency, on its OWN accounts - read off the
     * declaration by the settlement composition and the counterparty chart, never hand-named
     * ({@code CounterpartyClearingIsNamedByDeclarationsTest}).
     */
    public com.finapp.ledger.AccountPurpose clearingPurpose() {
        return com.finapp.ledger.AccountPurpose.FX_PROVIDER_CLEARING;
    }

    /** Whether the provider quotes {@code source -> destination}. */
    public boolean quotes(CurrencyCode source, CurrencyCode destination) {
        return pairs.contains(new QuotedPair(source, destination));
    }
}
