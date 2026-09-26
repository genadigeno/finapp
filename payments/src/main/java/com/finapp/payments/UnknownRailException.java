package com.finapp.payments;

/**
 * An operator named a rail this build does not declare (`P7-TSK-003`): a policy rule or an
 * availability act against a rail that does not exist would be a recorded fact about
 * nothing. Refused before anything is written; the surface maps it to
 * {@code payments.UnknownRail} (422).
 *
 * <p>Distinct from {@link PaymentRails#capabilitiesOf}'s {@code IllegalStateException} on
 * purpose: a STORED rail the build no longer declares is a wiring fault (deployment
 * regressed under live rows), while an operator TYPING an undeclared name is an input error
 * with a remedy of their own.
 */
public class UnknownRailException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public UnknownRailException(RailId rail) {
        super("no rail declares '" + rail.value() + "'");
    }
}
