package com.finapp.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * The ledger account a source's position IS, in one currency (`P9-TSK-011`, ADR-0078 section 8):
 * the shared operational account for a Phase 8 clearing, and the counterparty's OWN account for a
 * counterparty-owned purpose - {@code fx-sim-a}'s {@code FX_PROVIDER_CLEARING}, never a shared one
 * ({@code INV-RAIL-04}). Reconciliation names no counterparty and sees no settlement descriptor, so
 * the composition root answers from the register it composed - the RailOfSource shape - and the
 * matcher parks an item on the account its own source settles.
 */
@FunctionalInterface
public interface PositionAccounts {

    /** The account {@code sourceId}'s evidence settles for {@code position} in {@code currency}. */
    Optional<UUID> accountOf(Connection unitOfWork, UUID sourceId, AccountPurpose position, CurrencyCode currency);

    /**
     * The Phase 8 answer - the shared operational account, whoever the source - for a composition
     * with no counterparty-owned position (the matcher's suites).
     */
    static PositionAccounts operational(LedgerAccountStore<Connection> accounts) {
        return (unitOfWork, sourceId, position, currency) ->
                accounts.findOperational(unitOfWork, position, currency).map(account -> account.id().value());
    }
}
