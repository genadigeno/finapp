package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The BNPL book in the chart's own definitions (`P12-TSK-003`; ADR-0103 decision 1, PHASE_12_PLAN.md
 * section 12.11; {@code INV-BNPL-01}, {@code INV-LED-06}): who owns each purpose, which type and normal
 * balance the account plan gives it, which sets guard it, that none of them is a reconciled position or a
 * rail's clearing, and that the book stands apart from the loan book (OQ-11).
 *
 * <p>The per-obligation types are declared by their one opener - bnpl's {@code BnplAccounts}, held to the
 * plan by {@code BnplAccountsTest}; the operational types are the seed's, pinned by
 * {@link OperationalChartMigrationTest#SEEDED_TYPES} and held here to the same plan. The schema half - the
 * generated {@code CHECK}s and the re-stated binding trigger - is {@code LedgerAccountMigrationTest}'s and
 * {@code AdjustmentProposalMigrationTest}'s; the live half is bnpl's {@code BnplChartDatabaseTest}.
 */
@DisplayName("the BNPL book is owned, typed, guarded, unreconciled and rail-free (P12-TSK-003)")
class BnplBookPurposesTest {

    /** ADR-0103 decision 1's account plan: the six per-obligation purposes and their types. */
    private static final Map<AccountPurpose, AccountType> PER_OBLIGATION =
            Map.of(
                    AccountPurpose.BNPL_PRINCIPAL, AccountType.ASSET,
                    AccountPurpose.BNPL_PRINCIPAL_DUE, AccountType.ASSET,
                    AccountPurpose.BNPL_INTEREST_ACCRUED, AccountType.ASSET,
                    AccountPurpose.BNPL_INTEREST_DUE, AccountType.ASSET,
                    AccountPurpose.BNPL_FEES_DUE, AccountType.ASSET,
                    AccountPurpose.BNPL_CREDIT_BALANCE, AccountType.LIABILITY);

    /** The four operational purposes and their types, seeded per supported currency by `V027`. */
    private static final Map<AccountPurpose, AccountType> OPERATIONAL =
            Map.of(
                    AccountPurpose.BNPL_MERCHANT_FEE_INCOME, AccountType.REVENUE,
                    AccountPurpose.BNPL_INTEREST_INCOME, AccountType.REVENUE,
                    AccountPurpose.BNPL_FEE_INCOME, AccountType.REVENUE,
                    AccountPurpose.BNPL_WRITE_OFF_EXPENSE, AccountType.EXPENSE);

    private static final IdGenerator IDS = new IdGenerator(Clock.system(ZoneOffset.UTC), new SecureRandom());

    private static Set<AccountPurpose> theTen() {
        Set<AccountPurpose> ten = EnumSet.copyOf(PER_OBLIGATION.keySet());
        ten.addAll(OPERATIONAL.keySet());
        return ten;
    }

    @Test
    @DisplayName("the BNPL book is exactly the ten purposes of the account plan")
    void theBookIsTheAccountPlan() {
        assertThat(Arrays.stream(AccountPurpose.values()).filter(purpose -> purpose.name().startsWith("BNPL_")))
                .as("every BNPL_* purpose is in ADR-0103's plan, and every planned purpose exists")
                .containsExactlyInAnyOrderElementsOf(theTen());
    }

    @Test
    @DisplayName("the six per-obligation purposes are exactly the BNPL_OBLIGATION-owned ones; the other four"
            + " are the platform's")
    void theOwnerKindsAreTheAccountPlan() {
        assertThat(Arrays.stream(AccountPurpose.values())
                        .filter(purpose -> purpose.ownerKind() == OwnerKind.BNPL_OBLIGATION))
                .as("OwnerKind.BNPL_OBLIGATION owns exactly the six per-obligation accounts")
                .containsExactlyInAnyOrderElementsOf(PER_OBLIGATION.keySet());
        assertThat(OwnerKind.BNPL_OBLIGATION.requiresOwnerRef())
                .as("an obligation account names its obligation (owner_ref = the obligation id)")
                .isTrue();
        OPERATIONAL.keySet().forEach(purpose ->
                assertThat(purpose.ownerKind()).as("%s", purpose).isEqualTo(OwnerKind.OPERATIONAL));
    }

    @Test
    @DisplayName("the book stands apart from the loan book: no BNPL purpose is LOAN-owned and the loan book is"
            + " unchanged (OQ-11)")
    void theBookIsNotTheLoanBook() {
        PER_OBLIGATION.keySet().forEach(purpose ->
                assertThat(purpose.ownerKind()).as("%s", purpose).isNotEqualTo(OwnerKind.LOAN));
        assertThat(Arrays.stream(AccountPurpose.values()).filter(purpose -> purpose.ownerKind() == OwnerKind.LOAN))
                .as("lending's six per-loan purposes, still exactly six and none of them BNPL's")
                .hasSize(6)
                .allMatch(purpose -> purpose.name().startsWith("LOAN_"));
    }

    @Test
    @DisplayName("each operational purpose is seeded as the plan types it, and every type's normal balance is"
            + " the plan's: five receivables and the write-off debit-normal, the credit balance and the incomes"
            + " credit-normal")
    void theTypesAndNormalBalancesAreTheAccountPlan() {
        OPERATIONAL.forEach((purpose, type) -> assertThat(OperationalChartMigrationTest.SEEDED_TYPES.get(purpose))
                .as("the seed's pinned type of %s is the plan's", purpose)
                .isEqualTo(type));
        Map<AccountPurpose, NormalBalance> normal = new java.util.EnumMap<>(AccountPurpose.class);
        PER_OBLIGATION.forEach((purpose, type) -> normal.put(purpose, type.normalBalance()));
        OPERATIONAL.forEach((purpose, type) -> normal.put(purpose, type.normalBalance()));
        assertThat(normal)
                .as("ADR-0103 decision 1's normal sides")
                .containsEntry(AccountPurpose.BNPL_PRINCIPAL, NormalBalance.DEBIT)
                .containsEntry(AccountPurpose.BNPL_PRINCIPAL_DUE, NormalBalance.DEBIT)
                .containsEntry(AccountPurpose.BNPL_INTEREST_ACCRUED, NormalBalance.DEBIT)
                .containsEntry(AccountPurpose.BNPL_INTEREST_DUE, NormalBalance.DEBIT)
                .containsEntry(AccountPurpose.BNPL_FEES_DUE, NormalBalance.DEBIT)
                .containsEntry(AccountPurpose.BNPL_CREDIT_BALANCE, NormalBalance.CREDIT)
                .containsEntry(AccountPurpose.BNPL_MERCHANT_FEE_INCOME, NormalBalance.CREDIT)
                .containsEntry(AccountPurpose.BNPL_INTEREST_INCOME, NormalBalance.CREDIT)
                .containsEntry(AccountPurpose.BNPL_FEE_INCOME, NormalBalance.CREDIT)
                .containsEntry(AccountPurpose.BNPL_WRITE_OFF_EXPENSE, NormalBalance.DEBIT)
                .hasSize(10);
    }

    @Test
    @DisplayName("all ten are closed to free adjustments, and none is a reconciled position - so the"
            + " unattributed-lines gauge gains no BNPL series")
    void allTenAreGuardedAndNoneReconciled() {
        assertThat(AccountPurpose.closedToFreeAdjustments())
                .as("a MANUAL adjustment line on any BNPL account is refused (INV-BNPL-01): bnpl's posting rules"
                        + " are the only way its figures move")
                .containsAll(theTen());
        assertThat(AccountPurpose.reconciledPositions())
                .as("bnpl opens no expectations - completeness would report every BNPL line unattributed, and"
                        + " finapp.reconciliation.line.unattributed registers one series per reconciled position;"
                        + " bnpl's own proofs prove the book (P12-TSK-025)")
                .doesNotContainAnyElementsOf(theTen());
    }

    @Test
    @DisplayName("no *_CLEARING purpose arrives with BNPL: it moves no money across a rail (INV-RAIL-04)")
    void bnplAddsNoClearing() {
        assertThat(theTen()).noneMatch(purpose -> purpose.name().endsWith("_CLEARING"));
        assertThat(Arrays.stream(AccountPurpose.values()).filter(purpose -> purpose.name().endsWith("_CLEARING")))
                .as("the clearings are exactly the rails' and counterparties' - BNPL added none")
                .containsExactlyInAnyOrder(
                        AccountPurpose.SETTLEMENT_CLEARING,
                        AccountPurpose.PAYOUT_CLEARING,
                        AccountPurpose.INSTANT_CLEARING,
                        AccountPurpose.FX_PROVIDER_CLEARING,
                        AccountPurpose.CORRIDOR_CLEARING);
    }

    @Test
    @DisplayName("the domain refuses each mismatched pair before the schema is asked")
    void theDomainRefusesTheWrongOwner() {
        CurrencyCode eur = CurrencyCode.of("EUR");
        assertThatThrownBy(() -> LedgerAccount.owned(
                        IDS, Clock.systemUTC(), AccountType.REVENUE, AccountPurpose.BNPL_MERCHANT_FEE_INCOME, eur,
                        UUID.randomUUID()))
                .as("the merchant discount is the platform's income: it is seeded, never opened per owner")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPERATIONAL");
        LedgerAccount opened = LedgerAccount.owned(
                IDS, Clock.systemUTC(), AccountType.LIABILITY, AccountPurpose.BNPL_CREDIT_BALANCE, eur, UUID.randomUUID());
        assertThat(opened.ownerKind()).isEqualTo(OwnerKind.BNPL_OBLIGATION);
        assertThat(opened.normalBalance()).isEqualTo(NormalBalance.CREDIT);
    }
}
