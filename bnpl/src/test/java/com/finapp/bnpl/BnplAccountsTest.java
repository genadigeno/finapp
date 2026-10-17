package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.NormalBalance;
import com.finapp.ledger.OwnerKind;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The opener's declaration and its refusals (`P12-TSK-003`; ADR-0103 decision 1, {@code INV-LED-06},
 * {@code INV-BNPL-01}): which six accounts an obligation has, what type each is, and that an unsupported
 * currency or a wrong-shaped account is refused - never adopted - before anything is written. Against an
 * in-memory {@link LedgerAccountStore} that converges on {@code (owner_ref, purpose, currency)} as the ledger's
 * partial unique index does; the live half - opened, converged and typed in the database, two openers racing,
 * the caller's rollback - is {@code BnplChartDatabaseTest}'s.
 */
@DisplayName("an obligation's six accounts are declared once, typed as the account plan and refused when"
        + " mis-shaped (P12-TSK-003)")
class BnplAccountsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Connection UNIT_OF_WORK = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, arguments) -> null);

    @Test
    @DisplayName("the opener opens exactly the BNPL_OBLIGATION-owned purposes, each once, in a fixed order")
    void theOpenerCoversTheObligationOwnedPurposes() {
        assertThat(BnplAccounts.purposes())
                .as("every BNPL_OBLIGATION-owned purpose the ledger declares, and nothing else")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(AccountPurpose.values())
                        .filter(purpose -> purpose.ownerKind() == OwnerKind.BNPL_OBLIGATION)
                        .toList())
                .doesNotHaveDuplicates()
                .hasSize(6);
        assertThat(BnplAccounts.purposes())
                .as("the fixed opening order concurrent openers share - never a cycle")
                .containsExactly(
                        AccountPurpose.BNPL_PRINCIPAL,
                        AccountPurpose.BNPL_PRINCIPAL_DUE,
                        AccountPurpose.BNPL_INTEREST_ACCRUED,
                        AccountPurpose.BNPL_INTEREST_DUE,
                        AccountPurpose.BNPL_FEES_DUE,
                        AccountPurpose.BNPL_CREDIT_BALANCE);
    }

    @Test
    @DisplayName("five receivables are ASSET (debit-normal), the credit balance alone a LIABILITY (credit-normal)")
    void theDeclaredTypesAreTheAccountPlan() {
        for (AccountPurpose purpose : BnplAccounts.purposes()) {
            AccountType expected =
                    purpose == AccountPurpose.BNPL_CREDIT_BALANCE ? AccountType.LIABILITY : AccountType.ASSET;
            assertThat(BnplAccounts.declaredTypeOf(purpose)).as("%s", purpose).isEqualTo(expected);
            assertThat(BnplAccounts.declaredTypeOf(purpose).normalBalance())
                    .as("%s", purpose)
                    .isEqualTo(expected == AccountType.ASSET ? NormalBalance.DEBIT : NormalBalance.CREDIT);
        }
    }

    @Test
    @DisplayName("a purpose that is not an obligation account - a loan's included - has no declared BNPL type")
    void aNonObligationPurposeIsRefused() {
        for (AccountPurpose purpose : AccountPurpose.values()) {
            if (purpose.ownerKind() == OwnerKind.BNPL_OBLIGATION) {
                continue;
            }
            assertThatThrownBy(() -> BnplAccounts.declaredTypeOf(purpose))
                    .as("%s", purpose)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(purpose.name());
        }
    }

    @Test
    @DisplayName("an unsupported currency is refused before the ledger is asked")
    void anUnsupportedCurrencyIsRefused() {
        @SuppressWarnings("unchecked")
        LedgerAccountStore<Connection> untouchable = (LedgerAccountStore<Connection>) Proxy.newProxyInstance(
                LedgerAccountStore.class.getClassLoader(),
                new Class<?>[] {LedgerAccountStore.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("the store was asked: " + method.getName());
                });
        BnplAccounts opener = new BnplAccounts(untouchable, IDS, Clock.systemUTC());
        assertThat(SupportedCurrencies.ALL).doesNotContain(CurrencyCode.of("CHF"));
        assertThatThrownBy(() -> opener.openFor(UNIT_OF_WORK, BnplObligationId.next(IDS), CurrencyCode.of("CHF")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CHF");
    }

    @Test
    @DisplayName("the six are opened in order, typed and owned as declared, and a second opening converges on"
            + " the same six")
    void theSixAreOpenedAndConverged() {
        Ledger ledger = new Ledger();
        BnplAccounts opener = new BnplAccounts(ledger.store(), IDS, Clock.systemUTC());
        BnplObligationId obligation = BnplObligationId.next(IDS);

        BnplLedgerAccounts opened = opener.openFor(UNIT_OF_WORK, obligation, EUR);
        assertThat(ledger.created).as("six inserted, in the fixed order").containsExactlyElementsOf(BnplAccounts.purposes());
        assertThat(opened.all()).extracting(LedgerAccount::purpose).containsExactlyElementsOf(BnplAccounts.purposes());
        for (LedgerAccount account : opened.all()) {
            assertThat(account.accountType()).as("%s", account.purpose()).isEqualTo(BnplAccounts.declaredTypeOf(account.purpose()));
            assertThat(account.ownerKind()).isEqualTo(OwnerKind.BNPL_OBLIGATION);
            assertThat(account.ownerRef()).contains(obligation.value());
            assertThat(account.currency()).isEqualTo(EUR);
        }
        assertThat(opened.principal().purpose()).isEqualTo(AccountPurpose.BNPL_PRINCIPAL);
        assertThat(opened.creditBalance().normalBalance()).isEqualTo(NormalBalance.CREDIT);

        BnplLedgerAccounts again = opener.openFor(UNIT_OF_WORK, obligation, EUR);
        assertThat(again).as("a retry of the same intent converges on the one set").isEqualTo(opened);
        assertThat(ledger.created).as("nothing inserted the second time").hasSize(6);
        assertThat(ledger.accounts).hasSize(6);
    }

    @Test
    @DisplayName("an existing account of the wrong type is refused before any write, and never adopted"
            + " (INV-LED-06)")
    void aConvergedAccountOfTheWrongTypeIsRefusedBeforeAnyWrite() {
        Ledger ledger = new Ledger();
        BnplObligationId obligation = BnplObligationId.next(IDS);
        // A raw writer's credit balance opened as an ASSET - the shape a defect would leave.
        ledger.plant(LedgerAccount.owned(
                IDS, Clock.systemUTC(), AccountType.ASSET, AccountPurpose.BNPL_CREDIT_BALANCE, EUR, obligation.value()));
        BnplAccounts opener = new BnplAccounts(ledger.store(), IDS, Clock.systemUTC());
        assertThatThrownBy(() -> opener.openFor(UNIT_OF_WORK, obligation, EUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BNPL_CREDIT_BALANCE")
                .hasMessageContaining("INV-LED-06")
                // No obligation id in an exception message (the Phase 11 gate's Security criterion).
                .hasMessageNotContaining(obligation.value().toString());
        assertThat(ledger.created).as("refused before the first insert").isEmpty();
    }

    @Test
    @DisplayName("an existing account in another currency, or of another purpose under the same owner, is refused"
            + " before any write")
    void anAccountOfAnotherCurrencyOrOwnerIsRefusedBeforeAnyWrite() {
        BnplObligationId obligation = BnplObligationId.next(IDS);
        Ledger otherCurrency = new Ledger();
        otherCurrency.plant(LedgerAccount.owned(
                IDS, Clock.systemUTC(), AccountType.ASSET, AccountPurpose.BNPL_PRINCIPAL, CurrencyCode.of("GBP"),
                obligation.value()));
        assertThatThrownBy(() -> new BnplAccounts(otherCurrency.store(), IDS, Clock.systemUTC())
                        .openFor(UNIT_OF_WORK, obligation, EUR))
                .as("an obligation never holds an account in another currency")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GBP")
                .hasMessageNotContaining(obligation.value().toString());
        assertThat(otherCurrency.created).isEmpty();

        Ledger anotherOwner = new Ledger();
        anotherOwner.plant(LedgerAccount.owned(
                IDS, Clock.systemUTC(), AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR, obligation.value()));
        assertThatThrownBy(() -> new BnplAccounts(anotherOwner.store(), IDS, Clock.systemUTC())
                        .openFor(UNIT_OF_WORK, obligation, EUR))
                .as("the obligation's id already names another owner's account")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CUSTOMER_WALLET")
                .hasMessageNotContaining(obligation.value().toString());
        assertThat(anotherOwner.created).isEmpty();
    }

    @Test
    @DisplayName("an account a concurrent writer lands between the read and the insert is judged on convergence"
            + " too, and refused")
    void aRaceConvergedAccountOfTheWrongTypeIsRefused() {
        Ledger ledger = new Ledger();
        BnplObligationId obligation = BnplObligationId.next(IDS);
        // createOrConverge hands back a wrong-shaped principal-due, as if another writer had won the index entry.
        ledger.onCreate = fresh -> fresh.purpose() == AccountPurpose.BNPL_PRINCIPAL_DUE
                ? LedgerAccount.owned(IDS, Clock.systemUTC(), AccountType.LIABILITY, fresh.purpose(), EUR,
                        obligation.value())
                : fresh;
        assertThatThrownBy(() -> new BnplAccounts(ledger.store(), IDS, Clock.systemUTC())
                        .openFor(UNIT_OF_WORK, obligation, EUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BNPL_PRINCIPAL_DUE")
                .hasMessageContaining("LIABILITY/CREDIT")
                .hasMessageNotContaining(obligation.value().toString());
    }

    @Test
    @DisplayName("the typed set holds each component to its purpose")
    void theTypedSetHoldsEachComponentToItsPurpose() {
        UUID owner = IDS.next();
        List<LedgerAccount> six = BnplAccounts.purposes().stream()
                .map(purpose -> LedgerAccount.owned(
                        IDS, Clock.systemUTC(), BnplAccounts.declaredTypeOf(purpose), purpose, EUR, owner))
                .toList();
        assertThat(new BnplLedgerAccounts(six.get(0), six.get(1), six.get(2), six.get(3), six.get(4), six.get(5)).all())
                .containsExactlyElementsOf(six);
        assertThatThrownBy(() -> new BnplLedgerAccounts(six.get(1), six.get(0), six.get(2), six.get(3), six.get(4), six.get(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BNPL_PRINCIPAL");
    }

    // ------------------------------------------------------------------------------------------

    /** An in-memory ledger converging on (owner_ref, purpose, currency), the partial unique index's identity. */
    private static final class Ledger {

        private final Map<String, LedgerAccount> accounts = new LinkedHashMap<>();
        private final List<AccountPurpose> created = new ArrayList<>();
        private UnaryOperator<LedgerAccount> onCreate = UnaryOperator.identity();

        void plant(LedgerAccount account) {
            accounts.put(key(account), account);
        }

        @SuppressWarnings("unchecked")
        LedgerAccountStore<Connection> store() {
            return (LedgerAccountStore<Connection>) Proxy.newProxyInstance(
                    LedgerAccountStore.class.getClassLoader(),
                    new Class<?>[] {LedgerAccountStore.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "findAllOwned" -> accounts.values().stream()
                                .filter(account -> account.ownerRef().filter(arguments[1]::equals).isPresent())
                                .toList();
                        case "createOrConverge" -> {
                            LedgerAccount fresh = (LedgerAccount) arguments[1];
                            LedgerAccount existing = accounts.get(key(fresh));
                            if (existing != null) {
                                yield new LedgerAccountStore.Creation(existing, false);
                            }
                            LedgerAccount stored = onCreate.apply(fresh);
                            accounts.put(key(stored), stored);
                            created.add(stored.purpose());
                            yield new LedgerAccountStore.Creation(stored, true);
                        }
                        default -> throw new AssertionError("the opener asked the store: " + method.getName());
                    });
        }

        private static String key(LedgerAccount account) {
            return account.ownerRef().orElseThrow() + "|" + account.purpose() + "|" + account.currency().code();
        }
    }
}
