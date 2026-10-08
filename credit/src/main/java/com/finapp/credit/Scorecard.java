package com.finapp.credit;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A scorecard model version's points table (`P10-TSK-011`; ADR-0086 section 3, PHASE_10_PLAN.md section 12.5;
 * {@code INV-CRD-05}, {@code INV-CRD-04}): a base, and for each scored attribute an {@code ABSENT} band and ordered
 * value bands, each with integer points.
 *
 * <pre>
 * score = base + the points of the band each scored attribute falls in    -- integer arithmetic only
 * </pre>
 *
 * <p><strong>What a band is.</strong> An {@link AttributeValueType#INTEGER} attribute takes {@link Range}s -
 * {@code [lower, upper)}, an absent bound unbounded - that are contiguous, non-overlapping and cover every value, so an
 * integer always falls in exactly one. A {@link AttributeValueType#BOOLEAN} or {@link AttributeValueType#CODE}
 * attribute takes disjoint {@link Codes} sets (a boolean's are {@code true} and {@code false}, both covered). Money is
 * affordability's and exposure's, never a band, and the markers are not scored. Every scored attribute declares its
 * absent band: an {@code ABSENT} value takes it, never a zero. A code no set holds is {@link UnscoredValue} - an
 * error, never a default.
 *
 * <p>The table is validated whole at construction ({@link ScorecardInvalid}), so a version that exists is one that
 * scores every snapshot of its vocabulary; the database holds the shapes again ({@code credit V006}).
 */
public record Scorecard(int basePoints, List<AttributeBands> attributes) {

    /** A table's points are bounded so a score never overflows an {@code int}. */
    public static final int POINTS_BOUND = 100_000;

    /** At most this many value bands per attribute. */
    public static final int MAXIMUM_BANDS = 32;

    private static final Pattern CODE_SHAPE = Pattern.compile("[A-Z0-9_]{1,64}|true|false");

    public Scorecard {
        Objects.requireNonNull(attributes, "attributes");
        bounded("the base", basePoints);
        if (attributes.isEmpty()) {
            throw new ScorecardInvalid("a scorecard scores at least one attribute");
        }
        Set<CreditAttributeCode> seen = EnumSet.noneOf(CreditAttributeCode.class);
        for (AttributeBands attribute : attributes) {
            Objects.requireNonNull(attribute, "attribute");
            if (!seen.add(attribute.code())) {
                throw new ScorecardInvalid(attribute.code() + " is banded twice");
            }
        }
        attributes = attributes.stream().sorted(java.util.Comparator.comparing(a -> a.code().name())).toList();
    }

    /** The score of {@code snapshot} - integer, exact. */
    public int score(SnapshotContent snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        int score = basePoints;
        for (AttributeBands attribute : attributes) {
            score = Math.addExact(score, attribute.pointsFor(snapshot.attribute(attribute.code()).value()));
        }
        return score;
    }

    /** One attribute's bands: its absent band's points and its value bands in order. */
    public record AttributeBands(CreditAttributeCode code, int absentPoints, List<Band> bands) {

        public AttributeBands {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(bands, "bands");
            bands = List.copyOf(bands);
            bounded(code + "'s absent band", absentPoints);
            if (code.marker()) {
                throw new ScorecardInvalid(code + " is a marker, not a scored attribute");
            }
            if (bands.isEmpty() || bands.size() > MAXIMUM_BANDS) {
                throw new ScorecardInvalid(code + " has 1 to " + MAXIMUM_BANDS + " value bands beside its absent band");
            }
            for (Band band : bands) {
                bounded(code + "'s band", Objects.requireNonNull(band, "band").points());
            }
            switch (code.valueType()) {
                case INTEGER -> ranges(code, bands);
                case BOOLEAN, CODE -> codes(code, bands);
                case MONEY -> throw new ScorecardInvalid(code + " is money - affordability's and exposure's, never a band");
            }
        }

        int pointsFor(AttributeValue value) {
            if (value instanceof AttributeValue.Absent) {
                return absentPoints;
            }
            for (Band band : bands) {
                if (band.holds(value)) {
                    return band.points();
                }
            }
            throw new UnscoredValue(code);
        }

        private static void ranges(CreditAttributeCode code, List<Band> bands) {
            Long expectedLower = null;
            for (int i = 0; i < bands.size(); i++) {
                if (!(bands.get(i) instanceof Range range)) {
                    throw new ScorecardInvalid(code + " is an integer attribute: its bands are ranges");
                }
                if (!Objects.equals(range.lower(), expectedLower)) {
                    throw new ScorecardInvalid(code + "'s ranges are contiguous from an unbounded lower edge: band "
                            + (i + 1) + " starts where band " + i + " ends");
                }
                boolean last = i == bands.size() - 1;
                if (last != (range.upper() == null)) {
                    throw new ScorecardInvalid(code + "'s last range, and only its last, is unbounded above");
                }
                if (range.lower() != null && range.upper() != null && range.lower() >= range.upper()) {
                    throw new ScorecardInvalid(code + "'s range " + (i + 1) + " is empty");
                }
                expectedLower = range.upper();
            }
        }

        private static void codes(CreditAttributeCode code, List<Band> bands) {
            Set<String> seen = new java.util.HashSet<>();
            for (Band band : bands) {
                if (!(band instanceof Codes set)) {
                    throw new ScorecardInvalid(code + " is a " + code.valueType() + " attribute: its bands are code sets");
                }
                for (String member : set.codes()) {
                    if (!CODE_SHAPE.matcher(member).matches()) {
                        throw new ScorecardInvalid(code + "'s code sets hold codes - upper-case letters, digits, underscores");
                    }
                    if (!seen.add(member)) {
                        throw new ScorecardInvalid(code + "'s code sets overlap");
                    }
                }
            }
            if (code.valueType() == AttributeValueType.BOOLEAN && !seen.equals(Set.of("true", "false"))) {
                throw new ScorecardInvalid(code + " is a boolean: its sets cover exactly true and false");
            }
        }
    }

    /** A value band. */
    public sealed interface Band permits Range, Codes {

        int points();

        boolean holds(AttributeValue value);
    }

    /** {@code [lower, upper)} over an integer; a {@code null} bound is unbounded. */
    public record Range(Long lower, Long upper, int points) implements Band {
        @Override
        public boolean holds(AttributeValue value) {
            long integer = ((AttributeValue.IntegerValue) value).value();
            return (lower == null || integer >= lower) && (upper == null || integer < upper);
        }
    }

    /** A set of codes - a boolean's {@code true} or {@code false} among them. */
    public record Codes(Set<String> codes, int points) implements Band {
        public Codes {
            Objects.requireNonNull(codes, "codes");
            codes = Set.copyOf(codes);
            if (codes.isEmpty() || codes.size() > 64) {
                throw new ScorecardInvalid("a code set holds 1 to 64 codes");
            }
        }

        @Override
        public boolean holds(AttributeValue value) {
            String code = switch (value) {
                case AttributeValue.BooleanValue bool -> Boolean.toString(bool.value());
                case AttributeValue.CodeValue text -> text.value();
                default -> null;
            };
            return code != null && codes.contains(code);
        }

        /** The members in their stored, canonical order. */
        public List<String> sorted() {
            List<String> sorted = new ArrayList<>(codes);
            java.util.Collections.sort(sorted);
            return sorted;
        }
    }

    /** The table is not well formed; the message names the defect - never a value. */
    public static final class ScorecardInvalid extends IllegalArgumentException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ScorecardInvalid(String defect) {
            super(defect);
        }
    }

    /** A code no band of its attribute holds - the evaluation's error, never a zero ({@code INV-CRD-05}). */
    public static final class UnscoredValue extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        UnscoredValue(CreditAttributeCode code) {
            super("the value of " + code + " falls in no band of the pinned scorecard");
        }
    }

    private static void bounded(String what, int points) {
        if (points < -POINTS_BOUND || points > POINTS_BOUND) {
            throw new ScorecardInvalid(what + "'s points are within +/-" + POINTS_BOUND);
        }
    }
}
