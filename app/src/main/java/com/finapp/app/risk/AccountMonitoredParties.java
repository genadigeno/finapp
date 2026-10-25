package com.finapp.app.risk;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.party.CustomerId;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.risk.MonitoredParties;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Risk's account → party join over accounts and party (`P13-TSK-020`; {@link MonitoredParties}; {@code DESIGN-020} F1,
 * F2; the composition root's half, because {@code risk} sees neither module) - the party behind a monitored
 * transaction's customer account, and the accounts behind a party, for the monitoring rules. Read on the claim's own
 * connection under its locks, never cached; identifiers only leave.
 *
 * <ul>
 *   <li><strong>The forward join</strong> - a customer account (the ledger chart's {@code owner_ref}, carried by the
 *       monitored transaction) → its customer ({@link CustomerAccountStore#customerOf}, the system's read: the owner and
 *       nothing else of the agreement) → its party ({@link PartyStore#partyOfCustomer}). Empty when either link is
 *       missing - an account no party is placed behind is counted and unjudged.</li>
 *   <li><strong>The reverse</strong> - a party → its live customer ({@link PartyStore#findLiveCustomerFor}) → every
 *       agreement it holds or held ({@link CustomerAccountStore#findAllFor}, every status), with the earliest opening -
 *       the dormancy's anchor. A party with no live customer holds none here (owner item: a party whose customer closed
 *       and reopened is read through its live customer only).</li>
 * </ul>
 *
 * <p>This is the join `-007`'s {@code IdentitySubjectParties} left for this task (`DESIGN-007` D5). It is deliberately
 * not wired into {@code partyOf}: placing an {@code ACCOUNT} there would make every account signal pull its party's
 * profile permit - a `-007` behaviour change the monitoring does not need, because every AML alert's subject is the
 * {@code PARTY} itself, which that join already places.
 */
public final class AccountMonitoredParties implements MonitoredParties {

    private final PartyStore<Connection> parties;
    private final CustomerAccountStore<Connection> accounts;

    public AccountMonitoredParties(PartyStore<Connection> parties, CustomerAccountStore<Connection> accounts) {
        this.parties = Objects.requireNonNull(parties, "parties");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override
    public Optional<UUID> partyOfAccount(Connection unitOfWork, UUID customerAccountId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(customerAccountId, "customerAccountId");
        return accounts.customerOf(unitOfWork, CustomerAccountId.of(customerAccountId))
                .flatMap(customer -> parties.partyOfCustomer(unitOfWork, CustomerId.of(customer)))
                .map(PartyId::value);
    }

    @Override
    public Accounts accountsOf(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        List<CustomerAccount> held = parties.findLiveCustomerFor(unitOfWork, PartyId.of(partyId))
                .map(customer -> accounts.findAllFor(unitOfWork, customer.id().value()))
                .orElse(List.of());
        if (held.size() > MonitoredParties.BOUND) {
            throw new IllegalStateException("a party's accounts are read at most " + MonitoredParties.BOUND);
        }
        return Accounts.of(held.stream().map(account -> account.id().value()).toList(),
                held.stream().map(CustomerAccount::openedAt).toList());
    }
}
