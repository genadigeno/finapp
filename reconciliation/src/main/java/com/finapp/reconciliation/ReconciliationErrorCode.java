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
            "The suspense item is not yet old enough to be recognised as a gain."),

    /** No rule set version has this id — unknown and malformed alike (`P8-TSK-022`). */
    RULE_SET_NOT_FOUND(
            "reconciliation.RuleSetNotFound",
            404,
            "No matching rule set version has this identifier."),

    /**
     * An approval or rejection of a version no longer {@code PROPOSED} (`P8-TSK-022`,
     * ADR-0068 §8): it was activated, rejected or retired meanwhile.
     */
    RULE_SET_NOT_PENDING(
            "reconciliation.RuleSetNotPending",
            409,
            "This rule set version is no longer awaiting a decision."),

    /** The proposer activating their own version (`P8-TSK-022`, INV-AUD-04). */
    RULE_SET_ACTIVATION_BY_SAME_ACTOR(
            "reconciliation.RuleSetActivationBySameActor",
            409,
            "A rule set version is activated by someone other than its proposer."),

    /** A second proposal while one stands for the source (`P8-TSK-022`). */
    RULE_SET_PROPOSAL_PENDING(
            "reconciliation.RuleSetProposalPending",
            409,
            "A proposed rule set version already awaits a decision for this source."),

    /** A proposal whose content is not a well-formed version (`P8-TSK-022`). */
    RULE_SET_INVALID(
            "reconciliation.RuleSetInvalid",
            422,
            "The proposed rule set version is not well formed."),

    /**
     * A tolerance on anything but a fee's pinned terms or a date (`P8-TSK-022`, INV-REC-08):
     * an amount tolerance would absorb value without an entry.
     */
    TOLERANCE_NOT_PERMITTED(
            "reconciliation.ToleranceNotPermitted",
            422,
            "A tolerance may compare a fee against its terms or a date, never an amount."),

    /** No declared settlement source has this code (`P8-TSK-022`'s reprocess door). */
    SOURCE_NOT_FOUND(
            "reconciliation.SourceNotFound",
            404,
            "No declared settlement source has this code."),

    /** A reprocess request while the source's one open REPROCESS run stands. */
    REPROCESSING_IN_PROGRESS(
            "reconciliation.ReprocessingInProgress",
            409,
            "A reprocessing run is already open for this source."),

    /** A requeue of a run that is not {@code BLOCKED} (`P8-TSK-022`). */
    RUN_NOT_BLOCKED(
            "reconciliation.RunNotBlocked",
            409,
            "Only a blocked reconciliation run can be requeued."),

    /**
     * No settlement batch has this id (`P8-TSK-023`): the repudiation door's named 404, as every
     * reconciliation operator door names its own — unknown and malformed ids one answer.
     */
    BATCH_NOT_FOUND(
            "reconciliation.BatchNotFound",
            404,
            "No settlement batch has this identifier."),

    /** The batch is not ACCEPTED: only accepted evidence is repudiated (`P8-TSK-023`). */
    BATCH_NOT_REPUDIABLE(
            "reconciliation.BatchNotRepudiable",
            409,
            "Only an accepted settlement batch can be repudiated."),

    /** An item of the batch is still PENDING: its run disposes of every item first. */
    BATCH_NOT_DISPOSED(
            "reconciliation.BatchNotDisposed",
            409,
            "The batch's run has not yet disposed of every item."),

    /** A shape this phase does not compensate, refused before anything is written. */
    REPUDIATION_NOT_SUPPORTED(
            "reconciliation.RepudiationNotSupported",
            409,
            "This batch's repudiation needs a compensation this phase does not provide.");

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
