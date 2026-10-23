package com.finapp.risk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A rule's typed parameters, held to its kind's schema (`P13-TSK-002`; ADR-0109 decision 2; {@code INV-RSK-05}):
 * exactly the names {@link RuleKind#parameters} declares, no undeclared name, each value of the declared
 * {@link RuleKind.ParameterType} - judged whole at construction, so a parameter outside its schema is
 * {@link RiskPolicy.PolicyInvalid} at the policy door and never stored. The engine (`-003`) reads a value by the name
 * and type the kind declares ({@link #minorAmount}, {@link #window}, ...); a read of a name the kind does not declare is
 * a programming error, never a policy's.
 *
 * <p>The schema lives once, on the kind; this type holds values to it rather than restating it per kind (the entry's
 * "one record per kind" read as the schema's own records, {@link ParameterValue}).
 */
public record RuleParameters(RuleKind kind, Map<String, ParameterValue> values) {

    public RuleParameters {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(values, "values");
        Map<String, ParameterValue> held = new LinkedHashMap<>();
        Set<String> declared = new TreeSet<>();
        for (RuleKind.Parameter parameter : kind.parameters()) {
            declared.add(parameter.name());
            ParameterValue value = values.get(parameter.name());
            if (value == null) {
                throw new RiskPolicy.PolicyInvalid(
                        kind.name() + " requires the parameter '" + parameter.name() + "' (" + parameter.type() + ")");
            }
            if (value.type() != parameter.type()) {
                throw new RiskPolicy.PolicyInvalid(kind.name() + "'s parameter '" + parameter.name() + "' is a "
                        + parameter.type() + ", not a " + value.type());
            }
            held.put(parameter.name(), value);
        }
        for (String name : new TreeSet<>(values.keySet())) {
            if (!declared.contains(name)) {
                throw new RiskPolicy.PolicyInvalid(kind.name() + " declares no parameter '" + name + "'");
            }
        }
        values = Map.copyOf(held);
    }

    /** {@code kind}'s parameters from {@code values}. */
    public static RuleParameters of(RuleKind kind, Map<String, ParameterValue> values) {
        return new RuleParameters(kind, values);
    }

    /** A kind that declares no parameter. */
    public static RuleParameters none(RuleKind kind) {
        return new RuleParameters(kind, Map.of());
    }

    public long count(String name) {
        return ((ParameterValue.Count) declared(name, RuleKind.ParameterType.COUNT)).value();
    }

    public long minorAmount(String name) {
        return ((ParameterValue.MinorAmount) declared(name, RuleKind.ParameterType.MINOR_AMOUNT)).value();
    }

    public int basisPoints(String name) {
        return ((ParameterValue.BasisPoints) declared(name, RuleKind.ParameterType.BASIS_POINTS)).value();
    }

    public int days(String name) {
        return ((ParameterValue.Days) declared(name, RuleKind.ParameterType.DAYS)).value();
    }

    public int hours(String name) {
        return ((ParameterValue.Hours) declared(name, RuleKind.ParameterType.HOURS)).value();
    }

    public WindowKind window(String name) {
        return ((ParameterValue.Window) declared(name, RuleKind.ParameterType.WINDOW)).value();
    }

    public SubjectKind subjectKind(String name) {
        return ((ParameterValue.Subject) declared(name, RuleKind.ParameterType.SUBJECT_KIND)).value();
    }

    public Set<String> corridors(String name) {
        return ((ParameterValue.Corridors) declared(name, RuleKind.ParameterType.CORRIDOR_SET)).sorted();
    }

    public RiskAssurance assurance(String name) {
        return ((ParameterValue.Assurance) declared(name, RuleKind.ParameterType.ASSURANCE)).value();
    }

    public RiskRating rating(String name) {
        return ((ParameterValue.Rating) declared(name, RuleKind.ParameterType.RATING)).value();
    }

    /** The value named {@code name}, whatever its type - for the store and the view. */
    public ParameterValue value(String name) {
        ParameterValue value = values.get(Objects.requireNonNull(name, "name"));
        if (value == null) {
            throw new IllegalArgumentException(kind.name() + " declares no parameter '" + name + "'");
        }
        return value;
    }

    private ParameterValue declared(String name, RuleKind.ParameterType type) {
        ParameterValue value = value(name);
        if (value.type() != type) {
            throw new IllegalArgumentException(kind.name() + "'s parameter '" + name + "' is a " + value.type());
        }
        return value;
    }

    @Override
    public String toString() {
        // Never the values: a threshold is the policy's content (CONFIDENTIAL), and a toString reaches logs.
        return "RuleParameters[" + kind + ", " + values.size() + " parameters]";
    }
}
