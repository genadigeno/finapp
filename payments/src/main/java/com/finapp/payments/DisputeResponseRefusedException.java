package com.finapp.payments;

import java.util.Objects;

/**
 * A dispute refused an upload or a response before anything was written or sent (`P7-TSK-014`,
 * ADR-0061 §7) — each refusal a distinct, permanent reason the boundary renders as its own code.
 * Judged under the attempt and dispute row locks, so the answer is the dispute's state at the
 * moment the act would have committed.
 */
public final class DisputeResponseRefusedException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    /** Why — each is its own {@code payments.*} code at the boundary. */
    public enum Refusal {
        /** The dispute is not at {@code CHARGED_BACK}: an inquiry has nothing to contest, and a
         * represented or resolved dispute takes no answer ({@code INV-LIFE-04}). */
        NOT_RESPONDABLE,
        /** The network's respond-by deadline has passed — the platform's clock only refuses
         * its OWN dispatch; the outcome stays the network's. */
        DEADLINE_PASSED,
        /** A live response already answers the dispute — the evidence set froze with it. */
        ALREADY_ANSWERED,
        /** A representment with no evidence to carry. */
        EVIDENCE_REQUIRED,
        /** The dispute already holds the documents one submission may carry. */
        EVIDENCE_LIMIT_REACHED,
        /** An operator acting on a payment whose credited account its policy does not reach —
         * a payment with a merchant: the counterparty answers its own dispute (ADR-0061 §7, the
         * operator acts for a payment with no merchant). */
        COUNTERPARTY_ANSWERS
    }

    private final Refusal refusal;

    public DisputeResponseRefusedException(Refusal refusal) {
        super("the dispute refused the act: " + Objects.requireNonNull(refusal, "refusal"));
        this.refusal = refusal;
    }

    public Refusal refusal() {
        return refusal;
    }
}
