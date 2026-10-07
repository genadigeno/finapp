package com.finapp.credit;

import java.time.Duration;

/**
 * What credit data collection reports as it happens (`P10-TSK-006`) - {@code app}'s meters implement it, so the
 * module counts outcomes without a metrics dependency.
 */
public interface CreditDataObserver {

    /** One answer's outcome for a source kind and provider. */
    void answered(CreditSourceKind kind, String providerCode, Outcome outcome);

    /** How long one provider call took. */
    void called(CreditSourceKind kind, String providerCode, Duration took);

    /** The outcomes {@code finapp.credit.data.request} counts. */
    enum Outcome {
        RECEIVED,
        UNAVAILABLE,
        CONSENT_WITHDRAWN,
        DUPLICATE
    }

    /** Reports nothing. */
    CreditDataObserver NONE = new CreditDataObserver() {
        @Override
        public void answered(CreditSourceKind kind, String providerCode, Outcome outcome) {}

        @Override
        public void called(CreditSourceKind kind, String providerCode, Duration took) {}
    };
}
