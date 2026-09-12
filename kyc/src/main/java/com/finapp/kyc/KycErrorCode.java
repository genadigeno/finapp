package com.finapp.kyc;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client.
 *
 * <p>Namespaced {@code kyc.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against
 * these strings, so one is deprecated rather than renamed.
 */
public enum KycErrorCode implements ErrorCode {

    /**
     * The caller has no open KYC case to act on.
     *
     * <p>A {@code 409} and a distinct code because it is <strong>actionable</strong>
     * ({@code P1-TSK-018}'s test for earning one): the caller's case was decided — changed
     * circumstances are a <em>new</em> case ({@code INV-LIFE-04}), so the remedy is opening one
     * ({@code POST /v1/me/kyc}), not retrying the upload. No enumeration concern applies: the
     * caller is the authenticated owner asking about their own case, which is the opposite of
     * {@code party.RegistrationRefused}'s stranger.
     */
    NO_OPEN_CASE(
            "kyc.NoOpenCase", 409, "You have no open verification case for this to apply to."),

    /**
     * The declared party cannot be a beneficial owner — <strong>one refusal for every
     * cause</strong> (`P2-TSK-016`).
     *
     * <p>The causes are distinct inside the platform — the party does not exist, is itself an
     * organisation (the depth-1 bound), or is not a registered customer with a verification
     * case — and the boundary deliberately collapses them: {@code ownerPartyId} names a
     * <em>third party</em>, and distinguishing the refusals would make this endpoint an oracle
     * over other people's registrations ({@code INV-IDN-07}'s reasoning, applied to a body
     * field). The declarant learns only that this person cannot be declared, which is the one
     * thing they need and the most they may have.
     */
    OWNER_NOT_ELIGIBLE(
            "kyc.OwnerNotEligible",
            422,
            "This party cannot be declared as a beneficial owner."),

    /**
     * The owner is already on the graph. A {@code 409} rather than a silent convergence,
     * because declarations are <strong>append-only</strong>: if this request carried a
     * different stake or role, nothing was updated — corrections are not a Phase 2 capability
     * — and reporting success would hide that (the reviewer-resolution 409 reasoning).
     */
    OWNER_ALREADY_DECLARED(
            "kyc.OwnerAlreadyDeclared",
            409,
            "This party is already declared on the ownership graph."),

    /**
     * The case's owner set is frozen (`P2-TSK-015`): from {@code READY_FOR_DECISION} on, the
     * graph is part of what the decision rests on ({@code INV-KYC-02}). Actionable in the only
     * honest sense: the declaration arrived too late for this case.
     */
    CASE_NOT_ACCEPTING_OWNERS(
            "kyc.CaseNotAcceptingOwners",
            409,
            "The case is no longer accepting owner declarations."),

    /**
     * The declared stake would take the case's total past 10000 basis points — the caller's
     * own graph, so the refusal is specific and correctable ({@code 422}).
     */
    STAKE_EXCEEDS_WHOLE(
            "kyc.StakeExceedsWhole",
            422,
            "The declared stakes would exceed the whole of the organisation.");

    private final String code;
    private final int status;
    private final String title;

    KycErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

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
