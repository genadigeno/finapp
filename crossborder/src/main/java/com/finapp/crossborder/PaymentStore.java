package com.finapp.crossborder;

import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Where cross-border payments rest (`P9-TSK-019`, {@code crossborder V005}). */
public interface PaymentStore {

    /** The payment's states (the lifecycle document 3.5). */
    enum Status {
        SUBMITTED,
        IN_TRANSIT,
        DELIVERED,
        RETURNED,
        FAILED;

        /** What a customer is shown (the lifecycle document 3.5's shapes). */
        public String shaped() {
            return switch (this) {
                case SUBMITTED -> "PROCESSING";
                case IN_TRANSIT -> "SENT";
                case DELIVERED -> "DELIVERED";
                case RETURNED -> "RETURNED";
                case FAILED -> "FAILED";
            };
        }
    }

    /** A payment as stored. */
    record Row(
            UUID id,
            UUID owner,
            BeneficiaryId beneficiary,
            UUID offer,
            UUID quote,
            CorridorKey corridor,
            String dispatchKey,
            UUID outboundCredit,
            UUID cover,
            UUID hold,
            Status status,
            Instant createdAt) {
        public Row {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(beneficiary, "beneficiary must not be null");
            Objects.requireNonNull(offer, "offer must not be null");
            Objects.requireNonNull(quote, "quote must not be null");
            Objects.requireNonNull(corridor, "corridor must not be null");
            Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
            Objects.requireNonNull(outboundCredit, "outboundCredit must not be null");
            Objects.requireNonNull(cover, "cover must not be null");
            Objects.requireNonNull(hold, "hold must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    void insert(Connection unitOfWork, Row payment);

    /** Appends one edge of the payment's history. */
    void appendEvent(Connection unitOfWork, UUID eventId, UUID payment, Optional<Status> from, Status to, String cause, Instant at);

    Optional<Row> byDispatchKey(Connection unitOfWork, UUID owner, String dispatchKey);

    /** {@code owner}'s payment {@code id}, if it is theirs. */
    Optional<Row> findOwned(Connection unitOfWork, UUID id, UUID owner);
}
