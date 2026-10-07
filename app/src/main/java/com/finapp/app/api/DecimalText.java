package com.finapp.app.api;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * A decimal string from a request, admitted only in its plain shape before it is ever a {@link BigDecimal} (the
 * Phase 9 to 10 transition gate).
 *
 * <h2>Why the shape comes first</h2>
 *
 * <p>{@code new BigDecimal(String)} accepts an exponent: {@code "1E+400000000"} is twelve characters - inside every
 * {@code @Size} bound - and parses instantly to a value whose scale is minus four hundred million. Everything after
 * it is the denial of service: {@code Money.of}'s {@code setScale} computes ten to the four hundred millionth power,
 * and {@code toPlainString} - in an exception message or a log line - materialises four hundred million characters.
 * {@code "1E-400000000"} is the same in the other direction (the rescale divides by that power). A request thread
 * spends minutes and gigabytes on one body, and the cross-border quote door did it inside a transaction holding a
 * pooled connection.
 *
 * <p>So the text is judged first: one to fifteen integer digits, optionally a point and one to nine fraction digits -
 * no sign, no exponent, no grouping. Fifteen integer digits at the platform's widest minor-unit scale stays inside a
 * {@code long} of minor units; nine fraction digits cover every currency's scale and every rate or margin a policy
 * states. A value outside the shape is refused as the caller's malformed input; inside it, parsing and rescaling are
 * bounded by construction.
 */
public final class DecimalText {

    /** The bound a request field carrying a decimal string declares - wider than the shape, so the shape decides. */
    public static final int MAX_LENGTH = 32;

    private static final Pattern PLAIN = Pattern.compile("\\d{1,15}(?:\\.\\d{1,9})?");

    private DecimalText() {}

    /** Whether {@code raw} is a plain decimal of at most fifteen integer and nine fraction digits. */
    public static boolean plain(String raw) {
        return raw != null && raw.length() <= MAX_LENGTH && PLAIN.matcher(raw).matches();
    }

    /**
     * {@code raw} as a decimal, exactly.
     *
     * @throws NumberFormatException (an {@code IllegalArgumentException}) when it is not {@link #plain} - the message
     *     fixed, never echoing the input
     */
    public static BigDecimal parse(String raw) {
        if (!plain(raw)) {
            throw new NumberFormatException(
                    "a decimal is 1-15 digits, optionally a point and 1-9 digits - no sign, exponent or grouping");
        }
        return new BigDecimal(raw);
    }
}
