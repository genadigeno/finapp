package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where an outbound credit's return rests (`P9-TSK-023`, payments {@code V027}): born once - {@code UNIQUE
 * (outbound_credit_id)} the arbiter of every channel - append-only, an applier's return exactly the instructed
 * credit by trigger.
 */
public interface OutboundCreditReturnStore {

    /** Who recorded the return: the applier (the inquiry or the report worker) or a person's resolution. */
    enum AppliedBy {
        APPLIER,
        RESOLUTION
    }

    /** One return, as born. */
    record Return(
            UUID id,
            OutboundCreditId credit,
            Money amount,
            Optional<String> returnReference,
            AppliedBy appliedBy,
            Optional<UUID> resolutionId,
            Optional<UUID> journalEntryId,
            Instant returnedAt) {
        public Return {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(credit, "credit must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(returnReference, "returnReference must not be null");
            Objects.requireNonNull(appliedBy, "appliedBy must not be null");
            Objects.requireNonNull(resolutionId, "resolutionId must not be null");
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(returnedAt, "returnedAt must not be null");
        }
    }

    /** Inserts the return; false when the credit's return already exists (the unique's conflict). */
    boolean insert(Connection unitOfWork, Return returned);

    /** The credit's return, if it has one. */
    Optional<Return> findByCredit(Connection unitOfWork, OutboundCreditId credit);
}
