package com.finapp.kyc;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * How a beneficial owner controls an organisation other than by shareholding (`P2-TSK-015`).
 *
 * <p>An owner qualifies by an ownership <em>stake</em>, a control <em>role</em>, or both
 * ({@code PHASE_2_PLAN.md} §4: "a natural person with an ownership stake or control role") —
 * the FATF shape, where a person with no equity can still direct the entity, and the fallback
 * owner of a widely-held company is its senior managing official.
 *
 * <p>A closed enumeration in code, the {@code ConsentPurpose} argument: a free-string role is a
 * vocabulary nobody controls, and the reviewer deciding an organisation's case must be able to
 * rely on what a value means. Persisted values, in a generated {@code CHECK} constraint
 * ({@code P0-TSK-022}; {@code KycCaseMigrationTest} reconciles).
 */
public enum ControlRole {

    /** A member of the governing body. */
    DIRECTOR,

    /** The FATF fallback: the person who directs the entity when no stake dominates. */
    SENIOR_MANAGING_OFFICIAL,

    /** Controls the entity's assets on behalf of others — trusts and similar arrangements. */
    TRUSTEE;

    /** The roles as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(role -> "'" + role.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
