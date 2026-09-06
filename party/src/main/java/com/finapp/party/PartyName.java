package com.finapp.party;

import java.util.Objects;
import java.util.regex.Pattern;

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
 *
 * <p><strong>The one exception is a control character, and it is an exception for a reason that is
 * not about injection</strong> (`P1-TSK-006`). A name is text a person typed; a NUL, a carriage
 * return or a bidirectional override is not part of anybody's name, so refusing them rejects
 * nothing legitimate - which is precisely the test the paragraph above applies and the reason a
 * charset restriction fails it. Two things follow. A NUL cannot be stored in a PostgreSQL
 * {@code text} column at all, so accepting one turns a caller's mistake into a 500 from three
 * layers down; and a CR/LF sitting in a {@code RESTRICTED-PII} column is a forged log line waiting
 * for the first component that ever prints a name, which is the weak point
 * {@code DATA_CLASSIFICATION.md} §5 names in this exact scheme.
 *
 * <p>Excluded are the Unicode categories {@code Cc} (control), {@code Cf} (format, which is where
 * the bidirectional overrides live), {@code Cs} (unpaired surrogates), {@code Co} (private use) and
 * {@code Cn} (unassigned). Every letter, mark, digit, punctuation mark, space and symbol - emoji
 * included, since a valid surrogate <em>pair</em> is one code point and not {@code Cs} - is
 * untouched.
 */
public record PartyName(String value) {

    /** Long enough for a full legal name of an organisation; short enough to bound a row. */
    public static final int MAX_LENGTH = 200;

    /**
     * Anything that is not a control, format, surrogate, private-use or unassigned code point.
     *
     * <p>Stated as what is <em>excluded</em> rather than what is allowed. An allow-list of scripts
     * is the rule this type exists to refuse; a deny-list of five Unicode categories that contain
     * no character of any name is the opposite kind of rule, and it is exhaustive by construction
     * rather than by anybody remembering a script.
     */
    private static final Pattern FORBIDDEN =
            Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}]");

    public PartyName {
        Objects.requireNonNull(value, "name must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "name must be at most " + MAX_LENGTH + " characters but was " + value.length());
        }
        if (FORBIDDEN.matcher(value).find()) {
            // The message never repeats the value: it is RESTRICTED-PII, and an exception message
            // reaches a log line (INV-AUD-02). Which character offended is not said either, for the
            // same reason - it would echo the input one code point at a time.
            throw new IllegalArgumentException("name must not contain control characters");
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
