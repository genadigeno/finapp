package com.finapp.party;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of legal entity a {@link Party} is.
 *
 * <p>Present from the start, and deliberately so. A model that assumed every Party is a person
 * would be unable to represent an organisation without a migration of the table financial records
 * already reference — and `PRODUCT_VISION.md` includes KYB, merchants and beneficial ownership,
 * every one of which is an organisation. The cheap moment to have two kinds is before there is one
 * row.
 *
 * <p>Persisted values, in a generated {@code CHECK} constraint.
 */
public enum PartyKind {

    /** A natural person. */
    PERSON,

    /** A company, partnership, trust or other legal entity that is not a natural person. */
    ORGANISATION;

    /** The kinds as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(kind -> "'" + kind.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
