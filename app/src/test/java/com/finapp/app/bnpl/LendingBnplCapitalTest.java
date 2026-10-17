package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplHeadroom;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.LedgerStorageException;
import com.finapp.ledger.OwnerKind;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanStore;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * bnpl's capital port over lending's headroom, hermetic (`P12-TSK-005`; ADR-0104 decision 3, PHASE_12_PLAN.md
 * sections 7.2 and 12.9; {@code INV-BNPL-10}, {@code INV-LND-13}): {@link LendingBnplCapital} over a real
 * {@link LendingCapitalHeadroom} whose ledger is in memory - the capital row locked {@code FOR UPDATE} on bnpl's own
 * connection before anything is read (never {@code atRest}), the figure lending's one headroom with the BNPL principal
 * counted, and a failed read failing bnpl's transaction rather than answering a headroom. The lock against a real row -
 * a BNPL judgement and a loan acceptance serialising on it, both orders - is {@code BnplCapitalDatabaseTest}'s.
 */
@DisplayName("bnpl's capital port: lending's one headroom, under L6, on bnpl's connection (P12-TSK-005)")
class LendingBnplCapitalTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final List<String> events = new ArrayList<>();
    private final Map<AccountPurpose, Long> netDebits = new EnumMap<>(AccountPurpose.class);
    private final LedgerAccountId capitalId = LedgerAccountId.of(new IdGenerator(Clock.systemUTC(), new SecureRandom()).next());
    private final Connection bnplsConnection = connection("bnpl's transaction");
    private boolean failing;
    private long committedMinor;

    private final LendingBnplCapital port =
            new LendingBnplCapital(new LendingCapitalHeadroom(chart(), balances(), loans()));

    @Test
    @DisplayName("the capital row is locked FOR UPDATE on bnpl's own connection before the headroom is read - L6 in"
            + " bnpl's transaction, never an unlocked reading")
    void theCapitalRowIsLockedOnBnplsConnectionFirst() {
        netDebits.put(AccountPurpose.LENDING_CAPITAL, -1_000_00L);
        port.underLock(bnplsConnection, EUR);
        List<String> significant = events.stream().filter(event -> !event.startsWith("find")).toList();
        assertThat(significant).first().as("L6 before any read").isEqualTo("lock:" + capitalId.value() + "@bnpl's transaction");
        assertThat(significant).hasSize(4).allMatch(event -> event.endsWith("@bnpl's transaction"));
    }

    @Test
    @DisplayName("the figure is lending's one headroom: capital less committed less loan and BNPL principal")
    void theFigureIsLendingsHeadroom() {
        netDebits.put(AccountPurpose.LENDING_CAPITAL, -2_000_00L);
        committedMinor = 300_00;
        netDebits.put(AccountPurpose.LOAN_PRINCIPAL, 400_00L);
        netDebits.put(AccountPurpose.BNPL_PRINCIPAL, 500_00L);
        netDebits.put(AccountPurpose.BNPL_PRINCIPAL_DUE, 100_00L);
        BnplHeadroom judged = port.underLock(bnplsConnection, EUR);
        assertThat(judged.currency()).isEqualTo(EUR);
        assertThat(judged.headroom()).isEqualTo(Money.ofMinorUnits(700_00, EUR));
        assertThat(judged.admits(Money.ofMinorUnits(700_00, EUR))).isTrue();
        assertThat(judged.admits(Money.ofMinorUnits(700_01, EUR))).isFalse();
    }

    @Test
    @DisplayName("a failed read fails bnpl's transaction - no headroom is answered, of zero or otherwise")
    void aFailedReadFailsTheTransaction() {
        netDebits.put(AccountPurpose.LENDING_CAPITAL, -1_000_00L);
        failing = true;
        assertThatThrownBy(() -> port.underLock(bnplsConnection, EUR)).isInstanceOf(LedgerStorageException.class);
    }

    // ------------------------------------------------------------------ the in-memory ledger

    private static Connection connection(String name) {
        return (Connection) Proxy.newProxyInstance(
                LendingBnplCapitalTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) {
                        return name;
                    }
                    throw new UnsupportedOperationException("the fakes never touch the connection");
                });
    }

    @SuppressWarnings("unchecked")
    private LedgerAccountStore<Connection> chart() {
        LedgerAccount capital = LedgerAccount.rehydrate(
                capitalId, AccountType.EQUITY, AccountType.EQUITY.normalBalance(), EUR, OwnerKind.OPERATIONAL, null,
                AccountPurpose.LENDING_CAPITAL, null, LedgerAccountStatus.ACTIVE, Instant.EPOCH, Instant.EPOCH);
        return (LedgerAccountStore<Connection>) Proxy.newProxyInstance(
                LendingBnplCapitalTest.class.getClassLoader(), new Class<?>[] {LedgerAccountStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findOperational" -> {
                        events.add("find@" + args[0]);
                        yield Optional.of(capital);
                    }
                    case "lockForUpdate" -> {
                        events.add("lock:" + ((LedgerAccountId) args[1]).value() + "@" + args[0]);
                        yield Optional.of(capital);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private PurposeBalances<Connection> balances() {
        return (PurposeBalances<Connection>) Proxy.newProxyInstance(
                LendingBnplCapitalTest.class.getClassLoader(), new Class<?>[] {PurposeBalances.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("netDebit")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Set<AccountPurpose> purposes = (Set<AccountPurpose>) args[1];
                    events.add("netDebit" + purposes + "@" + args[0]);
                    if (failing && !purposes.contains(AccountPurpose.LENDING_CAPITAL)) {
                        throw new LedgerStorageException("the principal lines could not be read");
                    }
                    long sum = purposes.stream().mapToLong(purpose -> netDebits.getOrDefault(purpose, 0L)).sum();
                    return Money.ofMinorUnits(sum, (CurrencyCode) args[2]);
                });
    }

    private LoanStore loans() {
        return (LoanStore) Proxy.newProxyInstance(
                LendingBnplCapitalTest.class.getClassLoader(), new Class<?>[] {LoanStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("committedPrincipal")) {
                        events.add("committed@" + args[0]);
                        return Money.ofMinorUnits(committedMinor, (CurrencyCode) args[1]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
