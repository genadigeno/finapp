package com.finapp.checkout;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The failures this module reports to a client (`P6-TSK-007`). Namespaced {@code checkout.*}
 * ({@code ERROR_CONTRACT.md} §4) and permanent.
 *
 * <p><strong>There is no session not-found code, deliberately.</strong> An unknown session id,
 * a malformed one, another merchant's, and a token that opens nothing are all
 * {@code api.NotFound} — one answer, so neither surface becomes an oracle. For the merchant
 * that is {@code INV-MER-01}; for the customer it is stronger still, because a checkout token
 * is guessed at rather than typed, and telling a guesser that a session exists but is not
 * theirs is the only bit they need.
 */
@RequiredArgsConstructor
public enum CheckoutErrorCode implements ErrorCode {

    /**
     * The merchant has no fee schedule, so the platform cannot price this offer
     * (`P6-TSK-007`). A {@code 422}: the request is coherent and the remedy is an operator's —
     * assign the merchant a schedule.
     *
     * <p><strong>Refused at creation rather than discovered at capture</strong>, which is the
     * whole point: an unpriced session would reach the capture and fail <em>there</em>, inside
     * the transaction that moves money, after the customer had paid.
     */
    NOT_PRICEABLE(
            "checkout.NotPriceable",
            422,
            "This merchant has no fee schedule, so a checkout cannot be priced."),

    /**
     * The merchant is not trading (`P6-TSK-007`). A {@code 409}: the merchant's own state
     * refuses, and the remedy is to read it.
     *
     * <p>Suspension gates <strong>new dispatches</strong>
     * ({@code CHECKOUT_MERCHANT_LIFECYCLES.md} §5) — which a new checkout session is. It never
     * touches money already in flight: a session confirmed before the suspension still
     * completes, and its capture still credits the payable.
     */
    NOT_TRADING(
            "checkout.NotTrading",
            409,
            "This merchant cannot open new checkout sessions."),

    /**
     * The offer's deadline has passed (`P6-TSK-007`, ADR-0053 §5 — <em>expiry gates
     * dispatch</em>). A {@code 409}: the resource's own state refuses.
     *
     * <p>Answered whether the sweeper has arrived or not, because the aggregate checks the
     * clock as well as the state — a sweeper one minute behind is not a minute in which the
     * platform honours a dead offer.
     */
    SESSION_EXPIRED(
            "checkout.SessionExpired",
            409,
            "This checkout session has expired."),

    /**
     * The session is not accepting a confirmation — already confirmed, completed or abandoned
     * (`P6-TSK-007`). A {@code 409}: the remedy is to read the session.
     *
     * <p>Distinct from {@link #SESSION_EXPIRED} because the two say different things to the
     * customer looking at the page: one means *too late*, the other means *already done*.
     */
    NOT_CONFIRMABLE(
            "checkout.NotConfirmable",
            409,
            "This checkout session is not awaiting confirmation."),

    /**
     * The merchant cannot withdraw this offer (`P6-TSK-008`). A {@code 409}: the remedy is to
     * read the session and see what happened to it.
     *
     * <p><strong>The case that matters is `PAYMENT_PENDING`</strong>, and it is a refusal rather
     * than an oversight: a session whose payment is in flight has money moving toward it, and
     * withdrawing the offer would leave that money with no commercial home — which is exactly
     * the state {@code INV-MER-06} exists to prevent. The machine has no
     * {@code PAYMENT_PENDING → ABANDONED} edge at all, so this code is what that absence looks
     * like at the surface. A merchant who needs the money back refunds it, through the
     * existing human-decided path, after the order exists.
     *
     * <p>It is also the answer for a session already expired, abandoned or paid — terminal
     * states with nothing left to withdraw. One code for all of them, because the remedy is
     * the same read.
     */
    NOT_ABANDONABLE(
            "checkout.NotAbandonable",
            409,
            "This checkout session cannot be withdrawn.");

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
