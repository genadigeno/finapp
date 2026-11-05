package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.reporting.SafeguardingPositionDefinition;
import com.finapp.accounting.reporting.SafeguardingPositionDefinition.Disposition;
import com.finapp.accounting.reporting.SafeguardingPositionDefinition.Treatment;
import com.finapp.ledger.AccountPurpose;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which accounts, by what mapping (`P14-TSK-019`; ADR-0118 decision 4): every purpose the ledger holds is in the return or out of it, with why, once, under its own owner kind. {@code accounting} has no ledger edge, so it names them by string; this holds the strings to the enum in {@code app}, the one module that sees both. A purpose a later phase adds fails here until someone disposes of it, which is a new version of the definition.
 */
@DisplayName("the safeguarding return's mapping is total over the ledger's purposes (P14-TSK-019)")
class SafeguardingPurposeCensusTest {

    private static final Map<String, Disposition> HELD = SafeguardingPositionDefinition.DISPOSITIONS.stream()
            .collect(Collectors.toMap(Disposition::key, Function.identity()));

    private static String key(AccountPurpose purpose) {
        return purpose.name() + "/" + purpose.ownerKind().name();
    }

    @Test
    @DisplayName("every ledger purpose once, under its own owner kind, and nothing the ledger does not hold")
    void everyPurposeOnce() {
        assertThat(SafeguardingPositionDefinition.DISPOSITIONS).as("a key held twice").hasSameSizeAs(HELD.keySet());
        assertThat(HELD.keySet()).containsExactlyInAnyOrderElementsOf(Arrays.stream(AccountPurpose.values()).map(SafeguardingPurposeCensusTest::key).toList());
    }

    @Test
    @DisplayName("each with its reason, stated")
    void eachWithItsReason() {
        assertThat(SafeguardingPositionDefinition.DISPOSITIONS).allSatisfy(held -> assertThat(held.reason()).as(held.key()).hasSizeGreaterThan(20));
    }

    @Test
    @DisplayName("the entry's sentence: customer and merchant liabilities against cash and every clearing receivable are in; equity, income, expense and the book's income receivables out")
    void theEntrysSentence() {
        assertThat(treatment(AccountPurpose.CASH_AT_BANK)).isEqualTo(Treatment.CASH);
        for (AccountPurpose receivable : List.of(AccountPurpose.SETTLEMENT_CLEARING, AccountPurpose.INSTANT_CLEARING)) {
            assertThat(treatment(receivable)).as(receivable.name()).isEqualTo(Treatment.CLEARING_RECEIVABLE);
        }
        assertThat(treatment(AccountPurpose.FX_PROVIDER_CLEARING)).isEqualTo(Treatment.COUNTERPARTY_CLEARING);
        assertThat(treatment(AccountPurpose.CORRIDOR_CLEARING)).isEqualTo(Treatment.COUNTERPARTY_CLEARING);
        assertThat(treatment(AccountPurpose.CUSTOMER_WALLET)).isEqualTo(Treatment.CUSTOMER_WALLET);
        assertThat(treatment(AccountPurpose.MERCHANT_PAYABLE)).isEqualTo(Treatment.MERCHANT_PAYABLE);
        assertThat(treatment(AccountPurpose.LOAN_CREDIT_BALANCE)).isEqualTo(Treatment.CREDIT_BALANCE);
        assertThat(treatment(AccountPurpose.BNPL_CREDIT_BALANCE)).isEqualTo(Treatment.CREDIT_BALANCE);
        assertThat(treatment(AccountPurpose.PAYOUT_CLEARING)).isEqualTo(Treatment.CLEARING_PAYABLE);
        assertThat(treatment(AccountPurpose.SUSPENSE_UNMATCHED)).isEqualTo(Treatment.SUSPENSE);
        assertThat(treatment(AccountPurpose.LENDING_CAPITAL)).isEqualTo(Treatment.OUT);
        assertThat(treatment(AccountPurpose.CHARGEBACK_RECOVERABLE)).isEqualTo(Treatment.MEMO);
        assertThat(treatment(AccountPurpose.FX_POSITION)).isEqualTo(Treatment.MEMO);
    }

    @Test
    @DisplayName("every income and expense purpose is out but the two a write-off's entries are found by")
    void noIncomeOrExpenseIsInThePosition() {
        for (AccountPurpose purpose : AccountPurpose.values()) {
            String type = purpose.name();
            if (type.endsWith("_INCOME") || type.endsWith("_REVENUE") || type.endsWith("_COSTS") || type.endsWith("_GAINS") || type.endsWith("_LOSSES") || type.equals("ROUNDING_RESIDUAL")) {
                assertThat(treatment(purpose)).as(type).isEqualTo(Treatment.OUT);
            }
        }
        assertThat(treatment(AccountPurpose.LOAN_WRITE_OFF_EXPENSE)).isEqualTo(Treatment.WRITE_OFF_ROUTE);
        assertThat(treatment(AccountPurpose.BNPL_WRITE_OFF_EXPENSE)).isEqualTo(Treatment.WRITE_OFF_ROUTE);
    }

    private static Treatment treatment(AccountPurpose purpose) {
        return HELD.get(key(purpose)).treatment();
    }
}
