package com.finapp.merchant;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The failures this module reports to a client (`P6-TSK-003`). Namespaced {@code merchant.*}
 * ({@code ERROR_CONTRACT.md} §4) and permanent.
 *
 * <p><strong>There is no merchant not-found code, deliberately</strong>: an unknown or
 * malformed merchant identifier on any operator route is {@code api.NotFound}, one answer —
 * and when `P6-TSK-002`'s tenant-scoped surfaces arrive, the same one answer folds
 * another-tenant's into it ({@code INV-MER-01}, the {@code P1-TSK-016} oracle reasoning).
 */
@RequiredArgsConstructor
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
            "The settlement currency is not supported."),

    /**
     * An API key was requested for a {@code CLOSED} merchant (`P6-TSK-002`). A {@code 409}:
     * the resource's own state refuses, and the remedy is to read it. A credential for a
     * relationship that ended is a door nobody goes looking for until it is used.
     *
     * <p>A {@code SUSPENDED} merchant is deliberately NOT refused here: suspension is
     * reversible, its keys already refuse at authentication through the lookup's join, and
     * refusing issuance too would make an operator repeat the step when the suspension lifts.
     */
    NOT_KEYABLE(
            "merchant.NotKeyable",
            409,
            "A closed merchant cannot be issued an API key."),

    /**
     * A fee schedule version would take effect before it was created (`P6-TSK-004`). A
     * {@code 422}: the request is coherent and the remedy is the caller's — pick an instant
     * that is not in the past.
     *
     * <p>This is {@code INV-MER-03}'s refusal, and it is the one an operator is most likely to
     * meet: "make this effective from the first of the month" is a natural thing to type on
     * the second of the month, and it is a repricing of every capture in between.
     */
    FEE_SCHEDULE_NOT_FORWARD(
            "merchant.FeeScheduleNotForward",
            422,
            "A fee schedule version takes effect forward; it cannot be backdated."),

    /**
     * A fee schedule was asked to price, or to be assigned to, a currency it does not hold
     * (`P6-TSK-004`). A {@code 422}: coherent request, refused by the currencies involved.
     *
     * <p>One code for both boundaries — a version whose fixed part is in the wrong currency,
     * and an assignment to a merchant that settles in another — because both say the same
     * thing to the same reader: this schedule does not price that money. Cross-currency fees
     * are Phase 9's.
     */
    FEE_CURRENCY_MISMATCH(
            "merchant.FeeCurrencyMismatch",
            422,
            "The fee schedule's currency does not match.");

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
