package com.finapp.merchant;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * The payout provider, behind its own narrow port (`P6-TSK-012`, ADR-0051 §4): one simulated
 * disbursement operation and its query — deliberately NOT a widening of payments'
 * {@code PaymentProvider}, because a payout is not a payment attempt and one port for both would
 * couple two lifecycles. Not a rail abstraction either: Phase 7 owns that.
 *
 * <h2>The contract</h2>
 *
 * <ul>
 *   <li><strong>Provider misbehaviour is a result, never an exception</strong>: a timeout, a
 *       5xx, garbage and an unmapped word each come back as {@link PayoutAnswer.Verdict}'s
 *       indeterminate branch, with whatever bytes arrived kept as evidence. An exception
 *       escaping the port is an adapter defect, and it leaves the payout {@code DISPATCHED} for
 *       the takeover or the sweep.
 *   <li><strong>Our reference is idempotent at the provider</strong> ({@code INV-PAY-04}): a
 *       send of a reference the provider already acted on answers that same outcome and never
 *       pays twice. The simulated provider's contract, and one a real adapter must honour — it
 *       is what makes the takeover's re-send and the retry after a timeout safe.
 *   <li><strong>Provider vocabulary stays in the adapter</strong> ({@code INV-PAY-03}'s
 *       discipline): a total mapping whose default branch is indeterminate, never success.
 *   <li><strong>Called holding no database connection</strong> (ADR-0046 §1).
 * </ul>
 */
public interface PayoutProvider {

    /** Sends one payout of {@code request.amount()} to the destination, under our reference. */
    PayoutAnswer dispatch(PayoutRequest request);

    /** Asks what became of the payout sent under {@code ourReference}. Read-only. */
    PayoutQueryAnswer query(PayoutReference ourReference);

    /**
     * One payout to send.
     *
     * @param destination the provider's own opaque reference for the account — never bank
     *     details (ADR-0056 §7); unwrapped onto the wire by the adapter and nowhere else
     */
    record PayoutRequest(
            PayoutReference reference, PayoutDestinationReference destination, Money amount) {

        public PayoutRequest {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("a payout amount must be positive");
            }
        }

        /** Our reference and the currency only — never the destination or the amount. */
        @Override
        public String toString() {
            return "PayoutRequest[" + reference + ", " + amount.currency() + "]";
        }
    }
}
