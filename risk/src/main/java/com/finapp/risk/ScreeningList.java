package com.finapp.risk;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The screening lists whose versions {@code risk} records (`P13-TSK-022`; {@code PHASE_13_PLAN.md} section 12.10;
 * {@code risk V018}'s {@code screening_list_version_list_is_known}) - a sanctions list and a PEP list, each screened by
 * kyc as a check of its own type ({@code CheckType.SANCTIONS}, {@code CheckType.PEP}); {@code app}'s port maps one to the
 * other. Risk holds no list content: a version is the operator's fact that it is in force (the content is kyc's
 * provider's, out of scope).
 */
public enum ScreeningList {

    /** A sanctions list - kyc's {@code SANCTIONS} screening. */
    SANCTIONS,

    /** A politically-exposed-persons list - kyc's {@code PEP} screening. */
    PEP;

    /** The quoted, comma-separated value list the migration's {@code CHECK} uses. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
