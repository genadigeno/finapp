package com.finapp.settlement;

/**
 * Where a pull reports what it could not bring home (`P8-TSK-021`) — the
 * {@link ReceptionOutcomeObserver} shape, for the half the door never sees: a fetch that
 * produced no delivery. What a pull DID deliver is counted by the door's own observer, since a
 * pulled file is received exactly as an upload is.
 *
 * <p>The meter is a second, independent tally, never truth; a failing meter never fails a pull.
 */
public interface PullOutcomeObserver {

    /**
     * A fetch of {@code sourceCode}'s report produced no delivery — {@code outcome} is
     * {@code not_yet} or a {@link SettlementReportCollector.FailureOutcome}, lower-cased.
     */
    void notReceived(String sourceCode, String outcome);
}
