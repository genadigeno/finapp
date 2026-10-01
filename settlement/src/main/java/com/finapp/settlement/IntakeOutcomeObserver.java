package com.finapp.settlement;

import com.finapp.platform.telemetry.Spans;
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

    /**
     * A batch was accepted (`P8-TSK-024`): its source, and the door-to-accepted age -
     * {@code finapp.settlement.batch.accepted} and the latency's {@code accept} stage.
     */
    default void accepted(String sourceCode, Duration sinceReceipt) {}

    /** Where the parse and accept legs record their spans (`P8-TSK-024`). */
    default Spans spans() {
        return Spans.NONE;
    }

    IntakeOutcomeObserver NONE =
            new IntakeOutcomeObserver() {
                @Override
                public void parsed(String sourceCode, Duration sinceReceipt) {}

                @Override
                public void rejected(
                        String sourceCode, RejectionCode code, Duration sinceReceipt) {}
            };
}
