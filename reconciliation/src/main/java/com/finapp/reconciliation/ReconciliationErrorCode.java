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
            "No settlement expectation matches."),

    /** No resolution has this id — unknown and malformed alike (`P8-TSK-015`). */
    RESOLUTION_NOT_FOUND(
            "reconciliation.ResolutionNotFound",
            404,
            "No break resolution has this identifier."),

    /** The break already carries a live proposal — one per break (ADR-0071 §1). */
    RESOLUTION_ALREADY_PROPOSED(
            "reconciliation.ResolutionAlreadyProposed",
            409,
            "This break already carries a live resolution proposal."),

    /** The resolution was already decided — by somebody else, or differently. */
    RESOLUTION_NOT_PENDING(
            "reconciliation.ResolutionNotPending",
            409,
            "This resolution is no longer pending."),

    /** The proposer tried to approve or reject their own resolution (INV-REC-03). */
    SELF_APPROVAL_REFUSED(
            "reconciliation.SelfApprovalRefused",
            409,
            "A resolution's proposer cannot decide it; a second person must."),

    /** Somebody other than the proposer tried to withdraw it (ADR-0071 §10). */
    NOT_THE_PROPOSER(
            "reconciliation.NotTheProposer",
            409,
            "Only a resolution's proposer withdraws it; another person rejects it."),

    /** The subject's remainder or the break's residual moved since the proposal (§8). */
    RESOLUTION_STALE(
            "reconciliation.ResolutionStale",
            409,
            "The break's subject changed since the proposal; withdraw and re-propose."),

    /** A manual match collided with an allocation already recorded for the pair. */
    RECORD_ALREADY_MATCHED(
            "reconciliation.RecordAlreadyMatched",
            409,
            "The item is already allocated to the chosen expectation."),

    /** The kind is not the break type's, not a person's, or not the subject's side (§2). */
    RESOLUTION_KIND_NOT_ALLOWED(
            "reconciliation.ResolutionKindNotAllowed",
            422,
            "This resolution kind does not apply to this break."),

    /** The reason code is outside the kind's admitted subset (ADR-0071 §5). */
    REASON_CODE_NOT_ALLOWED(
            "reconciliation.ReasonCodeNotAllowed",
            422,
            "This reason code is not admitted for this resolution kind."),

    /** A transfer target, offset item or chosen candidate the template refuses (§2). */
    RESOLUTION_TARGET_REFUSED(
            "reconciliation.ResolutionTargetRefused",
            422,
            "The resolution's target, offset item or chosen candidate is refused."),

    /** A gain before the pinned minimum age, judged on the database clock (§2). */
    GAIN_NOT_YET_ELIGIBLE(
            "reconciliation.GainNotYetEligible",
            422,
            "The suspense item is not yet old enough to be recognised as a gain.");

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
