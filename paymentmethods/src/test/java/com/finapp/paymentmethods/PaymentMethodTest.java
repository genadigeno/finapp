package com.finapp.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@link PaymentMethod} aggregate and its machine (`P5-TSK-004`): the exhaustive sweep
 * derived from the machine itself, the coherence refused on rehydrate, and every field's
 * PAN-refusing shape ({@code INV-PAY-02} at the domain).
 */
@DisplayName("PaymentMethod (P5-TSK-004)")
class PaymentMethodTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static PaymentMethod attached() {
        return PaymentMethod.attach(
                PaymentMethodId.next(IDS),
                UUID.randomUUID(),
                TokenReference.of("tok_visa-4242"),
                "Visa",
                "4242",
                12,
                2030,
                CLOCK);
    }

    private static PaymentMethod inState(PaymentMethodStatus status) {
        Instant created = Instant.now(CLOCK);
        return PaymentMethod.rehydrate(
                PaymentMethodId.next(IDS),
                UUID.randomUUID(),
                TokenReference.of("tok_visa-4242"),
                "Visa",
                "4242",
                12,
                2030,
                status,
                created,
                status.isTerminal() ? created : null);
    }

    @Nested
    @DisplayName("the machine")
    class TheMachine {

        @Test
        @DisplayName("every transition is enforced, swept from the cross-product")
        void everyTransitionIsEnforced() {
            // Derived from values() and permittedTransitions(), not listed by hand, so a state
            // added later is swept without anyone remembering (INV-LIFE-01/-02). The one
            // transition door is detach(); a target outside its edge has no door at all, which
            // the machine-shape assertions below pin.
            for (PaymentMethodStatus from : PaymentMethodStatus.values()) {
                PaymentMethod method = inState(from);
                if (from.canTransitionTo(PaymentMethodStatus.DETACHED)) {
                    PaymentMethod detached = method.detach(CLOCK);
                    assertThat(detached.status()).isEqualTo(PaymentMethodStatus.DETACHED);
                    assertThat(detached.detachedAt()).isPresent();
                } else {
                    assertThatThrownBy(() -> method.detach(CLOCK))
                            .isInstanceOf(IllegalPaymentMethodTransitionException.class);
                }
            }
        }

        @Test
        @DisplayName("the machine is pinned: one edge, DETACHED terminal, birth the only way in")
        void theMachineIsPinned() {
            // Pinned exactly, because a sweep that trusts the machine cannot notice the
            // machine changing (the P4-TSK-003 lesson).
            assertThat(PaymentMethodStatus.ACTIVE.permittedTransitions())
                    .containsExactly(PaymentMethodStatus.DETACHED);
            assertThat(PaymentMethodStatus.DETACHED.permittedTransitions()).isEmpty();
            assertThat(PaymentMethodStatus.DETACHED.isTerminal()).isTrue();
            // Nothing transitions TO ACTIVE: no state permits it - attachment is the only door.
            for (PaymentMethodStatus from : PaymentMethodStatus.values()) {
                assertThat(from.canTransitionTo(PaymentMethodStatus.ACTIVE)).isFalse();
            }
        }

        @Test
        @DisplayName("the generated SQL lists say what the machine says")
        void theGeneratedListsMatchTheMachine() {
            assertThat(PaymentMethodStatus.sqlValueList()).isEqualTo("'ACTIVE', 'DETACHED'");
            assertThat(PaymentMethodStatus.sqlTerminalValueList()).isEqualTo("'DETACHED'");
        }
    }

    @Nested
    @DisplayName("coherence, refused on rehydrate too")
    class Coherence {

        @Test
        @DisplayName("the status and the detachment instant are one fact")
        void statusAndInstantAreOneFact() {
            Instant now = Instant.now(CLOCK);
            assertThatThrownBy(
                            () ->
                                    PaymentMethod.rehydrate(
                                            PaymentMethodId.next(IDS),
                                            UUID.randomUUID(),
                                            TokenReference.of("tok_1a"),
                                            "Visa",
                                            "4242",
                                            1,
                                            2030,
                                            PaymentMethodStatus.DETACHED,
                                            now,
                                            null))
                    .as("a detached method without its instant is corrupt")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    PaymentMethod.rehydrate(
                                            PaymentMethodId.next(IDS),
                                            UUID.randomUUID(),
                                            TokenReference.of("tok_1a"),
                                            "Visa",
                                            "4242",
                                            1,
                                            2030,
                                            PaymentMethodStatus.ACTIVE,
                                            now,
                                            now))
                    .as("a live method with a detachment instant is corrupt")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    PaymentMethod.rehydrate(
                                            PaymentMethodId.next(IDS),
                                            UUID.randomUUID(),
                                            TokenReference.of("tok_1a"),
                                            "Visa",
                                            "4242",
                                            1,
                                            2030,
                                            PaymentMethodStatus.DETACHED,
                                            now,
                                            now.minusSeconds(60)))
                    .as("detachment cannot precede existence")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the fields' shapes cannot carry a PAN (INV-PAY-02 at the domain)")
    class PanRefusingShapes {

        @Test
        @DisplayName("the brand is letters and spaces - a charset with no digits at all")
        void brandHoldsNoDigits() {
            assertThatCode(() -> withBrand("American Express")).doesNotThrowAnyException();
            for (String bad : new String[] {"", "4111111111111111", "Visa4", " Visa", "Visa!"}) {
                assertThatThrownBy(() -> withBrand(bad))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> withBrand("A".repeat(31)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the display suffix is exactly four digits - last4, and nothing more")
        void suffixIsExactlyLastFour() {
            for (String bad : new String[] {"", "424", "42424", "4111111111111111", "abcd"}) {
                assertThatThrownBy(() -> withSuffix(bad))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("the expiry is two bounded integers")
        void expiryIsBounded() {
            for (int badMonth : new int[] {0, 13, -1}) {
                assertThatThrownBy(() -> withExpiry(badMonth, 2030))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            for (int badYear : new int[] {1999, 2101, 41111111}) {
                assertThatThrownBy(() -> withExpiry(12, badYear))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        private static void withBrand(String brand) {
            PaymentMethod.attach(
                    PaymentMethodId.next(IDS),
                    UUID.randomUUID(),
                    TokenReference.of("tok_1a"),
                    brand,
                    "4242",
                    12,
                    2030,
                    CLOCK);
        }

        private static void withSuffix(String suffix) {
            PaymentMethod.attach(
                    PaymentMethodId.next(IDS),
                    UUID.randomUUID(),
                    TokenReference.of("tok_1a"),
                    "Visa",
                    suffix,
                    12,
                    2030,
                    CLOCK);
        }

        private static void withExpiry(int month, int year) {
            PaymentMethod.attach(
                    PaymentMethodId.next(IDS),
                    UUID.randomUUID(),
                    TokenReference.of("tok_1a"),
                    "Visa",
                    "4242",
                    month,
                    year,
                    CLOCK);
        }
    }

    @Test
    @DisplayName("no rendering carries the token (INV-AUD-02)")
    void renderingsNeverCarryTheToken() {
        String needle = "tok_needle-9999x";
        PaymentMethod method =
                PaymentMethod.attach(
                        PaymentMethodId.next(IDS),
                        UUID.randomUUID(),
                        TokenReference.of(needle),
                        "Visa",
                        "4242",
                        12,
                        2030,
                        CLOCK);
        assertThat(method.toString()).doesNotContain(needle);
        assertThat(method.token().toString()).doesNotContain(needle);
        // The transition exception's message names states and the id, never the token.
        PaymentMethod detached = method.detach(CLOCK);
        assertThatThrownBy(() -> detached.detach(CLOCK))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(needle));
    }

    @Test
    @DisplayName("detach stamps the instant and preserves everything else")
    void detachStampsAndPreserves() {
        PaymentMethod live = attached();
        PaymentMethod detached = live.detach(CLOCK);
        assertThat(detached.id()).isEqualTo(live.id());
        assertThat(detached.partyId()).isEqualTo(live.partyId());
        assertThat(detached.token()).isEqualTo(live.token());
        assertThat(detached.brand()).isEqualTo(live.brand());
        assertThat(detached.displaySuffix()).isEqualTo(live.displaySuffix());
        assertThat(detached.createdAt()).isEqualTo(live.createdAt());
        assertThat(detached.detachedAt()).isPresent();
        assertThat(live.detachedAt()).isEmpty();
        assertThat(live.status()).isEqualTo(PaymentMethodStatus.ACTIVE);
    }

    @Test
    @DisplayName("the terminal set is exactly DETACHED, derived not listed")
    void terminalSetIsExactlyDetached() {
        assertThat(
                        EnumSet.allOf(PaymentMethodStatus.class).stream()
                                .filter(PaymentMethodStatus::isTerminal))
                .containsExactly(PaymentMethodStatus.DETACHED);
        assertThat(Optional.of(PaymentMethodStatus.ACTIVE).filter(PaymentMethodStatus::isTerminal))
                .isEmpty();
    }
}
