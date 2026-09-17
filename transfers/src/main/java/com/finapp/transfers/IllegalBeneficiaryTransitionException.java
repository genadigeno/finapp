package com.finapp.transfers;

import java.io.Serial;

/**
 * A beneficiary status transition the machine does not permit ({@code INV-LIFE-02}).
 *
 * <p>Carries the states and not the identifier — an exception is serializable, {@code EntityId}
 * is not (the {@code IllegalTransferTransitionException} reasoning); the message names the
 * identifier's value instead. Never the display name ({@code INV-AUD-02}): an exception message
 * reaches logs, and a beneficiary's name is {@code RESTRICTED-PII}.
 */
public final class IllegalBeneficiaryTransitionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final BeneficiaryStatus from;
    private final BeneficiaryStatus to;

    public IllegalBeneficiaryTransitionException(
            BeneficiaryId beneficiary, BeneficiaryStatus from, BeneficiaryStatus to) {
        super("beneficiary " + beneficiary + " cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public BeneficiaryStatus from() {
        return from;
    }

    public BeneficiaryStatus to() {
        return to;
    }
}
