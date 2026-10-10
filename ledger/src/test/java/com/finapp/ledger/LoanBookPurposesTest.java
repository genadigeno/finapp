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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The loan book and the lending capital in the chart's own definitions (`P11-TSK-003`; ADR-0096
 * section 1, PHASE_11_PLAN.md sections 12.8 and 12.9): who owns each purpose, which sets guard it,
 * and that none of them is a rail's clearing.
 *
 * <p>The schema half - the generated {@code CHECK}s and the re-stated binding trigger holding these
 * same definitions - is {@code LedgerAccountMigrationTest}'s and
 * {@code AdjustmentProposalMigrationTest}'s; the live half is lending's {@code LoanChartDatabaseTest}.
 */
@DisplayName("the loan book and the lending capital are owned, guarded and rail-free (P11-TSK-003)")
class LoanBookPurposesTest {

    private static final Set<AccountPurpose> PER_LOAN =
            EnumSet.of(
                    AccountPurpose.LOAN_PRINCIPAL,
                    AccountPurpose.LOAN_PRINCIPAL_DUE,
                    AccountPurpose.LOAN_INTEREST_ACCRUED,
                    AccountPurpose.LOAN_INTEREST_DUE,
                    AccountPurpose.LOAN_FEES_DUE,
                    AccountPurpose.LOAN_CREDIT_BALANCE);

    private static final Set<AccountPurpose> LOAN_PNL =
            EnumSet.of(
                    AccountPurpose.LOAN_INTEREST_INCOME,
                    AccountPurpose.LOAN_FEE_INCOME,
                    AccountPurpose.LOAN_WRITE_OFF_EXPENSE);

    private static final IdGenerator IDS =
            new IdGenerator(Clock.system(ZoneOffset.UTC), new SecureRandom());

    @Test
    @DisplayName("the six per-loan purposes are exactly the LOAN-owned ones; the other four are the platform's")
    void theOwnerKindsAreTheAccountPlan() {
        assertThat(Arrays.stream(AccountPurpose.values())
                        .filter(purpose -> purpose.ownerKind() == OwnerKind.LOAN))
                .as("OwnerKind.LOAN owns exactly the six per-loan accounts, for both products")
                .containsExactlyInAnyOrderElementsOf(PER_LOAN);
        assertThat(OwnerKind.LOAN.requiresOwnerRef())
                .as("a loan account names its loan (owner_ref = the loan id)")
                .isTrue();
        LOAN_PNL.forEach(purpose -> assertThat(purpose.ownerKind()).as("%s", purpose).isEqualTo(OwnerKind.OPERATIONAL));
        assertThat(AccountPurpose.LENDING_CAPITAL.ownerKind()).isEqualTo(OwnerKind.OPERATIONAL);
    }

    @Test
    @DisplayName("all ten are closed to free adjustments; LENDING_CAPITAL alone is a reconciled position")
    void allTenAreGuarded() {
        Set<AccountPurpose> ten = EnumSet.copyOf(PER_LOAN);
        ten.addAll(LOAN_PNL);
        ten.add(AccountPurpose.LENDING_CAPITAL);
        assertThat(AccountPurpose.closedToFreeAdjustments())
                .as("a MANUAL adjustment line on any loan or capital account is refused (INV-LND-01, INV-LND-13)")
                .containsAll(ten);
        assertThat(AccountPurpose.reconciledPositions())
                .as("LENDING_CAPITAL joins the single-poster discipline: completeness reports any line"
                        + " on it no recognition explains (INV-LND-13)")
                .contains(AccountPurpose.LENDING_CAPITAL);
        assertThat(AccountPurpose.reconciledPositions())
                .as("the loan book opens no expectations - completeness would report every lending line"
                        + " unattributed; the subledger proof proves it instead")
                .doesNotContainAnyElementsOf(PER_LOAN)
                .doesNotContainAnyElementsOf(LOAN_PNL);
    }

    @Test
    @DisplayName("no *_CLEARING purpose arrives with lending: it moves no money across a rail itself (INV-RAIL-04)")
    void lendingAddsNoClearing() {
        assertThat(Arrays.stream(AccountPurpose.values()).filter(purpose -> purpose.name().endsWith("_CLEARING")))
                .as("the clearings are exactly the rails' and counterparties' - lending added none")
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
                        IDS, Clock.systemUTC(), AccountType.EQUITY, AccountPurpose.LENDING_CAPITAL, eur, UUID.randomUUID()))
                .as("the platform's capital never has an owner: it is seeded, never opened")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPERATIONAL");
        LedgerAccount opened = LedgerAccount.owned(
                IDS, Clock.systemUTC(), AccountType.LIABILITY, AccountPurpose.LOAN_CREDIT_BALANCE, eur, UUID.randomUUID());
        assertThat(opened.ownerKind()).isEqualTo(OwnerKind.LOAN);
        assertThat(opened.normalBalance()).isEqualTo(NormalBalance.CREDIT);
    }
}
