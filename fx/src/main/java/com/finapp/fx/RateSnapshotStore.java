package com.finapp.fx;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for reference snapshots (`P9-TSK-005`, ADR-0075 §1, {@code INV-FX-02}).
 *
 * <p><strong>Freshness is the database's to judge.</strong> {@link #freshLatest} compares the
 * pair's latest {@code received_at} with {@code statement_timestamp()} inside the caller's own
 * transaction - never an instance's clock, never a cached value - and answers empty when the
 * latest is older than {@code maxAge}: the caller fails closed. The age is a parameter because
 * its value is the pricing policy's ({@code reference_max_age}, {@code P9-TSK-007}).
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface RateSnapshotStore<T> {

    /** What recording one observation did. */
    enum Recorded {
        /** Stored: newer than every observation of the pair. */
        STORED,
        /** Not stored: a duplicate or an older observation - a replay never looks fresh. */
        NOT_NEWER
    }

    /**
     * Records {@code observation} from {@code source} under {@code id}, or stores nothing when it
     * is not newer than the pair's latest - the database's trigger decides, for every writer.
     *
     * @throws ObservationRefusedException when the database refuses the row itself (a pair
     *     outside the declaration, an observation from the future) - the caller's transaction
     *     is spent and must roll back
     */
    Recorded record(T unitOfWork, String source, RateObservation observation, UUID id);

    /** The pair's latest observation, fresh or not. */
    Optional<RateSnapshot> latest(T unitOfWork, String source, ReferencePair pair);

    /**
     * The pair's latest observation, only if it was received within {@code maxAge} of the
     * database's {@code statement_timestamp()} - else empty, and the caller fails closed.
     */
    Optional<RateSnapshot> freshLatest(
            T unitOfWork, String source, ReferencePair pair, Duration maxAge);

    /**
     * How long ago the pair's latest observation was received, on the database clock, to the
     * microsecond - empty when the pair was never fetched.
     */
    Optional<Duration> age(T unitOfWork, String source, ReferencePair pair);

    /** The database refused an observation row; the transaction is spent. */
    final class ObservationRefusedException extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ObservationRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
