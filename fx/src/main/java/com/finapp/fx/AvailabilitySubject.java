package com.finapp.fx;

import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.Objects;
import java.util.Set;

/**
 * What an availability fact or an enable request is about (`P9-TSK-007`): a directional pair
 * ({@code EUR-USD} - the hyphen, because the value travels in a URL path and an event payload) or
 * a declared provider code.
 */
public record AvailabilitySubject(Kind kind, String subject) {

    /** Which kind of subject. */
    public enum Kind {
        PAIR,
        PROVIDER
    }

    public AvailabilitySubject {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
    }

    /**
     * A pair subject from its path form {@code EUR-USD}: two different postable currencies.
     *
     * @throws IllegalArgumentException for any other shape
     */
    public static AvailabilitySubject pair(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        if (!raw.matches("^[A-Z]{3}-[A-Z]{3}$")) {
            throw new IllegalArgumentException("a pair is BASE-QUOTE, two ISO 4217 codes");
        }
        CurrencyCode source = CurrencyCode.of(raw.substring(0, 3));
        CurrencyCode destination = CurrencyCode.of(raw.substring(4));
        if (source.equals(destination)
                || !SupportedCurrencies.ALL.contains(source)
                || !SupportedCurrencies.ALL.contains(destination)) {
            throw new IllegalArgumentException("a pair converts between two postable currencies");
        }
        return new AvailabilitySubject(Kind.PAIR, raw);
    }

    /**
     * A provider subject: a code the running build declares.
     *
     * @throws IllegalArgumentException for an undeclared code
     */
    public static AvailabilitySubject provider(String code, Set<String> declared) {
        Objects.requireNonNull(code, "code must not be null");
        if (!declared.contains(code)) {
            throw new IllegalArgumentException("not a provider this build declares");
        }
        return new AvailabilitySubject(Kind.PROVIDER, code);
    }

    /** The pair's source currency; only for a pair subject. */
    public CurrencyCode source() {
        requirePair();
        return CurrencyCode.of(subject.substring(0, 3));
    }

    /** The pair's destination currency; only for a pair subject. */
    public CurrencyCode destination() {
        requirePair();
        return CurrencyCode.of(subject.substring(4));
    }

    private void requirePair() {
        if (kind != Kind.PAIR) {
            throw new IllegalStateException("a provider subject has no currencies");
        }
    }
}
