package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where outbound credits rest (`P9-TSK-019`, payments {@code V025}): born {@code DISPATCHED} with our
 * end-to-end reference and a database-stamped permit, the instruction frozen, the machine held by trigger.
 * The resolution's writes (`P9-TSK-020`) are conditional on the state read under the row's lock, and the facts
 * recorded beside the machine - the provider's reference, the delivery - are each set once.
 */
public interface OutboundCreditStore {

    /** The outbound credit's states (the lifecycle document 3.6). */
    enum Status {
        DISPATCHED,
        UNKNOWN,
        RECEIVED,
        COMPLETED,
        FAILED;

        /** Not yet concluded: an answer can still complete or fail it. */
        public boolean resolvable() {
            return this == DISPATCHED || this == UNKNOWN || this == RECEIVED;
        }
    }

    /** Why a credit will never execute (payments {@code V025}'s {@code failure_reason}). */
    /** The provider's definitive answer to a recall (`P9-TSK-024`): only {@code RECALLED} concludes the credit. */
    enum RecallOutcome {
        /** Recalled before acceptance: the credit fails {@code RECALLED}. */
        RECALLED,
        /** Too late - the provider had committed: nothing concluded, the credit completes as it would have. */
        REFUSED
    }

    enum FailureReason {
        /** The provider definitively refused it. */
        DECLINED,
        /** A first send reached nobody: the provider never had it. */
        PROVIDER_UNAVAILABLE,
        /** The provider does not know it, past the rail's declared deadline and margin since the latest permit. */
        NEVER_RECEIVED,
        /** The customer's cancellation, confirmed by the provider (`P9-TSK-024`). */
        RECALLED
    }

    /** A new credit, before its first send. */
    record Draft(
            OutboundCreditId id,
            UUID customerParty,
            UUID subject,
            String dispatchKey,
            RailId rail,
            ProviderReference destination,
            Money amount,
            Money held,
            UUID holdId,
            EndToEndReference reference) {
        public Draft {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(customerParty, "customerParty must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(held, "held must not be null");
            Objects.requireNonNull(holdId, "holdId must not be null");
            Objects.requireNonNull(reference, "reference must not be null");
        }

        @Override
        public String toString() {
            return "Draft[id=" + id + ", rail=" + rail + ", destination=<redacted>]";
        }
    }

    /** A credit as stored. */
    record Row(
            OutboundCreditId id,
            UUID customerParty,
            UUID subject,
            String dispatchKey,
            RailId rail,
            ProviderReference destination,
            Money amount,
            Money held,
            UUID holdId,
            EndToEndReference reference,
            Status status,
            Optional<String> providerReference,
            Instant createdAt,
            Instant lastDispatchedAt,
            Optional<FailureReason> failureReason,
            Optional<Instant> deliveredAt,
            Optional<Instant> recallRequestedAt,
            Optional<RecallOutcome> recallOutcome) {
        public Row {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(providerReference, "providerReference must not be null");
            Objects.requireNonNull(failureReason, "failureReason must not be null");
            Objects.requireNonNull(deliveredAt, "deliveredAt must not be null");
            Objects.requireNonNull(recallRequestedAt, "recallRequestedAt must not be null");
            Objects.requireNonNull(recallOutcome, "recallOutcome must not be null");
        }

        /** A customer's cancellation asked for a recall the provider has not yet answered (`P9-TSK-024`). */
        public boolean recallPending() {
            return recallRequestedAt.isPresent() && recallOutcome.isEmpty() && status.resolvable();
        }

        /** Whether the latest send permit is at or before {@code bound} - the never-received rule's test. */
        public boolean permitAtOrBefore(Instant bound) {
            return !lastDispatchedAt.isAfter(bound);
        }

        @Override
        public String toString() {
            return "Row[id=" + id + ", status=" + status + ", destination=<redacted>]";
        }
    }

    void insert(Connection unitOfWork, Draft draft);

    Optional<Row> bySubject(Connection unitOfWork, UUID subject);

    /** The credit our end-to-end reference names - the callback's only usable claim. */
    Optional<Row> byReference(Connection unitOfWork, EndToEndReference reference);

    /** The credit under {@code FOR UPDATE}. */
    Optional<Row> lock(Connection unitOfWork, OutboundCreditId id);

    /** Moves {@code id} from {@code from} to {@code to}; false when it was not there. */
    boolean move(Connection unitOfWork, OutboundCreditId id, Status from, Status to);

    /** Moves {@code id} from {@code from} to {@code COMPLETED}, the provider's reference stored once. */
    boolean complete(Connection unitOfWork, OutboundCreditId id, Status from, ProviderReference providerReference);

    /** Moves {@code id} from {@code from} to {@code FAILED} with its reason. */
    boolean fail(Connection unitOfWork, OutboundCreditId id, Status from, FailureReason reason);

    /** Stores the provider's reference once; a stored one is never replaced. */
    void recordProviderReference(Connection unitOfWork, OutboundCreditId id, ProviderReference providerReference);

    /** Stores a completed credit's delivery once; false when it was already stored or the credit is not completed. */
    boolean markDelivered(Connection unitOfWork, OutboundCreditId id, Instant deliveredAt);

    /**
     * Renews the send permit - stamped forward by the database - before a re-send of the same reference; false, and
     * nothing renewed, once a recall is requested: an instruction with a recall requested is never re-sent (D22).
     */
    boolean renewPermit(Connection unitOfWork, OutboundCreditId id);

    /**
     * Marks the credit for a recall once (`P9-TSK-024`): conditional on a status the provider may still recall
     * ({@code DISPATCHED}, {@code UNKNOWN}, {@code RECEIVED}) and on no request standing; false otherwise.
     */
    boolean requestRecall(Connection unitOfWork, OutboundCreditId id);

    /** Stores the provider's recall answer once, beside a standing request; false when one is already stored. */
    boolean recordRecallOutcome(Connection unitOfWork, OutboundCreditId id, RecallOutcome outcome);

    /**
     * The credits due an inquiry, oldest permit first, read without a lock: {@code DISPATCHED} past
     * {@code dispatchedAge}, {@code UNKNOWN} past {@code unknownAge}, {@code RECEIVED} past {@code receivedAge} -
     * each since its latest permit, on the database clock - and {@code COMPLETED} credits whose delivery is not yet
     * known, past {@code deliveryAge} since their birth - and, at once, every credit whose recall is requested and
     * not yet answered (`P9-TSK-024`), paced by the sweep's own poll.
     */
    List<Row> findDue(Connection unitOfWork, Duration dispatchedAge, Duration unknownAge, Duration receivedAge,
            Duration deliveryAge, int limit);

    /** The stuck-credit gauge: {@code UNKNOWN} credits, and {@code DISPATCHED} ones past {@code dispatchedAge}. */
    PaymentAttemptStore.UnknownReading unknownReading(Connection unitOfWork, Duration dispatchedAge);

    /** The {@code RECEIVED} credits and the oldest one's wait since its latest permit, in seconds. */
    PaymentAttemptStore.UnknownReading receivedReading(Connection unitOfWork);
}
