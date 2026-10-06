package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where outbound credits rest (`P9-TSK-019`, payments {@code V025}): born {@code DISPATCHED} with our
 * end-to-end reference and a database-stamped permit, the instruction frozen, the machine held by trigger.
 */
public interface OutboundCreditStore {

    /** The outbound credit's states (the lifecycle document 3.6). */
    enum Status {
        DISPATCHED,
        UNKNOWN,
        RECEIVED,
        COMPLETED,
        FAILED
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
            Instant lastDispatchedAt) {
        public Row {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(providerReference, "providerReference must not be null");
        }

        @Override
        public String toString() {
            return "Row[id=" + id + ", status=" + status + ", destination=<redacted>]";
        }
    }

    void insert(Connection unitOfWork, Draft draft);

    Optional<Row> bySubject(Connection unitOfWork, UUID subject);

    /** The credit under {@code FOR UPDATE}. */
    Optional<Row> lock(Connection unitOfWork, OutboundCreditId id);

    /** Moves {@code id} from {@code from} to {@code to}; false when it was not there. */
    boolean move(Connection unitOfWork, OutboundCreditId id, Status from, Status to);

    /** Renews the send permit - stamped forward by the database - before a re-send of the same reference. */
    void renewPermit(Connection unitOfWork, OutboundCreditId id);
}
