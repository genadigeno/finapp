package com.finapp.payments;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client (`P5-TSK-011`).
 *
 * <p>Namespaced {@code payments.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against
 * these strings, so one is deprecated rather than renamed.
 *
 * <p><strong>No provider vocabulary, structurally</strong> ({@code INV-PAY-03} at the contract):
 * a provider's decline code lives in the retained evidence, and the customer-facing rendering of
 * a failed payment is the mapped {@link PaymentFailureReason} <em>in the view body</em> — a
 * {@code FAILED} judgement is a {@code 200} whose body says so, never an HTTP error (the
 * asynchronous-outcome contract shape, `P4-TSK-008`). The codes here are therefore only the
 * refusals: requests the platform declined to judge, with nothing written.
 *
 * <p><strong>There is no payment not-found code, deliberately</strong>: an unknown, not-yours or
 * malformed payment identifier on any {@code '{id}'} route is {@code api.NotFound},
 * byte-identical across its causes — a distinct code would make the surface an oracle over other
 * people's payments (the {@code P1-TSK-016} reasoning).
 */
public enum PaymentsErrorCode implements ErrorCode {

    /**
     * The caller has no wallet a payment could fund (`P5-TSK-009`'s refusal, surfaced).
     *
     * <p>A {@code 422}: the request is coherent but the caller's own state cannot receive the
     * money — the remedy (open an account) is theirs. Discloses only the caller's own standing.
     */
    NO_WALLET(
            "payments.NoWallet",
            422,
            "You have no account that can receive a payment."),

    /**
     * The named instrument resolves to no active payment method of the caller's.
     *
     * <p>A {@code 422}: a body field the caller supplied and must be able to correct.
     * <strong>One code for unknown, not-yours, malformed and detached alike</strong> — the
     * resolution port answers the first three with one empty
     * ({@code PaymentParticipants.instrumentOwnedBy}), the surface folds malformed into absent
     * (malformed-equals-absent), and an instrument detached between creation and confirmation is
     * the same answer at confirm time: a split would make the payment endpoints an oracle over
     * other people's instruments.
     */
    UNKNOWN_INSTRUMENT(
            "payments.UnknownInstrument",
            422,
            "The payment method does not resolve to an active instrument of yours."),

    /**
     * The payment's currency is not the wallet's (`P5-TSK-009`'s refusal, surfaced).
     *
     * <p>A {@code 422}: FX is Phase 9's, and until then a payment funds the wallet in the
     * wallet's own currency. The remedy is the caller's — a corrected amount.
     */
    CURRENCY_MISMATCH(
            "payments.CurrencyMismatch",
            422,
            "The payment currency must match your account's currency."),

    /**
     * The intent is not in a state confirmation can leave (`P5-TSK-011`).
     *
     * <p>A {@code 409}: well formed, refused by the machine — named for what is
     * <em>checked</em> (the intent machine's edge; the {@code transfers.NotReversible} shape),
     * never for the commonest cause. One code for {@code CANCELLED} and the terminal states
     * alike; the remedy is the same — {@code GET /v1/payments/'{id}'} carries the state.
     */
    NOT_CONFIRMABLE(
            "payments.NotConfirmable",
            409,
            "The payment is not awaiting confirmation."),

    /**
     * The intent is not in a state cancellation can leave (`P5-TSK-011`).
     *
     * <p>A {@code 409}: cancellation wins only the confirmation window (ADR-0045 — once the
     * dispatch has committed, the provider may already have acted, and pretending otherwise
     * would be a lie with money on it). One code for {@code PROCESSING} and the terminal states
     * alike, same reasoning as {@link #NOT_CONFIRMABLE}.
     */
    NOT_CANCELLABLE(
            "payments.NotCancellable",
            409,
            "The payment can no longer be cancelled."),

    /**
     * No payment provider is configured on this deployment (`P5-TSK-011`).
     *
     * <p>A {@code 503}, the {@code paymentmethods.TokenisationUnavailable} decision verbatim:
     * the caller did nothing wrong and retrying <em>this deployment</em> cannot help until an
     * operator configures {@code finapp.payments.provider.url} (the {@code ObjectProvider}
     * decision recorded in {@code PaymentBeans}). Distinct in kind from the <em>in-body</em>
     * {@code FAILED(PROVIDER_UNAVAILABLE)}: that is a refused connection to a configured
     * provider — knowledge, a judged outcome — where this is the platform declining to judge,
     * with nothing written.
     */
    PROVIDER_UNAVAILABLE(
            "payments.ProviderUnavailable",
            503,
            "Payments are temporarily unavailable.");

    private final String code;
    private final int status;
    private final String title;

    PaymentsErrorCode(String code, int status, String title) {
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
