package com.finapp.bnpl;

/**
 * Why pay-in-4's schedule engine produced no schedule (`P12-TSK-008`; ADR-0102 decision 2) - a typed answer, never an
 * exception a caller must parse, and never an approximated schedule.
 */
public enum BnplScheduleRefusal {

    /**
     * The terms are not the shape version 1 computes: not pay-in-4's own part, another instalment count or interval,
     * another schedule engine version, a non-zero rate, another frequency or another instalment rounding
     * ({@code INV-LND-09} by analogy) - the engine refuses rather than approximates.
     */
    SHAPE_NOT_IMPLEMENTED,

    /**
     * The principal is not in the terms' currency or not at its scale, or a due date would fall outside the years the
     * engine's calendar computes.
     */
    INVALID_REQUEST,

    /** The principal is zero or negative - there is nothing to schedule. */
    PRINCIPAL_NOT_POSITIVE,

    /**
     * The principal is fewer minor units than there are instalments, so {@code A = floor(P / 4)} is zero and instalments
     * 2-4 would be instalments of nothing. The terms door answers it {@code 422 bnpl.TermsNotAmortising} when credit's
     * smallest purchase would meet it ({@code BnplTermsAdministration}).
     */
    INSTALMENT_OF_NOTHING
}
