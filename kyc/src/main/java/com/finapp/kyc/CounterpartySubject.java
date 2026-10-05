package com.finapp.kyc;

import com.finapp.sharedkernel.money.CountryCode;
import java.util.Objects;

/**
 * Who is screened (`P9-TSK-016`, ADR-0081 point 1): a name, a country and an entity type - and
 * <strong>no bank identifier</strong>. The name is RESTRICTED-PII: it transits {@code crossborder} for
 * the call and is stored only by kyc, encrypted with the screening id as associated data; it is never
 * printed ({@link #toString()} names no part of it).
 */
public record CounterpartySubject(String name, CountryCode country, CounterpartyScreeningVocabulary.EntityType entityType) {

    /** A name's bound, in characters. */
    public static final int MAX_NAME_LENGTH = 140;

    public CounterpartySubject {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(country, "country must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("a counterparty name is 1.." + MAX_NAME_LENGTH + " characters");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("a counterparty name holds no control characters");
        }
    }

    @Override
    public String toString() {
        return "CounterpartySubject[name=<redacted>, country=" + country + ", entityType=" + entityType + "]";
    }
}
