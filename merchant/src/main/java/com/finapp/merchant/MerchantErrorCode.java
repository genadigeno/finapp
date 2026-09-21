package com.finapp.merchant;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client (`P6-TSK-003`). Namespaced {@code merchant.*}
 * ({@code ERROR_CONTRACT.md} §4) and permanent.
 *
 * <p><strong>There is no merchant not-found code, deliberately</strong>: an unknown or
 * malformed merchant identifier on any operator route is {@code api.NotFound}, one answer —
 * and when `P6-TSK-002`'s tenant-scoped surfaces arrive, the same one answer folds
 * another-tenant's into it ({@code INV-MER-01}, the {@code P1-TSK-016} oracle reasoning).
 */
public enum MerchantErrorCode implements ErrorCode {

    /**
     * The named party cannot be onboarded ({@code INV-KYC-05} consumed at this boundary).
     *
     * <p>A {@code 422}: the request is coherent but the subject's standing refuses it — the
     * remedy (complete KYB) is the counterparty's. <strong>One code for no-such-party,
     * person-party, no-relationship and unverified alike</strong>: the verification port
     * answers all four with one empty ({@link MerchantVerification}), because an onboarding
     * surface that distinguishes them is an oracle over parties and their compliance standing.
     */
    NOT_ELIGIBLE(
            "merchant.NotEligible",
            422,
            "The party cannot be onboarded as a merchant."),

    /**
     * The requested state move is not on the machine ({@code INV-LIFE-02}) — suspending a
     * closed merchant, reinstating an active one, closing a suspended one. A {@code 409}: the
     * resource's current state refuses, and the remedy is to read it.
     */
    ILLEGAL_TRANSITION(
            "merchant.IllegalTransition",
            409,
            "The merchant's current status does not permit this change."),

    /**
     * The settlement currency is not one the chart can serve — the account-opening refusal's
     * shape ({@code P3-TSK-012}), because a payable the residual account cannot exist for is
     * refused before it exists for a millisecond ({@code INV-BAL-03}).
     */
    UNSUPPORTED_CURRENCY(
            "merchant.UnsupportedCurrency",
            422,
            "The settlement currency is not supported.");

    private final String code;
    private final int status;
    private final String title;

    MerchantErrorCode(String code, int status, String title) {
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
