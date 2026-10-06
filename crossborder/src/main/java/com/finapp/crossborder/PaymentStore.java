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
            Instant createdAt,
            Optional<String> failureReason) {

        /** A payment as authorized - no failure yet. */
        public Row(
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
            this(id, owner, beneficiary, offer, quote, corridor, dispatchKey, outboundCredit, cover, hold, status, createdAt,
                    Optional.empty());
        }

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
            Objects.requireNonNull(failureReason, "failureReason must not be null");
        }

        /**
         * What the customer is shown (`P9-TSK-024`, PHASE_9_PLAN.md section 5): {@code CANCELLED} for a payment
         * recalled at their request - {@code FAILED(RECALLED)}, or {@code FAILED(NEVER_RECEIVED)} after a
         * cancellation request; otherwise the status's own shape. A recall refused as too late is never shown
         * cancelled: the payment completes, {@code SENT}.
         */
        public String shaped(boolean cancellationRequested) {
            if (status == Status.FAILED && (failureReason.equals(Optional.of("RECALLED"))
                    || cancellationRequested && failureReason.equals(Optional.of("NEVER_RECEIVED")))) {
                return "CANCELLED";
            }
            return status.shaped();
        }
    }

    void insert(Connection unitOfWork, Row payment);

    /** Appends one edge of the payment's history. */
    void appendEvent(Connection unitOfWork, UUID eventId, UUID payment, Optional<Status> from, Status to, String cause, Instant at);

    Optional<Row> byDispatchKey(Connection unitOfWork, UUID owner, String dispatchKey);

    /** {@code owner}'s payment {@code id}, if it is theirs. */
    Optional<Row> findOwned(Connection unitOfWork, UUID id, UUID owner);

    /**
     * The payment under {@code FOR UPDATE} - for its outbound credit's outcome applier only, the id the credit's
     * frozen subject (`P9-TSK-020`), never a request value.
     */
    Optional<Row> lock(Connection unitOfWork, UUID id);

    /** Moves {@code id} from {@code from} to {@code to}, with the failure class exactly when {@code to} is FAILED. */
    boolean move(Connection unitOfWork, UUID id, Status from, Status to, Optional<String> failureReason);
}
