package com.finapp.crossborder;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A datum a corridor requires about the beneficiary or the payment (`P9-TSK-015`, ADR-0080 section 4).
 * A corridor whose policy needs data the platform does not hold cannot be proposed or activated: the
 * platform collects only what {@link #HELD} names, so a corridor requiring more could never be paid
 * truthfully.
 */
public enum RequiredData {
    /** The beneficiary's name - collected at registration, held encrypted by kyc (ADR-0081). */
    BENEFICIARY_NAME,
    /** Individual or business - attested by the provider at registration. */
    ENTITY_TYPE,
    /** A postal address - not collected. */
    BENEFICIARY_ADDRESS,
    /** A regulatory purpose code - not collected. */
    PAYMENT_PURPOSE;

    /** What the platform holds for every beneficiary today. */
    public static final Set<RequiredData> HELD = EnumSet.of(BENEFICIARY_NAME, ENTITY_TYPE);

    /** Whether the platform can satisfy this datum. */
    public boolean held() {
        return HELD.contains(this);
    }

    /** The values as a SQL literal list - {@code crossborder V002}'s {@code CHECK} is generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
