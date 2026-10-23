package com.finapp.risk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A signal's attributes: exactly the ones its {@link SignalKind} declares, each in its type's bound, in canonical
 * text (`P13-TSK-005`). Built only through {@link #of}, which validates against the kind's schema - so a
 * {@link RiskSignal} holding one of these holds nothing the kind did not declare and nothing outside a bound, and no
 * description, name or amount can be among them ({@code INV-AUD-02}; OQ-4).
 */
public final class SignalAttributes {

    private static final SignalAttributes EMPTY = new SignalAttributes(Map.of());

    private final Map<String, String> values;

    private SignalAttributes(Map<String, String> values) {
        this.values = values;
    }

    /** No attributes - a kind that declares none. */
    public static SignalAttributes none() {
        return EMPTY;
    }

    /**
     * The attributes of a signal of {@code kind}.
     *
     * @throws IllegalArgumentException if an attribute the kind declares is missing, one it does not declare is
     *     present, or a value is outside its type's bound. The message names the attribute and never its value:
     *     the value may be what the producer should not have sent
     */
    public static SignalAttributes of(SignalKind kind, Map<String, String> values) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(values, "values");
        Map<String, String> admitted = new LinkedHashMap<>();
        for (SignalKind.Attribute attribute : kind.attributes()) {
            String value = values.get(attribute.name());
            if (value == null) {
                throw new IllegalArgumentException(
                        "a " + kind + " signal carries " + attribute.name() + ", which is missing");
            }
            if (!attribute.type().admits(value)) {
                throw new IllegalArgumentException(
                        "a " + kind + " signal's " + attribute.name() + " is a " + attribute.type()
                                + ", and the value given is not one within its bound");
            }
            admitted.put(attribute.name(), value);
        }
        for (String name : values.keySet()) {
            if (!admitted.containsKey(name)) {
                throw new IllegalArgumentException("a " + kind + " signal does not carry " + name);
            }
        }
        return admitted.isEmpty() ? EMPTY : new SignalAttributes(Collections.unmodifiableMap(admitted));
    }

    /** The attribute's canonical text, if the signal carries it. */
    public Optional<String> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    /** Every attribute, in the kind's declared order. */
    public Map<String, String> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SignalAttributes that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /** The names only: an attribute's value is a producer's code or date, never a log line's business by default. */
    @Override
    public String toString() {
        return "SignalAttributes" + values.keySet();
    }
}
