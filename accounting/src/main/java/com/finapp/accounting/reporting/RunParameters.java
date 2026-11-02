package com.finapp.accounting.reporting;

import com.finapp.sharedkernel.security.InstrumentShapes;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A report run's parameters (`P14-TSK-008`; {@code PHASE_14_PLAN.md} section 9's {@code parameters_hash}): a flat map of
 * bounded string values and the valuation rate set the run pins, if any - the part of the run's question the caller
 * chooses. Its canonical text ({@link CanonicalJson}) is stored beside the run and its SHA-256 is the run key's last
 * member, so two requests asking the same question - whatever their member order - name one run, and two asking
 * different ones never do. {@code accounting V004} recomputes the hash from the stored text for every writer.
 *
 * <p><strong>Bounded and screened</strong> ({@code INV-AUD-02}): at most {@link #MAX_VALUES} members; a name of a lower-case
 * letter and up to 31 letters or digits; a value of 1 to 128 characters from a plain alphabet (letters, digits, space and
 * {@code _ . : , / = + -}) holding no card-number or bank-account shape ({@link InstrumentShapes}) - a report is asked by
 * codes, dates and currencies, never by a person's instrument. The rate set is a reference by id, never validated here:
 * it is how a run asks for translation (`P14-TSK-015`) - the claim refuses a set that is not the period's approved one, and
 * the definition reads it inside its snapshot ({@code Translation.Rates.pinnedBy}).
 */
public record RunParameters(SortedMap<String, String> values, Optional<UUID> valuationRateSet) {

    /** At most this many named values. */
    public static final int MAX_VALUES = 16;

    /** A value's longest length. */
    public static final int MAX_VALUE_LENGTH = 128;

    private static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,31}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.:,/=+-]{0,127}");


    /** The parameters are not a bounded, plain, instrument-free set. */
    public static final class Invalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public Invalid(String detail) {
            super(detail);
        }
    }

    public RunParameters {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(valuationRateSet, "valuationRateSet");
        values = Collections.unmodifiableSortedMap(new TreeMap<>(values));
    }

    /**
     * {@code values} and {@code valuationRateSet}, screened.
     *
     * @throws Invalid for too many values, a malformed name, a value out of its alphabet or bound, or an instrument shape
     */
    public static RunParameters of(Map<String, String> values, Optional<UUID> valuationRateSet) {
        Objects.requireNonNull(valuationRateSet, "valuationRateSet");
        Map<String, String> given = values == null ? Map.of() : values;
        if (given.size() > MAX_VALUES) {
            throw new Invalid("a report run takes at most " + MAX_VALUES + " parameters");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : given.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || !NAME.matcher(name).matches()) {
                throw new Invalid("a parameter name is a lower-case letter and up to 31 letters or digits");
            }
            if (value == null || !VALUE.matcher(value).matches()) {
                throw new Invalid("a parameter value is 1 to " + MAX_VALUE_LENGTH
                        + " letters, digits, spaces or _ . : , / = + -");
            }
            if (InstrumentShapes.holdsAny(value)) {
                throw new Invalid("a parameter value must not hold a card-number or bank-account shape");
            }
            sorted.put(name, value);
        }
        return new RunParameters(sorted, valuationRateSet);
    }

    /** {@code {"valuationRateSet":<id or null>,"values":{...}}}, canonical - what is stored and hashed. */
    public String canonical() {
        CanonicalJson.Obj named = CanonicalJson.object();
        values.forEach(named::put);
        CanonicalJson.Obj whole = CanonicalJson.object().put("values", named);
        if (valuationRateSet.isPresent()) {
            whole.put("valuationRateSet", valuationRateSet.get().toString());
        } else {
            whole.putNull("valuationRateSet");
        }
        return CanonicalJson.text(whole);
    }

    /** No values and no rate set - a definition that takes no parameters (a method: no static state, ADR-0014). */
    public static RunParameters none() {
        return new RunParameters(new TreeMap<>(), Optional.empty());
    }

    /** The SHA-256 of {@link #canonical} - the run key's last member. */
    public String hash() {
        return CanonicalJson.sha256(canonical());
    }

    /** As a canonical value, for the run's content header. */
    CanonicalJson.Obj asValue() {
        CanonicalJson.Obj named = CanonicalJson.object();
        values.forEach(named::put);
        return named;
    }
}
