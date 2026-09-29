package com.finapp.settlement;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A settlement line's direction from the platform's view (`P8-TSK-008`, ADR-0065):
 * {@code INBOUND} is money coming to the platform, {@code OUTBOUND} money leaving it. The
 * amount itself stays positive (the ADR-0003 triple); the sign lives here, exactly as the
 * expectation register holds it.
 */
public enum LineDirection {
    INBOUND,
    OUTBOUND;

    /** The `V003` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
