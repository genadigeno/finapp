package com.finapp.risk;

import com.finapp.sharedkernel.event.EventId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where risk signals rest (`P13-TSK-005`; {@code risk V003}) - one born-once insert and six bounded reads, every one
 * on the caller's unit of work (ADR-0033): the insert joins the inbox handler's transaction (T-sig), the reads join
 * T-eval's (`-004`) or the sweep's (`-007`). No update, no delete: none exists in the contract, the grant or the
 * table's trigger.
 */
public interface RiskSignalStore {

    /**
     * Records {@code signal} unless a signal of its source event already exists.
     *
     * <p>One statement, {@code INSERT ... ON CONFLICT (source_event_id) DO NOTHING}: the unique is the arbiter beneath
     * the inbox's dedupe, so a replay after the dedupe's retention, or a dedupe row lost, still records nothing twice
     * ({@code INV-RSK-06}, {@code INV-IDEM-04}). {@code recorded_at} is the database's clock, stamped by the table's
     * trigger - never an instance's.
     *
     * @return true when the signal was born by this call; false when its source event had already been recorded
     */
    boolean insert(Connection unitOfWork, RiskSignal signal);

    /** How many signals of {@code kind} about {@code subject} were observed at or after {@code since}. */
    int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since);

    /**
     * How many signals of {@code kind} name {@code merchant} as their {@code merchant_subject_ref} and were observed at or after
     * {@code since} - a merchant's refunds or chargebacks (`P13-TSK-014`; one range scan on {@code
     * risk_signal_by_merchant}).
     */
    int countByMerchantSince(Connection unitOfWork, UUID merchant, SignalKind kind, Instant since);

    /** The latest signal of {@code kind} about {@code subject}, by {@code observed_at}. */
    Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind);

    /** When the first signal of {@code kind} about {@code subject} was observed. */
    Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind);

    /** Whether any signal of any kind exists about {@code subject} - a device's novelty is the absence of one. */
    boolean seen(Connection unitOfWork, SignalSubject subject);

    /**
     * The signal {@code source} was recorded as, if any (`P13-TSK-025`) - read by T-sig when an insert answered that the
     * event was already recorded and the signal's kind raises an alert of its own ({@link SignalAlerts}): the raise is
     * made over the standing row, never over the delivery's unrecorded copy. One probe of the source event's unique.
     *
     * <p>A store that cannot answer it refuses: the suites' fakes are never wired with an alerting kind.
     */
    default Optional<RiskSignal> recordedFrom(Connection unitOfWork, EventId source) {
        throw new UnsupportedOperationException("this signal store does not read a signal by its source event");
    }
}
