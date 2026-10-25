package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStatus;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.party.Customer;
import com.finapp.party.CustomerId;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.risk.MonitoredParties;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Risk's account → party join (`P13-TSK-020`; {@link AccountMonitoredParties}; {@code DESIGN-020} F1, F2) over fakes of
 * accounts' and party's ports: the forward join through accounts' system read of an agreement's customer and party's
 * {@code partyOfCustomer} - empty where a link is missing; the reverse through the live customer's agreements, every
 * status, with the earliest opening; a party beyond the bound refused. The statements are
 * {@code AccountMonitoredPartiesDatabaseTest}'s (written and owed, C5).
 */
@DisplayName("risk's account -> party join for the monitoring rules (P13-TSK-020)")
class AccountMonitoredPartiesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            AccountMonitoredPartiesTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    private final Map<UUID, CustomerAccount> agreements = new HashMap<>();
    private final Map<UUID, Customer> customers = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final AccountMonitoredParties join = new AccountMonitoredParties(parties(), accounts());

    @Test
    @DisplayName("forward: an agreement's customer, then the customer's party - and nothing where a link is missing")
    void theForwardJoin() {
        UUID party = IDS.next();
        Customer customer = customer(party);
        CustomerAccount wallet = agreement(customer, Instant.parse("2025-03-01T08:00:00Z"));

        assertThat(join.partyOfAccount(UOW, wallet.id().value())).contains(party);
        assertThat(calls).containsExactly("customerOf", "partyOfCustomer");
        assertThat(join.partyOfAccount(UOW, IDS.next())).as("no such agreement").isEmpty();

        CustomerAccount orphan = CustomerAccount.rehydrate(CustomerAccountId.of(IDS.next()), IDS.next(),
                ProductType.WALLET, CustomerAccountStatus.ACTIVE, CLOCK.instant(), CLOCK.instant());
        agreements.put(orphan.id().value(), orphan);
        assertThat(join.partyOfAccount(UOW, orphan.id().value())).as("a customer party does not know").isEmpty();
    }

    @Test
    @DisplayName("reverse: the live customer's agreements of every status, with the earliest opening - none for a"
            + " party with no live customer")
    void theReverseJoin() {
        UUID party = IDS.next();
        Customer customer = customer(party);
        CustomerAccount first = agreement(customer, Instant.parse("2025-03-01T08:00:00Z"));
        CustomerAccount closed = CustomerAccount.rehydrate(CustomerAccountId.of(IDS.next()), customer.id().value(),
                ProductType.WALLET, CustomerAccountStatus.CLOSED, Instant.parse("2024-11-11T11:00:00Z"),
                CLOCK.instant());
        agreements.put(closed.id().value(), closed);

        MonitoredParties.Accounts accounts = join.accountsOf(UOW, party);
        assertThat(accounts.ids()).containsExactlyInAnyOrder(first.id().value(), closed.id().value());
        assertThat(accounts.firstOpenedAt()).contains(Instant.parse("2024-11-11T11:00:00Z"));
        assertThat(join.accountsOf(UOW, IDS.next())).isEqualTo(MonitoredParties.Accounts.NONE);
    }

    @Test
    @DisplayName("a party holding more agreements than the bound is refused, never cut")
    void theBound() {
        UUID party = IDS.next();
        Customer customer = customer(party);
        for (int i = 0; i <= MonitoredParties.BOUND; i++) {
            agreement(customer, CLOCK.instant());
        }
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> join.accountsOf(UOW, party));
    }

    // ------------------------------------------------------------------

    private Customer customer(UUID party) {
        Customer customer = Customer.rehydrate(CustomerId.of(IDS.next()), PartyId.of(party), CustomerStatus.ACTIVE,
                CLOCK.instant(), CLOCK.instant());
        customers.put(party, customer);
        return customer;
    }

    private CustomerAccount agreement(Customer customer, Instant opened) {
        CustomerAccount account = CustomerAccount.rehydrate(CustomerAccountId.of(IDS.next()), customer.id().value(),
                ProductType.WALLET, CustomerAccountStatus.ACTIVE, opened, opened);
        agreements.put(account.id().value(), account);
        return account;
    }

    @SuppressWarnings("unchecked")
    private PartyStore<Connection> parties() {
        return (PartyStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PartyStore.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "partyOfCustomer" -> customers.values().stream()
                                .filter(customer -> customer.id().equals(args[1])).findFirst()
                                .map(Customer::partyId);
                        case "findLiveCustomerFor" -> Optional.ofNullable(customers.get(((PartyId) args[1]).value()));
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    @SuppressWarnings("unchecked")
    private CustomerAccountStore<Connection> accounts() {
        return (CustomerAccountStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {CustomerAccountStore.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "customerOf" -> Optional.ofNullable(agreements.get(((CustomerAccountId) args[1]).value()))
                                .map(CustomerAccount::customerId);
                        case "findAllFor" -> agreements.values().stream()
                                .filter(account -> account.customerId().equals(args[1])).toList();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
