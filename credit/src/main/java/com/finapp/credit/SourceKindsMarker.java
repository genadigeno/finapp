package com.finapp.credit;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A marker's value naming the source kinds it is about (`P10-TSK-008`): a snapshot holds one value per attribute code,
 * so {@code SOURCE_UNAVAILABLE} and {@code CURRENCY_NOT_SUPPORTED} name every affected kind in ONE canonical code - the
 * kinds in declaration order joined by {@code _AND_} ({@code BUREAU}, {@code FINANCIAL_DATA},
 * {@code BUREAU_AND_FINANCIAL_DATA}). Deterministic, so the canonical snapshot is.
 */
public final class SourceKindsMarker {

    private static final String JOIN = "_AND_";

    private SourceKindsMarker() {}

    /** The marker value for {@code kinds} - at least one. */
    public static AttributeValue.CodeValue of(Set<CreditSourceKind> kinds) {
        Objects.requireNonNull(kinds, "kinds");
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("a marker names at least one source kind");
        }
        return new AttributeValue.CodeValue(EnumSet.copyOf(kinds).stream().map(Enum::name).collect(Collectors.joining(JOIN)));
    }

    /** The kinds a marker value names. */
    public static Set<CreditSourceKind> kindsOf(AttributeValue.CodeValue value) {
        Objects.requireNonNull(value, "value");
        Set<CreditSourceKind> kinds = EnumSet.noneOf(CreditSourceKind.class);
        String rest = value.value();
        for (CreditSourceKind kind : CreditSourceKind.values()) {
            if (rest.equals(kind.name()) || rest.startsWith(kind.name() + JOIN)) {
                kinds.add(kind);
                rest = rest.equals(kind.name()) ? "" : rest.substring(kind.name().length() + JOIN.length());
            }
        }
        if (!rest.isEmpty() || kinds.isEmpty()) {
            throw new IllegalArgumentException("not a source-kinds marker value");
        }
        return kinds;
    }
}
