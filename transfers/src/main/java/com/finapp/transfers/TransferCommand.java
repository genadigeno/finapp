package com.finapp.transfers;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One commanded transfer: what the caller asked for, before any judgement (`P4-TSK-005`).
 *
 * <p>The products are raw {@code UUID} references and the caller is a party — resolution to
 * customers and ledger accounts happens inside the execution transaction against authoritative
 * state ({@link TransferParticipants}), never here and never from a request's claims. The
 * reference is the caller's memo, nullable, bounded at the boundary and the column.
 */
public record TransferCommand(
        String idempotencyKey,
        UUID callerPartyId,
        UUID sourceProductRef,
        UUID destinationProductRef,
        Money amount,
        String reference) {

    public TransferCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
        Objects.requireNonNull(sourceProductRef, "sourceProductRef must not be null");
        Objects.requireNonNull(destinationProductRef, "destinationProductRef must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
