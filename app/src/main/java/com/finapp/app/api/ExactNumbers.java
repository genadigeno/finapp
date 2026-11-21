package com.finapp.app.api;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Request fields that take an exact number as a JSON number <em>or</em> as a decimal string (`P17-TSK-127`).
 *
 * <h2>Why both</h2>
 *
 * <p>A JSON number is exact on this side - Jackson reads its text straight into a {@link BigDecimal} - but a browser holds
 * every number as a binary double, so a client in JavaScript can only send the values a double converts exactly. A decimal
 * string carries any value exactly from any client, which is how this platform already serves every amount and rate. The
 * number stays accepted, so every request that was valid is still valid: the change is a widening, compatible under
 * {@code /v1} (ADR-0015). The field's Java type stays what it was, so its bean-validation bounds and the domain beneath it
 * are unchanged.
 *
 * <h2>Why the string is judged first</h2>
 *
 * <p>The string goes through {@link DecimalText} - one to fifteen integer digits, an optional point and one to nine fraction
 * digits, no sign, no exponent - so {@code "1E+400000000"} is refused before it is ever a {@link BigDecimal}
 * (`RequestDecimalsAreBoundedTest`'s rule). A string outside the shape, or a value of any other JSON type, is the caller's
 * malformed input: {@code 400 api.MalformedRequest}, as a wrong type always was.
 *
 * <p>The published contract shows such a field as {@code oneOf} a number and a patterned string, derived from this annotation
 * in test scope ({@code ContractExtensions}), never hand-kept.
 */
public final class ExactNumbers {

    /** The shape of a count of minor units given as a string, unanchored: one to eighteen digits, inside a {@code long}. */
    public static final String MINOR_UNITS_SHAPE = "\\d{1,18}";

    private static final Pattern MINOR_UNITS = Pattern.compile(MINOR_UNITS_SHAPE);

    private ExactNumbers() {}

    /** A decimal: a JSON number read exactly, or a {@link DecimalText} string. */
    public static final class Decimal extends ValueDeserializer<BigDecimal> {

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT) || parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
                return parser.getDecimalValue();
            }
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                String text = parser.getString();
                if (!DecimalText.plain(text)) {
                    return (BigDecimal) context.handleWeirdStringValue(BigDecimal.class, text,
                            "a decimal is 1-15 digits, optionally a point and 1-9 digits - no sign, exponent or grouping");
                }
                return DecimalText.parse(text);
            }
            return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
        }
    }

    /**
     * A count of minor units: a JSON integer, or a string of one to eighteen digits. A JSON number with a fraction is refused
     * rather than truncated - a fraction of a minor unit is never silently dropped.
     */
    public static final class MinorUnits extends ValueDeserializer<Long> {

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return parser.getLongValue();
            }
            if (parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
                BigDecimal exact = parser.getDecimalValue();
                try {
                    return exact.longValueExact();
                } catch (ArithmeticException fractionalOrTooLarge) {
                    return (Long) context.handleWeirdNumberValue(Long.class, exact,
                            "a count of minor units is a whole number inside a long");
                }
            }
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                String text = parser.getString();
                if (text == null || !MINOR_UNITS.matcher(text).matches()) {
                    return (Long) context.handleWeirdStringValue(Long.class, text,
                            "a count of minor units is 1-18 digits - no sign, point or grouping");
                }
                return Long.parseLong(text);
            }
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
    }
}
