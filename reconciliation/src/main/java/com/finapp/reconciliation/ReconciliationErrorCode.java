package com.finapp.reconciliation;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The failures this module reports to a client (`P8-TSK-011`).
 *
 * <p>Namespaced {@code reconciliation.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written
 * against these strings, so one is deprecated rather than renamed.
 *
 * <p>The named {@code 404}s follow the {@code settlement.FileNotFound} departure: every
 * route sits behind an operator permission, so the surface is no oracle over anyone else's
 * resources, and an investigator chasing a break is told plainly that the id is wrong.
 * Unknown and malformed ids are still ONE answer, and a guessed id records nothing.
 */
@RequiredArgsConstructor
public enum ReconciliationErrorCode implements ErrorCode {

    /** No reconciliation run has this id — unknown and malformed alike. */
    RUN_NOT_FOUND(
            "reconciliation.RunNotFound",
            404,
            "No reconciliation run has this identifier."),

    /** No match decision has this id — unknown and malformed alike. */
    DECISION_NOT_FOUND(
            "reconciliation.DecisionNotFound",
            404,
            "No match decision has this identifier."),

    /** No allocation has this id — unknown and malformed alike. */
    ALLOCATION_NOT_FOUND(
            "reconciliation.AllocationNotFound",
            404,
            "No allocation has this identifier."),

    /** No break has this id — unknown and malformed alike (`P8-TSK-014`). */
    BREAK_NOT_FOUND(
            "reconciliation.BreakNotFound",
            404,
            "No reconciliation break has this identifier."),

    /**
     * A write to a {@code RESOLVED} break (`P8-TSK-014`, ADR-0069 §7): a resolved break takes
     * no assignment, note, link or reclassification — the case continues on its successor.
     */
    BREAK_TERMINAL(
            "reconciliation.BreakTerminal",
            409,
            "This break is resolved; its case continues on its successor."),

    /**
     * No expectation has this id, or none tracks this (kind, operation) (`P8-TSK-014`) —
     * unknown and malformed alike: an operation that settles internally opens none.
     */
    EXPECTATION_NOT_FOUND(
            "reconciliation.ExpectationNotFound",
            404,
            "No settlement expectation matches.");

    private final String code;
    private final int status;
    private final String title;

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
