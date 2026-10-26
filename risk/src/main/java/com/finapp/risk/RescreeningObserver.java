package com.finapp.risk;

import java.util.Locale;

/**
 * What the rescreening leg reports (`P13-TSK-022`; {@code PHASE_13_PLAN.md} section 15) - {@code app}'s meters implement
 * it. Every figure a closed code or a count: never a party, a list entry, a screening or a name ({@code INV-RSK-08}).
 */
public interface RescreeningObserver {

    /** Reports nothing - the suites that do not watch. */
    RescreeningObserver NONE = new RescreeningObserver() {
        @Override
        public void concluded(Conclusion conclusion) {
            // Nothing watched.
        }

        @Override
        public void enqueued(int born) {
            // Nothing watched.
        }

        @Override
        public void askFailed(Class<? extends Throwable> cause) {
            // Nothing watched.
        }
    };

    /** What became of one claimed permit - {@code finapp.risk.rescreening{outcome}}. */
    enum Conclusion {
        /** kyc's current screening cleared; the signal recorded. */
        CLEARED,
        /** kyc's current screening hit; the signal recorded and the alert raised. */
        HIT,
        /** kyc's current screening hit; the signal recorded - no {@code MONITORING} rule names the alert. */
        HIT_NOT_ALERTED,
        /** The party is no longer active. */
        SKIPPED,
        /** Not yet current, in flight, never screened or kyc unavailable - re-permitted. */
        DEFERRED,
        /** The bound reached without a current screening - the exposure. */
        FAILED;

        /** The tag value. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One permit concluded or deferred. */
    void concluded(Conclusion conclusion);

    /** One enqueue batch bore {@code born} permits. */
    void enqueued(int born);

    /** The port threw - the cause's class only, never its message. */
    void askFailed(Class<? extends Throwable> cause);
}
