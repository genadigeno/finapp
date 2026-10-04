package com.finapp.fx;

import java.util.List;
import java.util.Objects;

/**
 * The independent reference boundary (`P9-TSK-005`, ADR-0075 §1, ADR-0008's port shape): one
 * fetch answers every pair the source publishes, as values - never an exception through the
 * sweep. Implemented in {@code app} by the {@code simulated-reference} adapter.
 */
public interface RateSource {

    /** The source's code - {@link ReferenceSourceDeclaration#SOURCE} for the simulated one. */
    String sourceCode();

    /** One round trip, holding no connection. */
    Fetched fetch();

    /** What one fetch produced. */
    sealed interface Fetched permits Fetched.Rates, Fetched.Failed {

        /**
         * The admitted observations, and how many rows the adapter refused (an unknown pair, a
         * rate the type refuses, a malformed row) - counted, never stored.
         */
        record Rates(List<RateObservation> observations, int rejected) implements Fetched {
            public Rates {
                observations = List.copyOf(observations);
                if (rejected < 0) {
                    throw new IllegalArgumentException("rejected is a count");
                }
            }
        }

        /** The source could not be read this round. */
        record Failed(FetchFailure failure) implements Fetched {
            public Failed {
                Objects.requireNonNull(failure, "failure must not be null");
            }
        }
    }

    /** Why a fetch produced nothing - the outcome tag, a closed set. */
    enum FetchFailure {
        TIMEOUT,
        UNAVAILABLE,
        TRANSPORT,
        REFUSED_ANSWER
    }
}
