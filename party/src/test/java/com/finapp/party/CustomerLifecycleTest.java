package com.finapp.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Customer state machine, rejected <strong>by the aggregate</strong> (`INV-LIFE-02`).
 *
 * <p>That distinction is the reason this test exists rather than an API-level one. Every aggregate
 * is eventually driven by a second caller — a background job, an operator tool, a migration script
 * — and a check at the HTTP boundary protects none of them. So every assertion here calls the
 * aggregate directly.
 *
 * <p><strong>Every transition is enumerated, not the ones somebody remembered.</strong> The sweep
 * below walks the full cross product of states and asserts each pair against
 * {@link CustomerStatus#permittedTransitions()}. A hand-written list of illegal moves is a list
 * that stops being complete the first time a state is added, and the addition is exactly when the
 * check is needed.
 */
@DisplayName("Customer lifecycle (P1-TSK-005)")
class CustomerLifecycleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS =
            new IdGenerator(CLOCK, new java.security.SecureRandom());

    @Test
    @DisplayName("a relationship opens PENDING, not ACTIVE")
    void opensPending() {
        // Pending because "we have recorded this person" and "this person may transact" are
        // different facts, with Phase 2's KYC decision between them. Opening ACTIVE and adding the
        // check later would mean every existing row had silently been treated as verified.
        Customer customer = Customer.open(IDS, CLOCK, PartyId.next(IDS));

        assertThat(customer.status()).isEqualTo(CustomerStatus.PENDING);
        assertThat(customer.openedAt()).isEqualTo(customer.statusChangedAt());
    }

    @Test
    @DisplayName("every state pair behaves exactly as the machine declares")
    void everyTransitionIsEnforced() {
        for (CustomerStatus from : CustomerStatus.values()) {
            for (CustomerStatus to : CustomerStatus.values()) {
                Customer customer = at(from);
                boolean permitted = from.permittedTransitions().contains(to);

                if (permitted) {
                    assertThat(move(customer, to).status())
                            .as("%s -> %s is declared permitted and must succeed", from, to)
                            .isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> move(customer, to))
                            .as("%s -> %s is not declared permitted and must be refused", from, to)
                            .isInstanceOf(IllegalCustomerTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("CLOSED is terminal: nothing leads out of it, including CLOSED itself")
    void closedIsTerminal() {
        // INV-LIFE-04. Asserted separately from the sweep because it is the property the domain
        // depends on rather than a consequence of the table: reopening would make history
        // non-monotonic, so a report issued while the relationship was closed would stop being
        // reproducible.
        Customer closed = at(CustomerStatus.CLOSED);

        assertThat(CustomerStatus.CLOSED.isTerminal()).isTrue();
        assertThat(CustomerStatus.CLOSED.permittedTransitions()).isEmpty();

        for (CustomerStatus target : CustomerStatus.values()) {
            assertThatThrownBy(() -> move(closed, target))
                    .as("CLOSED -> %s must be refused", target)
                    .isInstanceOf(IllegalCustomerTransitionException.class);
        }
    }

    @Test
    @DisplayName("closing an already-closed relationship raises rather than being a quiet no-op")
    void closingTwiceRaises() {
        // A silently idempotent close would hide a caller that believes it is ending a relationship
        // which ended months ago. That is a defect worth a stack trace, not a shrug.
        Customer closed = at(CustomerStatus.CLOSED);

        assertThatThrownBy(() -> closed.close(CLOCK))
                .isInstanceOf(IllegalCustomerTransitionException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    @DisplayName("suspension is reversible, because the relationship never ended")
    void suspensionIsReversible() {
        Customer active = at(CustomerStatus.ACTIVE);

        Customer suspended = active.suspend(CLOCK);
        assertThat(suspended.status()).isEqualTo(CustomerStatus.SUSPENDED);
        assertThatCode(() -> suspended.activate(CLOCK)).doesNotThrowAnyException();
        assertThat(suspended.activate(CLOCK).status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    @DisplayName("a rejected transition leaves the aggregate untouched")
    void aRejectedTransitionChangesNothing() {
        // The aggregate is immutable, so this holds by construction - and asserting it is what
        // stops a later refactor to mutable state from silently introducing a half-changed object
        // on the error path.
        Customer pending = at(CustomerStatus.PENDING);

        assertThatThrownBy(() -> pending.suspend(CLOCK))
                .isInstanceOf(IllegalCustomerTransitionException.class);
        assertThat(pending.status()).isEqualTo(CustomerStatus.PENDING);
    }

    @Test
    @DisplayName("the exception names both states, so a caller need not parse the message")
    void theExceptionCarriesItsStates() {
        IllegalCustomerTransitionException thrown =
                (IllegalCustomerTransitionException)
                        assertThatThrownBy(() -> at(CustomerStatus.CLOSED).activate(CLOCK))
                                .actual();

        assertThat(thrown.from()).isEqualTo(CustomerStatus.CLOSED);
        assertThat(thrown.to()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    @DisplayName("the terminal states are CLOSED and REJECTED, and no others")
    void exactlyTheTwoTerminalStates() {
        // A vacuity guard for the sweep above: if permittedTransitions() ever returned empty for
        // every state, every "must be refused" assertion would pass while the machine allowed
        // nothing at all. REJECTED joined at P2-TSK-014 - the KYC decision's refusal, terminal
        // for INV-LIFE-04's reason: re-onboarding is a new Customer, never a reopened one.
        Set<CustomerStatus> terminal = EnumSet.noneOf(CustomerStatus.class);
        for (CustomerStatus status : CustomerStatus.values()) {
            if (status.isTerminal()) {
                terminal.add(status);
            }
        }
        assertThat(terminal)
                .containsExactlyInAnyOrder(CustomerStatus.CLOSED, CustomerStatus.REJECTED);
    }

    @Test
    @DisplayName(
            "a move on a clock trailing the relationship's latest stamp is stamped at that stamp,"
                    + " never before it; a clock past it stamps its own read (X-TSK-006, ADR-0063"
                    + " decision 2)")
    void aMoveOnATrailingClockIsStampedAtTheLatestStamp() {
        // Opened at 09:00, activated at 09:01 - by an instance whose clock led this one's.
        Instant opened = Instant.parse("2026-09-04T09:00:00Z");
        Instant latest = opened.plusSeconds(60);
        Customer active =
                Customer.rehydrate(
                        CustomerId.next(IDS), PartyId.next(IDS), CustomerStatus.ACTIVE, opened, latest);
        Clock trailing = Clock.offset(Clock.fixed(latest, ZoneOffset.UTC), Duration.ofSeconds(-5));
        assertThat(Instant.now(trailing))
                .as("precondition: the clock trails the latest stamp, and not only birth")
                .isBefore(latest)
                .isAfter(opened);

        Customer suspended = active.suspend(trailing);
        assertThat(suspended.status()).isEqualTo(CustomerStatus.SUSPENDED);
        assertThat(suspended.statusChangedAt()).as("the predecessor's instant").isEqualTo(latest);
        assertThat(suspended.close(trailing).statusChangedAt()).isEqualTo(latest);

        // A floor, not a pin.
        Clock ahead = Clock.fixed(latest.plusSeconds(5), ZoneOffset.UTC);
        assertThat(active.suspend(ahead).statusChangedAt()).isEqualTo(latest.plusSeconds(5));
    }

    // -----------------------------------------------------------------

    private static Customer at(CustomerStatus status) {
        Instant now = Instant.now(CLOCK);
        return Customer.rehydrate(CustomerId.next(IDS), PartyId.next(IDS), status, now, now);
    }

    private static Customer move(Customer customer, CustomerStatus target) {
        return switch (target) {
            case PENDING -> throw new IllegalCustomerTransitionException(
                    customer.id(), customer.status(), CustomerStatus.PENDING);
            case ACTIVE -> customer.activate(CLOCK);
            case SUSPENDED -> customer.suspend(CLOCK);
            case CLOSED -> customer.close(CLOCK);
            case REJECTED -> customer.reject(CLOCK);
        };
    }
}
