package com.finapp.payments;

/**
 * The observation seam on the payment appliers' acting branches (`P7-TSK-015`,
 * {@code PHASE_7_PLAN.md} §15) — the ledger's {@code PostingObserver} shape, pointed at the
 * judgements the rails produce.
 *
 * <h2>Why a port, and why here</h2>
 *
 * <p>{@code payments} must not see a metrics library: what {@code finapp.payments.rail.outcome}
 * counts is this module's fact, and what publishes it is the composition root's decision. And the
 * seam sits on the <strong>three appliers</strong> — {@link PaymentOutcomes},
 * {@link WithdrawalOutcomes} and {@link DisputeResponseOutcomes} — rather than at the doors,
 * because every judgement a rail produces is written there and nowhere else (the audit
 * {@code JudgementWritersAreConfinedTest} pins): the synchronous flights, the webhook and callback
 * doors and every resolution sweep are different <em>arrivals</em> of one judgement. A count
 * incremented per door is a count a new door silently loses — and Phase 7's doors did lose it: the
 * pay-in sweep's executions, the return sweep's refunds, voids, withdrawals and dispute answers
 * were counted nowhere until this seam existed. The parameter is <strong>required</strong> on every
 * applier, the {@code PostingObserver} reasoning: a defaulted overload is the quiet path a later
 * author takes.
 *
 * <h2>What an observation is</h2>
 *
 * <p>Called only in the branch where <strong>this call's own conditional transition fired</strong>
 * — the acting bit, the row count, the one place the answer exists — so a resolver that converged
 * on a judgement another made never observes it, and ten racing resolvers observe one judgement
 * once. Called <em>inside</em> the caller's transaction, with the status the transition committed:
 * whether the count waits for that transaction's commit is the listener's decision (the
 * composition root's listener waits — a rolled-back judgement never happened). Every committed
 * status is reported, a mid-question one ({@code VOID_DISPATCHED} after a declined capture's
 * redirect, {@code AWAITING_PAYER} after an initiation opened) included; which statuses are
 * <em>judgements</em> is the listener's vocabulary. Nothing crosses but the rail and the status —
 * no amount, no account, no identifier ({@code INV-AUD-02}).
 */
public interface RailOutcomeObserver {

    /** An acting attempt transition committed {@code committed} on {@code rail}. */
    void attemptJudged(RailId rail, PaymentAttemptStatus committed);

    /** An acting refund transition committed {@code committed} on the refunded attempt's rail. */
    void refundJudged(RailId rail, RefundStatus committed);

    /** An acting withdrawal transition committed {@code committed} on {@code rail}. */
    void withdrawalJudged(RailId rail, WithdrawalStatus committed);

    /** An acting dispute-response transition committed {@code committed} on the disputed rail. */
    void disputeResponseJudged(RailId rail, DisputeResponseStatus committed);

    /**
     * An acting suspense parking committed on {@code rail} (the Phase 7 -> 8 transition): the
     * parking reports where it is written, so a parking made by the pay-in sweep's applier is
     * counted exactly as one made at the callback door.
     */
    void unmatchedParked(RailId rail);

    /**
     * Observes nothing — for tests and tools that measure nothing. Production wiring passes the
     * meters; this constant exists so a test asserting payment semantics does not have to invent
     * one, not so a bean can take the quiet path.
     */
    RailOutcomeObserver NONE =
            new RailOutcomeObserver() {
                @Override
                public void attemptJudged(RailId rail, PaymentAttemptStatus committed) {}

                @Override
                public void refundJudged(RailId rail, RefundStatus committed) {}

                @Override
                public void withdrawalJudged(RailId rail, WithdrawalStatus committed) {}

                @Override
                public void disputeResponseJudged(RailId rail, DisputeResponseStatus committed) {}

                @Override
                public void unmatchedParked(RailId rail) {}
            };
}
