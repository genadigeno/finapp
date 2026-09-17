package com.finapp.transfers;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Saves a destination for a party ({@code P4-TSK-006}): validate that the destination exists
 * through the resolution port, then create or converge on the caller's connection.
 *
 * <p><strong>The destination must exist and be a customer-owned product, judged from
 * authoritative state per decision</strong> — {@link TransferParticipants#destination} is the
 * one definition of "a product holding a customer wallet", so a beneficiary can only ever name
 * something a transfer could resolve. <strong>Existence only, deliberately.</strong>
 * Postability is not this decision's question: it goes stale by design (a product suspended
 * tomorrow invalidates nothing saved today), and re-judging it belongs to the transfer's own
 * execution — which is also why a saved destination is never re-validated on any later
 * transfer (the backlog's own scope sentence, and plan §7's accepted race).
 *
 * <p>An unknown destination throws with <strong>nothing written</strong> — a boundary mistake,
 * never a committed outcome (the {@code TransferExecution} boundary-mistake shape): the caller
 * named nothing, so there is no fact to record.
 *
 * <p><strong>What deliberately does not happen here</strong>: no audit record and no step-up —
 * {@code transfers.BeneficiaryAdded} and the conditional {@code MULTI_FACTOR} check arrive with
 * the surface whose design fixes them ({@code P4-TSK-007}, plan §11), wrapped around this
 * command in the same transaction.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public final class BeneficiaryCreation<T> {

    private final TransferParticipants<T> participants;
    private final BeneficiaryStore<T> beneficiaries;
    private final IdGenerator ids;
    private final Clock clock;

    public BeneficiaryCreation(
            TransferParticipants<T> participants,
            BeneficiaryStore<T> beneficiaries,
            IdGenerator ids,
            Clock clock) {
        this.participants = Objects.requireNonNull(participants, "participants must not be null");
        this.beneficiaries =
                Objects.requireNonNull(beneficiaries, "beneficiaries must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Save the destination for the party, converging onto an already-live row for the same
     * (party, destination) — the natural-key idempotency the store documents.
     *
     * @throws UnknownBeneficiaryDestinationException when the destination resolves to no
     *     customer wallet; nothing is written
     */
    public BeneficiaryStore.Creation createOrConverge(
            T unitOfWork, UUID callerPartyId, String displayName, UUID destinationProductRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
        Objects.requireNonNull(destinationProductRef, "destinationProductRef must not be null");
        // The display name is validated by the aggregate's constructor below, before any write.
        participants
                .destination(unitOfWork, destinationProductRef)
                .orElseThrow(UnknownBeneficiaryDestinationException::new);
        return beneficiaries.createOrConverge(
                unitOfWork,
                Beneficiary.create(
                        BeneficiaryId.next(ids),
                        callerPartyId,
                        displayName,
                        destinationProductRef,
                        clock));
    }
}
