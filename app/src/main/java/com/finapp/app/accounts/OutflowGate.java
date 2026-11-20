package com.finapp.app.accounts;

import com.finapp.accounts.AccountsErrorCode;
import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountStatus;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.platform.api.ApiException;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The one check every new customer-initiated outflow makes of its agreement (`P17-TSK-028`, ADR-0153 decisions 2 and 3; the
 * owner's ruling of 2026-11-20, "New outflows only"): the customer's live WALLET agreement read {@code FOR SHARE} in the
 * command's own transaction, refused {@code 409 accounts.OutflowSuspended} while {@code SUSPENDED}.
 *
 * <p><strong>The lock is the control.</strong> A suspension takes the same row {@code FOR UPDATE}
 * ({@code AccountRestrictions}), so a withdrawal, wallet payment, FX conversion or cross-border credit racing it commits
 * wholly before it - the suspension waits for this share lock - or reads {@code SUSPENDED} after it; never half. Called
 * <strong>before any ledger row is locked</strong>: the agreement, then the ledger, the closer's own order, so no cycle.
 *
 * <p>Answers nothing else: a party with no live customer or no live agreement is left to the command's own refusal (no
 * wallet), and a closed agreement's ledger accounts refuse the posting themselves. Credits, completions of movements already
 * in flight, repayments and corrections never call it.
 */
@RequiredArgsConstructor
public final class OutflowGate {

    @NonNull private final PartyStore<Connection> parties;
    @NonNull private final CustomerAccountStore<Connection> customerAccounts;

    /** {@link #requireForCustomer} for the party's live customer relationship, if it has one. */
    public void requireForParty(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        parties.findLiveCustomerFor(unitOfWork, PartyId.of(partyId))
                .ifPresent(customer -> requireForCustomer(unitOfWork, customer.id().value()));
    }

    /** Refuses the outflow when the customer's live WALLET agreement is {@code SUSPENDED}, holding its share lock to commit. */
    public void requireForCustomer(Connection unitOfWork, UUID customerId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Optional<CustomerAccount> locked = customerAccounts.findLive(unitOfWork, customerId, ProductType.WALLET)
                .flatMap(live -> customerAccounts.lockOwnedForShare(unitOfWork, live.id(), customerId));
        if (locked.isPresent() && locked.get().status() == CustomerAccountStatus.SUSPENDED) {
            throw new ApiException(AccountsErrorCode.OUTFLOW_SUSPENDED, "An outgoing movement was refused",
                    "the account is suspended; incoming money still lands, and it sends again once reinstated.");
        }
    }
}
