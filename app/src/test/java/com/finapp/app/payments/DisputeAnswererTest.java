package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whose answer a dispute is, as the operator's view says it (`P17-TSK-114`, finapp-admin-panel B7.5): the view's
 * {@code answeredByMerchant} is the complement of the very reach the operator's acts are judged against (ADR-0061 section 7),
 * so the console hides exactly the acts {@code finapp} would refuse {@code payments.DisputeAnsweredByItsMerchant}. The flag over
 * HTTP, on a wallet top-up's dispute and a merchant's, is {@code DisputeResponseDatabaseTest}'s.
 */
@DisplayName("whose answer a dispute is (P17-TSK-114)")
class DisputeAnswererTest {

    @Test
    @DisplayName("a customer wallet's payment is the operator's to answer; a merchant's payable's is its merchant's")
    void theTwoCreditedPurposes() {
        assertThat(DisputeOperations.answeredByMerchant(AccountPurpose.CUSTOMER_WALLET)).isFalse();
        assertThat(DisputeOperations.answeredByMerchant(AccountPurpose.MERCHANT_PAYABLE)).isTrue();
    }

    @Test
    @DisplayName("the flag is the operator's reach, complemented - for every purpose, so the two can never disagree")
    void theFlagIsTheReachComplemented() {
        Arrays.stream(AccountPurpose.values()).forEach(purpose ->
                assertThat(DisputeOperations.answeredByMerchant(purpose))
                        .as(purpose.name())
                        .isEqualTo(!DisputeOperations.OPERATOR_REACH.contains(purpose)));
    }
}
