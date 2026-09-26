package com.finapp.paymentmethods;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
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
            "The tokenisation grant was refused; obtain a fresh grant and retry."),

    /**
     * The rail provider explicitly refused the bank-account grant (`P7-TSK-007`) — spent,
     * expired or revoked. {@link #INSTRUMENT_NOT_TOKENISED}'s reasoning at the bank door: a
     * {@code 422}, the caller's own value, the remedy theirs (link again, register with the
     * fresh grant). The refusal discloses a fact about the caller's own grant and nothing
     * else ({@code INV-RAIL-03}: no detail of the account ever rides an answer).
     */
    GRANT_EXCHANGE_REFUSED(
            "paymentmethods.GrantExchangeRefused",
            422,
            "The rail provider refused the grant; obtain a fresh grant and retry."),

    /**
     * The grant exchange could not be completed (`P7-TSK-007`) —
     * {@link #TOKENISATION_UNAVAILABLE}'s reasoning at the bank door, one code for every
     * unavailable cause: timeout, 5xx, garbage, an unmapped word, an answer we refuse to
     * store, no rail configured. A {@code 503}, honestly retry-later — with the recorded
     * asymmetry that the retry is a <em>new</em> keyed request, and may need a fresh grant
     * when the failed exchange consumed it (single-use at the provider; no money and no
     * state were at stake, ADR-0062 §2).
     */
    GRANT_EXCHANGE_UNAVAILABLE(
            "paymentmethods.GrantExchangeUnavailable",
            503,
            "The bank account could not be registered right now; retry later."),

    /**
     * The scheme directory answered {@code NO_MATCH} and the request carried no
     * acknowledgement (`P7-TSK-007`, ADR-0062 §2's consent rule). A {@code 409}: the
     * registration as asked conflicts with a recorded judgement that requires the customer's
     * explicit say-so — the client shows the mismatch, and a registration <em>with</em> the
     * acknowledgement is a different request under a new key (and, the grant being
     * single-use, a fresh grant; the recorded cost of refusing a pending half-instrument
     * state).
     */
    PAYEE_CHECK_NO_MATCH(
            "paymentmethods.PayeeCheckNoMatch",
            409,
            "The payee check found no match; registering needs the customer's explicit"
                    + " acknowledgement.");

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
