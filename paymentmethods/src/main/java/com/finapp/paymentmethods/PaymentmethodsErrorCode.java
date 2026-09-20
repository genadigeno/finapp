package com.finapp.paymentmethods;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client (`P5-TSK-005`).
 *
 * <p>Namespaced {@code paymentmethods.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against
 * these strings, so one is deprecated rather than renamed.
 *
 * <p><strong>There is no payment-method not-found code, deliberately</strong>: an unknown,
 * not-yours or malformed identifier on the detach endpoint is {@code api.NotFound},
 * byte-identical across its causes — a distinct code would make the endpoint an oracle over
 * other people's instruments (the beneficiary surface's reasoning, verbatim).
 */
public enum PaymentmethodsErrorCode implements ErrorCode {

    /**
     * The tokenisation exchange could not be completed (`P5-TSK-005`).
     *
     * <p>A {@code 503}, and the status is the decision worth reading: not a {@code 422} (the
     * caller's grant may be perfectly good), not a {@code 409} (no state refused anything),
     * and not a {@code 500} (nothing of ours failed — {@code ERROR_CONTRACT.md} §3's rule
     * that a 500 says <em>our</em> side broke). The attach genuinely cannot proceed right now
     * and never falls back to holding raw detail ({@code INV-PAY-02}'s own sentence), so the
     * honest answer is retry-later. One code for every unavailable cause — timeout, 5xx,
     * garbage, an unmapped status, an answer we cannot store, no provider configured — because
     * the remedy is identical and the causes are nobody's business at this boundary.
     */
    TOKENISATION_UNAVAILABLE(
            "paymentmethods.TokenisationUnavailable",
            503,
            "The instrument could not be tokenised right now; retry later."),

    /**
     * The tokenisation provider explicitly refused the grant (`P5-TSK-005`).
     *
     * <p>A {@code 422}: the grant is the caller's own value — expired, already used, or never
     * real — and the remedy is theirs: obtain a fresh one and retry. Distinct from
     * {@link #TOKENISATION_UNAVAILABLE} because the remedies differ (renew versus wait), and
     * what it discloses is bounded to a fact about the caller's own grant.
     */
    INSTRUMENT_NOT_TOKENISED(
            "paymentmethods.InstrumentNotTokenised",
            422,
            "The tokenisation grant was refused; obtain a fresh grant and retry.");

    private final String code;
    private final int status;
    private final String title;

    PaymentmethodsErrorCode(String code, int status, String title) {
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
