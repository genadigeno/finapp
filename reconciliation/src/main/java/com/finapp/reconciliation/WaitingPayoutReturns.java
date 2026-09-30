package com.finapp.reconciliation;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciliation's public read of the payout returns waiting for their worker (`P8-TSK-019`,
 * ADR-0073 §4): {@code UNMATCHED} {@code PAYOUT_RETURNED} items — lines the operation-anchored
 * rule could not yet allocate, because no {@code PAYOUT_RETURN} stands for their payout. The
 * return worker in {@code app} reads a page, and re-reads each item under a SHARE lock before it
 * applies anything: the item judged on its locked row is what orders the worker against the
 * grace leg, which locks the same row {@code FOR UPDATE} (ADR-0073 §7). Never SQL across schemas
 * — this is the seam.
 */
public interface WaitingPayoutReturns {

    /**
     * One waiting return, as the worker needs it.
     *
     * @param batchId the settlement batch the item's run copied — whose stored {@code
     *     accepted_on} dates the return's posting
     * @param settlementDate the item's settlement date — the return's value date
     * @param providerReference the line's {@code PAYOUT_PROVIDER_REF}, when it carries one
     * @param ourReference the line's {@code OUR_REF}, when it carries one
     * @param correlation the item's own correlation — the flow the return continues
     */
    record WaitingReturn(
            UUID itemId,
            UUID runId,
            UUID batchId,
            UUID sourceId,
            Money amount,
            LocalDate settlementDate,
            Optional<String> providerReference,
            Optional<String> ourReference,
            CorrelationId correlation) {

        public WaitingReturn {
            Objects.requireNonNull(itemId, "itemId must not be null");
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(batchId, "batchId must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(settlementDate, "settlementDate must not be null");
            Objects.requireNonNull(providerReference, "providerReference must not be null");
            Objects.requireNonNull(ourReference, "ourReference must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }
    }

    /**
     * A bounded page of waiting returns, in claimant order {@code (source_sequence, line_no,
     * id)}, lock-free — each is re-read under its own lock before anything is done with it —
     * strictly after the item {@code after} names in that order, when it names one. The
     * worker walks every page in a tick: a return that cannot apply yet stays waiting until its
     * grace, and a fixed first page of them would starve every return behind it.
     */
    List<WaitingReturn> page(Connection unitOfWork, Optional<UUID> after, int limit);

    /**
     * The item re-read {@code FOR SHARE}, only while it is still an {@code UNMATCHED}
     * {@code PAYOUT_RETURNED} item — empty when the grace leg parked it, a person resolved it or
     * the rematch leg allocated it meanwhile, and then the worker writes nothing.
     */
    Optional<WaitingReturn> lockWaiting(Connection unitOfWork, UUID itemId);
}
