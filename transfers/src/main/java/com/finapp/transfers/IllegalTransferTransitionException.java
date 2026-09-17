package com.finapp.transfers;

import java.io.Serial;

/**
 * A transfer status transition the machine does not permit ({@code INV-LIFE-02}).
 *
 * <p>Carries the states and not the identifier — an exception is serializable, {@code EntityId}
 * is not ({@code IllegalLedgerAccountTransitionException}'s recorded reasoning); the message
 * names the identifier's value instead. No amount anywhere ({@code INV-AUD-02}): an exception
 * message reaches logs, and transfer amounts are {@code RESTRICTED-FINANCIAL}.
 */
public final class IllegalTransferTransitionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final TransferStatus from;
    private final TransferStatus to;

    public IllegalTransferTransitionException(
            TransferId transfer, TransferStatus from, TransferStatus to) {
        super("transfer " + transfer + " cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public TransferStatus from() {
        return from;
    }

    public TransferStatus to() {
        return to;
    }
}
