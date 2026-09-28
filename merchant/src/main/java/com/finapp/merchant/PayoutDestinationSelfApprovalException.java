package com.finapp.merchant;

/**
 * The proposer of a payout destination tried to approve it ({@code INV-AUD-04}): refused by the
 * aggregate, by the approval statement's own {@code proposed_by <> ?} and by `V006`'s
 * {@code CHECK} — three layers, blind in different directions.
 *
 * <p>Never an exception that leaves the approval transaction: {@code PayoutDestinations} turns it
 * into a committed {@code DENIED} audit record, because a refusal that rolls back takes its own
 * evidence with it ({@code INV-AUD-03}).
 */
public class PayoutDestinationSelfApprovalException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public PayoutDestinationSelfApprovalException() {
        super("a payout destination requires a second approver distinct from its proposer");
    }
}
