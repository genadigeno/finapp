package com.finapp.transfers;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the two sides of a transfer from authoritative state (`P4-TSK-005`) — the port
 * {@code app} implements, because {@code transfers} may not see {@code accounts} or
 * {@code party} (the {@code AccountHolderVerification} shape): the module that moves the money
 * and the module that owns the product must not become one dependency ball.
 *
 * <p><strong>Resolution is per decision and currency-blind.</strong> Per decision: the
 * implementation reads authoritative state inside the execution transaction, never a cache and
 * never a request's claim (the {@code ConsentGate} discipline). Currency-blind: the side's
 * wallet comes back with <em>its own</em> currency, because a currency-mismatched transfer
 * still commits {@code FAILED(CURRENCY_MISMATCH)} carrying the real accounts — a refusal row
 * whose accounts could not be stored would be unconstructible (`P4-TSK-003`'s coherence).
 * Today a product holds one wallet (opened with one initial currency); multi-currency products
 * are Phase 9's, and this port's list-free shape is deliberately the seam that phase will
 * widen.
 *
 * <p><strong>The source is owned; the destination is merely real.</strong>
 * {@link #sourceOwnedBy} answers empty for unknown, malformed-ownership and not-yours alike —
 * one answer, so no surface built over it can become an oracle over other people's products
 * ({@code INV-IDN-07}'s reasoning at a port). {@link #destination} requires no ownership:
 * being the counterparty of a movement is a product's purpose, and what it discloses here is
 * bounded to existence, postability and currency — the facts the judgement itself must commit.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface TransferParticipants<T> {

    /** One side's wallet: the ledger account, its own currency, and whether it may post. */
    record Side(LedgerAccountId account, CurrencyCode currency, boolean postable) {}

    /** The source side, plus the owning customer the transfer records. */
    record Source(UUID customerId, Side side) {}

    /**
     * The caller's product, resolved through the caller's <strong>live customer</strong> —
     * party → live customer → owned product → wallet — or empty when any link is absent:
     * unknown product, somebody else's product, or a party with no live customer are one
     * indistinguishable answer.
     */
    Optional<Source> sourceOwnedBy(T unitOfWork, UUID callerPartyId, UUID sourceProductRef);

    /** The destination product's wallet, or empty when no such product holds one. */
    Optional<Side> destination(T unitOfWork, UUID destinationProductRef);
}
