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
 * <p><strong>Resolution is per decision and keyed by currency</strong> (`P9-TSK-004`). Per
 * decision: the implementation reads authoritative state inside the execution transaction,
 * never a cache and never a request's claim (the {@code ConsentGate} discipline). Keyed by
 * currency: a product holds one wallet per currency (ADR-0076 §6), and each side answers
 * <em>its wallet in the transfer's currency</em> — or, when it holds none in that currency,
 * its first-opened wallet with <em>that wallet's own</em> currency, because a
 * currency-mismatched transfer still commits {@code FAILED(CURRENCY_MISMATCH)} carrying the
 * real accounts: a refusal row whose accounts could not be stored would be unconstructible
 * (`P4-TSK-003`'s coherence). {@code CURRENCY_MISMATCH} therefore means exactly "a side holds
 * no wallet in this currency" — never an arbitrary pick among several.
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
     * party → live customer → owned product → its wallet in {@code currency} (else its
     * first-opened wallet) — or empty when any link is absent: unknown product, somebody else's
     * product, or a party with no live customer are one indistinguishable answer.
     */
    Optional<Source> sourceOwnedBy(
            T unitOfWork, UUID callerPartyId, UUID sourceProductRef, CurrencyCode currency);

    /**
     * The destination product's wallet in {@code currency} (else its first-opened wallet), or
     * empty when no such product holds a wallet at all.
     */
    Optional<Side> destination(T unitOfWork, UUID destinationProductRef, CurrencyCode currency);

    /**
     * Whether {@code destinationProductRef} names a product holding any wallet — the
     * beneficiary's question, which is about existence and never about a currency.
     */
    boolean destinationExists(T unitOfWork, UUID destinationProductRef);
}
