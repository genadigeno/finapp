package com.finapp.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Customer Account machine, held at the aggregate (`P3-TSK-012`, {@code INV-LIFE-02}).
 *
 * <p>The transition sweep is <strong>derived from the machine</strong> — every ordered pair of
 * states, with the expectation read from {@code permittedTransitions()} — so a state or edge
 * added later is swept without anyone remembering, and the test cannot quietly cover only the
 * transitions somebody wrote down (the {@code CustomerLifecycleTest} idiom).
 */
@DisplayName("CustomerAccount (P3-TSK-012)")
class CustomerAccountTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("opening creates an ACTIVE agreement - the gate is the caller's precondition")
    void openingCreatesActive() {
        UUID customer = IDS.next();
        CustomerAccount account =
                CustomerAccount.open(IDS, CLOCK, customer, ProductType.WALLET);

        assertThat(account.status()).isEqualTo(CustomerAccountStatus.ACTIVE);
        assertThat(account.customerId()).isEqualTo(customer);
        assertThat(account.productType()).isEqualTo(ProductType.WALLET);
        assertThat(account.statusChangedAt()).isEqualTo(account.openedAt());
    }

    @Test
    @DisplayName("every transition in the cross-product behaves as the machine declares")
    void everyTransitionIsEnforced() {
        for (CustomerAccountStatus from : CustomerAccountStatus.values()) {
            for (CustomerAccountStatus to : CustomerAccountStatus.values()) {
                CustomerAccount account = accountIn(from);
                if (from.canTransitionTo(to)) {
                    assertThat(account.moveTo(to, CLOCK).status())
                            .as("%s -> %s is permitted by the machine", from, to)
                            .isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> account.moveTo(to, CLOCK))
                            .as("%s -> %s must be refused by the aggregate itself", from, to)
                            .isInstanceOf(IllegalCustomerAccountTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("CLOSED is the one terminal state, and it is terminal (INV-LIFE-04)")
    void closedIsTheOneTerminalState() {
        // The one-live-account index predicate is generated from exactly this property, so it
        // is asserted as a property of the machine rather than left to the migration test's
        // string comparison alone: a second terminal state, or CLOSED regaining an exit, is a
        // decision about how many agreements a customer can hold - never a silent edit.
        for (CustomerAccountStatus status : CustomerAccountStatus.values()) {
            assertThat(status.isTerminal())
                    .as("%s terminal?", status)
                    .isEqualTo(status == CustomerAccountStatus.CLOSED);
        }
    }

    /** An agreement rehydrated in {@code status}, as a row read back would be. */
    private static CustomerAccount accountIn(CustomerAccountStatus status) {
        Instant openedAt = Instant.now(CLOCK);
        return CustomerAccount.rehydrate(
                CustomerAccountId.next(IDS),
                IDS.next(),
                ProductType.WALLET,
                status,
                openedAt,
                openedAt);
    }
}
