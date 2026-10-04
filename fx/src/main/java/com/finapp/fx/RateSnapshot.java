package com.finapp.fx;

import com.finapp.sharedkernel.money.ExchangeRate;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored reference observation (`P9-TSK-005`, ADR-0075 §1): the source's rate and
 * {@code observedAt}, and the database's {@code receivedAt} - the instant freshness is judged
 * from, on the database clock. Never executable.
 */
public record RateSnapshot(
        UUID id, String source, ExchangeRate rate, Instant observedAt, Instant receivedAt) {

    public RateSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(rate, "rate must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }

    public ReferencePair pair() {
        return new ReferencePair(rate.source(), rate.destination());
    }
}
