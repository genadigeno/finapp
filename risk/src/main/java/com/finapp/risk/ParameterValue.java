package com.finapp.risk;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * One typed value of a rule parameter (`P13-TSK-002`; ADR-0109 decision 2) - one record per
 * {@link RuleKind.ParameterType}, each judged within its type's bounds at construction, so a value the engine could not
 * read is never built, let alone stored. Which values a rule needs is its kind's schema ({@link RuleKind#parameters});
 * {@link RuleParameters} holds a rule's values to it.
 */
public sealed interface ParameterValue {

    /** The type this value is of - matched against the kind's declared parameter type. */
    RuleKind.ParameterType type();

    /** A non-negative count. */
    record Count(long value) implements ParameterValue {
        public Count {
            if (value < 0) {
                throw new RiskPolicy.PolicyInvalid("a count is non-negative");
            }
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.COUNT;
        }
    }

    /** An amount in minor units of the policy version's currency, non-negative. */
    record MinorAmount(long value) implements ParameterValue {
        public MinorAmount {
            if (value < 0) {
                throw new RiskPolicy.PolicyInvalid("an amount parameter is non-negative");
            }
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.MINOR_AMOUNT;
        }
    }

    /** A share in basis points, 0 to 10000. */
    record BasisPoints(int value) implements ParameterValue {
        public BasisPoints {
            if (value < 0 || value > 10_000) {
                throw new RiskPolicy.PolicyInvalid("a ratio is 0 to 10000 basis points");
            }
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.BASIS_POINTS;
        }
    }

    /** A number of days, positive. */
    record Days(int value) implements ParameterValue {
        public Days {
            if (value < 1) {
                throw new RiskPolicy.PolicyInvalid("a number of days is positive");
            }
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.DAYS;
        }
    }

    /** A number of hours, positive. */
    record Hours(int value) implements ParameterValue {
        public Hours {
            if (value < 1) {
                throw new RiskPolicy.PolicyInvalid("a number of hours is positive");
            }
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.HOURS;
        }
    }

    /** A velocity window. */
    record Window(WindowKind value) implements ParameterValue {
        public Window {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.WINDOW;
        }
    }

    /** A velocity dimension (`P13-TSK-006`; it replaced the {@code SubjectKind} value `-002` first stored). */
    record Dimension(VelocityDimension value) implements ParameterValue {
        public Dimension {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.VELOCITY_DIMENSION;
        }
    }

    /**
     * A non-empty set of corridor codes, each in crossborder's shape {@code SRC-DST-CC} (its {@code CorridorKey}: source
     * currency, destination currency, country) - mirrored as a shape here, because risk cannot see {@code crossborder}
     * and a corridor reaches an evaluation only as its code.
     */
    record Corridors(Set<String> value) implements ParameterValue {
        /** {@code CorridorKey}'s code shape. */
        public static final Pattern CODE = Pattern.compile("[A-Z]{3}-[A-Z]{3}-[A-Z]{2}");

        public Corridors {
            Objects.requireNonNull(value, "value");
            if (value.isEmpty()) {
                throw new RiskPolicy.PolicyInvalid("a corridor set names at least one corridor");
            }
            for (String code : value) {
                if (code == null || !CODE.matcher(code).matches()) {
                    throw new RiskPolicy.PolicyInvalid("a corridor is named SRC-DST-CC, for example EUR-USD-US");
                }
            }
            value = Set.copyOf(new TreeSet<>(value));
        }

        /** The codes in their natural order - the engine reads them sorted, for determinism. */
        public Set<String> sorted() {
            return new TreeSet<>(value);
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.CORRIDOR_SET;
        }
    }

    /** A session assurance. */
    record Assurance(RiskAssurance value) implements ParameterValue {
        public Assurance {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.ASSURANCE;
        }
    }

    /** A customer risk rating. */
    record Rating(RiskRating value) implements ParameterValue {
        public Rating {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public RuleKind.ParameterType type() {
            return RuleKind.ParameterType.RATING;
        }
    }
}
