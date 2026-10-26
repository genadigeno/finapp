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
        /** kyc's current screening hit; the signal recorded and the fact alert raised (since the review: always). */
        HIT,
        /** The party's relationship is neither active nor onboarding any more. */
        SKIPPED,
        /** The party is onboarding with no KYC case yet - never screened, nothing to rescreen; not an exposure. */
        NOT_ONBOARDED,
        /**
         * A hit kyc recorded on one of a permit's questions after the permit concluded otherwise - a late callback, a slow
         * call - told after the fact: its own signal and alert, the permit as it concluded (the last review's item 3).
         */
        LATE_HIT,
        /** Not yet current, in flight, never screened or kyc unavailable - re-permitted. */
        DEFERRED,
        /** The bound reached without a current screening, or passed with no conclusion - the exposure. */
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
