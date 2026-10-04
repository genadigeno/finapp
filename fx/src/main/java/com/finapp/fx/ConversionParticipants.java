package com.finapp.fx;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * Who may convert (`P9-TSK-008`; PHASE_9_PLAN.md section 3's port table): declared by {@code fx},
 * implemented in {@code app} over the party projection, answered per decision with no cache. The
 * wallet by currency and its open-if-absent arrive with the conversion (`P9-TSK-009`).
 */
public interface ConversionParticipants {

    /**
     * The party's customer, only while its standing is {@code ACTIVE}; empty for every other case
     * alike - no such party, not a customer, unverified, suspended - so no caller can tell them
     * apart.
     */
    Optional<UUID> activeCustomer(Connection unitOfWork, UUID partyId);

    /**
     * The customer's wallet in {@code currency}, in their one live WALLET agreement - exactly
     * that currency, never a fallback (`P9-TSK-009`). Empty when the customer holds no such
     * agreement or no wallet in the currency.
     */
    Optional<com.finapp.ledger.LedgerAccountId> wallet(Connection unitOfWork, UUID customerId, com.finapp.sharedkernel.money.CurrencyCode currency);

    /**
     * Ensures the customer's live WALLET agreement holds a wallet in {@code currency}, opening it
     * when absent in the caller's transaction (D28: insert-if-absent, then a re-read - ten racers
     * open one, none aborts) - empty when the customer holds no live agreement.
     */
    Optional<com.finapp.ledger.LedgerAccountId> openIfAbsent(Connection unitOfWork, UUID customerId, com.finapp.sharedkernel.money.CurrencyCode currency);
}
