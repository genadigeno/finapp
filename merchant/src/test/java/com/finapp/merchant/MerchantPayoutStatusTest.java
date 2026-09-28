package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payout machine, pinned whole (`P6-TSK-012`): four states — {@code REQUESTED} refused by
 * ADR-0044 — and exactly the edges ADR-0051 §5 as refined by ADR-0057 §1 names.
 */
@DisplayName("the merchant payout machine (P6-TSK-012)")
class MerchantPayoutStatusTest {

    @Test
    @DisplayName("four states, and no REQUESTED: the dispatch commits DISPATCHED atomically")
    void fourStates() {
        assertThat(MerchantPayoutStatus.values())
                .containsExactly(
                        MerchantPayoutStatus.DISPATCHED,
                        MerchantPayoutStatus.COMPLETED,
                        MerchantPayoutStatus.FAILED,
                        MerchantPayoutStatus.UNKNOWN);
    }

    @Test
    @DisplayName("exactly the machine's edges: DISPATCHED to any outcome, UNKNOWN to a definite one")
    void exactlyTheEdges() {
        assertThat(MerchantPayoutStatus.DISPATCHED.permittedTransitions())
                .containsExactlyInAnyOrder(
                        MerchantPayoutStatus.COMPLETED,
                        MerchantPayoutStatus.FAILED,
                        MerchantPayoutStatus.UNKNOWN);
        assertThat(MerchantPayoutStatus.UNKNOWN.permittedTransitions())
                .containsExactlyInAnyOrder(
                        MerchantPayoutStatus.COMPLETED, MerchantPayoutStatus.FAILED);
        assertThat(MerchantPayoutStatus.COMPLETED.permittedTransitions()).isEmpty();
        assertThat(MerchantPayoutStatus.FAILED.permittedTransitions()).isEmpty();
    }

    @Test
    @DisplayName("resolvable is exactly non-terminal, and nothing leaves a terminal state")
    void resolvableIsNonTerminal() {
        for (MerchantPayoutStatus status : MerchantPayoutStatus.values()) {
            assertThat(status.isResolvable()).isEqualTo(!status.isTerminal());
            for (MerchantPayoutStatus target : MerchantPayoutStatus.values()) {
                if (status.isTerminal()) {
                    assertThat(status.canTransitionTo(target))
                            .as("%s -> %s", status, target)
                            .isFalse();
                }
            }
        }
        assertThat(EnumSet.allOf(MerchantPayoutStatus.class).stream()
                        .filter(MerchantPayoutStatus::isResolvable))
                .containsExactly(MerchantPayoutStatus.DISPATCHED, MerchantPayoutStatus.UNKNOWN);
    }

    @Test
    @DisplayName("the SQL lists are the enums' own, in declaration order")
    void theSqlLists() {
        assertThat(MerchantPayoutStatus.sqlValueList())
                .isEqualTo("'DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN'");
        assertThat(MerchantPayoutStatus.resolvableSqlValueList())
                .isEqualTo("'DISPATCHED', 'UNKNOWN'");
        assertThat(PayoutFailureReason.sqlValueList())
                .isEqualTo("'DECLINED', 'PROVIDER_UNAVAILABLE', 'NEVER_RECEIVED'");
    }
}
