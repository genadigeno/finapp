package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.OwnerKind;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The opener's declaration (`P11-TSK-003`; ADR-0096 section 1, {@code INV-LED-06}): which six
 * accounts a loan has and what type each is. The live half - opened, converged, typed in the
 * database - is {@code LoanChartDatabaseTest}'s.
 */
@DisplayName("a loan's six accounts are declared once, typed as the account plan (P11-TSK-003)")
class LoanAccountsTest {

    @Test
    @DisplayName("the opener opens exactly the LOAN-owned purposes, each once, in a fixed order")
    void theOpenerCoversTheLoanOwnedPurposes() {
        assertThat(LoanAccounts.purposes())
                .as("every LOAN-owned purpose the ledger declares, and nothing else")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(AccountPurpose.values())
                        .filter(purpose -> purpose.ownerKind() == OwnerKind.LOAN)
                        .toList())
                .doesNotHaveDuplicates()
                .hasSize(6);
        assertThat(LoanAccounts.purposes())
                .as("the fixed opening order two concurrent openers share - never a cycle")
                .containsExactly(
                        AccountPurpose.LOAN_PRINCIPAL,
                        AccountPurpose.LOAN_PRINCIPAL_DUE,
                        AccountPurpose.LOAN_INTEREST_ACCRUED,
                        AccountPurpose.LOAN_INTEREST_DUE,
                        AccountPurpose.LOAN_FEES_DUE,
                        AccountPurpose.LOAN_CREDIT_BALANCE);
    }

    @Test
    @DisplayName("five receivables are ASSET, the credit balance alone a LIABILITY")
    void theDeclaredTypesAreTheAccountPlan() {
        for (AccountPurpose purpose : LoanAccounts.purposes()) {
            AccountType expected = purpose == AccountPurpose.LOAN_CREDIT_BALANCE ? AccountType.LIABILITY : AccountType.ASSET;
            assertThat(LoanAccounts.declaredTypeOf(purpose)).as("%s", purpose).isEqualTo(expected);
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
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        LoanAccounts opener = new LoanAccounts(untouchable, ids, Clock.systemUTC());
        Connection unitOfWork = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, arguments) -> null);
        assertThatThrownBy(() -> opener.openFor(unitOfWork, LoanId.next(ids), CurrencyCode.of("CHF")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CHF");
    }

    @Test
    @DisplayName("a purpose that is not a loan account has no declared loan type")
    void aNonLoanPurposeIsRefused() {
        for (AccountPurpose purpose : AccountPurpose.values()) {
            if (purpose.ownerKind() == OwnerKind.LOAN) {
                continue;
            }
            assertThatThrownBy(() -> LoanAccounts.declaredTypeOf(purpose))
                    .as("%s", purpose)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(purpose.name());
        }
    }
}
