package com.finapp.party;

import java.util.Objects;

/**
 * A Party's display name.
 *
 * <p><strong>Bounded and non-blank, and nothing else.</strong> No charset restriction, no
 * structure, no split into given and family names. Names are one of the areas where a validation
 * rule that felt reasonable is simply wrong about real people: names contain apostrophes, hyphens,
 * spaces, accents, non-Latin scripts, and any number of parts including one. A rule narrow enough
 * to be a control here would reject legitimate customers, which is a worse outcome than the thing
 * it was guarding against.
 *
 * <p>What <em>is</em> enforced is the pair of properties the platform actually depends on: it is
 * present, and it is bounded. The length bound matches the column, so a value that constructs here
 * always stores.
 *
 * <p><strong>The injection concern is handled elsewhere, on purpose.</strong> A name reaching a log
 * line is prevented by {@link Party#toString()} omitting it and by the classification, not by
 * forbidding characters here; a name reaching SQL is prevented by parameter binding (ADR-0033).
 * Sanitising input at construction to defend an output is how a value gets silently corrupted for
 * every consumer to protect one.
 */
public record PartyName(String value) {

    /** Long enough for a full legal name of an organisation; short enough to bound a row. */
    public static final int MAX_LENGTH = 200;

    public PartyName {
        Objects.requireNonNull(value, "name must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "name must be at most " + MAX_LENGTH + " characters but was " + value.length());
        }
    }

    /**
     * Deliberately masked.
     *
     * <p>A record's generated {@code toString} prints every component, which is how a
     * {@code RESTRICTED-PII} value reaches a log line with nobody writing a log statement about it —
     * the same accident {@code Sensitive<T>} exists to prevent for secrets ({@code INV-AUD-02},
     * ADR-0019). The value is available through {@link #value()} to code that has decided it needs
     * it.
     */
    @Override
    public String toString() {
        return "PartyName[***]";
    }
}
