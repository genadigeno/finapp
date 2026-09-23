package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payout destination machine as data (`P6-TSK-011`): the generators `V006` is reconciled
 * against, and the machine properties the cooling-off depends on.
 */
@DisplayName("the payout destination machine (P6-TSK-011)")
class PayoutDestinationStatusTest {

    @Test
    @DisplayName("the SQL lists are exactly the declared states, and the open pair")
    void theSqlListsMatchTheDeclaration() {
        assertThat(PayoutDestinationStatus.sqlValueList())
                .isEqualTo(
                        "'PROPOSED', 'APPROVED', 'EFFECTIVE', 'SUPERSEDED', 'REJECTED',"
                                + " 'WITHDRAWN'");
        assertThat(PayoutDestinationStatus.openSqlValueList()).isEqualTo("'PROPOSED', 'APPROVED'");
    }

    @Test
    @DisplayName("an approved change can still be withdrawn: the cooling-off is a control")
    void theCoolingOffCanBeActedOn() {
        assertThat(PayoutDestinationStatus.APPROVED.canTransitionTo(PayoutDestinationStatus.WITHDRAWN))
                .isTrue();
        assertThat(PayoutDestinationStatus.EFFECTIVE.canTransitionTo(PayoutDestinationStatus.WITHDRAWN))
                .as("an effective destination is replaced by a new change, never withdrawn")
                .isFalse();
    }

    @Test
    @DisplayName("the terminal states are exactly the three ends, and open means not yet decided")
    void terminalAndOpenStates() {
        assertThat(Arrays.stream(PayoutDestinationStatus.values()).filter(PayoutDestinationStatus::isTerminal))
                .containsExactlyInAnyOrder(
                        PayoutDestinationStatus.SUPERSEDED,
                        PayoutDestinationStatus.REJECTED,
                        PayoutDestinationStatus.WITHDRAWN);
        assertThat(Arrays.stream(PayoutDestinationStatus.values()).filter(PayoutDestinationStatus::isOpen))
                .containsExactly(PayoutDestinationStatus.PROPOSED, PayoutDestinationStatus.APPROVED);
    }

    @Test
    @DisplayName("nothing re-enters PROPOSED, and only the platform's effect reaches EFFECTIVE")
    void theEdgesIntoTheSensitiveStates() {
        for (PayoutDestinationStatus from : PayoutDestinationStatus.values()) {
            assertThat(from.canTransitionTo(PayoutDestinationStatus.PROPOSED)).isFalse();
            assertThat(from.canTransitionTo(PayoutDestinationStatus.EFFECTIVE))
                    .as("only APPROVED becomes EFFECTIVE (%s)", from)
                    .isEqualTo(from == PayoutDestinationStatus.APPROVED);
        }
    }
}
