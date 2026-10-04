package com.finapp.fx;

import com.finapp.sharedkernel.money.ExchangeRate;
import java.time.Instant;
import java.util.Objects;

/**
 * One reference observation as the source states it (`P9-TSK-005`): the pair's mid rate, exact
 * ({@link ExchangeRate}: precision at most 20, scale at most 10 - refused, never rounded), and
 * the instant the SOURCE says it observed it. When we received it is the database's to say.
 */
public record RateObservation(ExchangeRate rate, Instant observedAt) {

    public RateObservation {
        Objects.requireNonNull(rate, "rate must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (rate.value().signum() <= 0) {
            throw new IllegalArgumentException("A reference rate is positive");
        }
    }

    public ReferencePair pair() {
        return new ReferencePair(rate.source(), rate.destination());
    }
}
