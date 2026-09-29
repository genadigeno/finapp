package com.finapp.settlement;

import java.time.Duration;

/**
 * What the intake tells telemetry (`P8-TSK-008`) — the {@code ReceptionOutcomeObserver}
 * shape: the module reports facts, the composition decides they are counted only after the
 * transaction that made them true commits (`P1-TSK-029`'s rule; a rejection counted and then
 * rolled back would be an alert about nothing).
 */
public interface IntakeOutcomeObserver {

    /** A file became one canonical batch; {@code sinceReceipt} is the door-to-parsed age. */
    void parsed(String sourceCode, Duration sinceReceipt);

    /** A file was rejected — the parse leg's verdicts and the decline alike. */
    void rejected(String sourceCode, RejectionCode code, Duration sinceReceipt);

    IntakeOutcomeObserver NONE =
            new IntakeOutcomeObserver() {
                @Override
                public void parsed(String sourceCode, Duration sinceReceipt) {}

                @Override
                public void rejected(
                        String sourceCode, RejectionCode code, Duration sinceReceipt) {}
            };
}
