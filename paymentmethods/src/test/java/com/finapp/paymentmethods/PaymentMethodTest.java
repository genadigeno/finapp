package com.finapp.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@link PaymentMethod} aggregate and its machine (`P5-TSK-004`; kinds by `P7-TSK-007`):
 * the exhaustive sweep derived from the machine itself, the per-kind coherence refused on
 * rehydrate, and every field's identifier-refusing shape ({@code INV-PAY-02} and
 * {@code INV-RAIL-03} at the domain).
 */
@DisplayName("PaymentMethod (P5-TSK-004, P7-TSK-007)")
class PaymentMethodTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static PaymentMethod attached() {
        return PaymentMethod.attachCard(
                PaymentMethodId.next(IDS),
                UUID.randomUUID(),
                TokenReference.of("tok_visa-4242"),
                "Visa",
                "4242",
                12,
                2030,
                CLOCK);
    }

    private static PaymentMethod registered(PayeeCheck check, boolean acknowledged) {
        return PaymentMethod.registerBankAccount(
                PaymentMethodId.next(IDS),
                UUID.randomUUID(),
                DestinationReference.of("dest-acct-7f31"),
                "6819",
                check,
                acknowledged,
                CLOCK);
    }

    /** A stored card row in {@code status} — the rehydrate door, card arm. */
    private static PaymentMethod inState(PaymentMethodStatus status) {
        Instant created = Instant.now(CLOCK);
        return cardRow(status, created, status.isTerminal() ? created : null);
    }

    private static PaymentMethod cardRow(
            PaymentMethodStatus status, Instant createdAt, Instant detachedAt) {
        return PaymentMethod.rehydrate(
                PaymentMethodId.next(IDS),
                UUID.randomUUID(),
                PaymentMethodKind.CARD_TOKEN,
                TokenReference.of("tok_visa-4242"),
                "Visa",
                "4242",
                12,
                2030,
                null,
                null,
                null,
                status,
                createdAt,
                detachedAt);
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

        @Test
        @DisplayName("the kind and payee vocabularies generate what V003's CHECKs carry")
        void theGeneratedKindListsMatchTheEnums() {
            assertThat(PaymentMethodKind.sqlValueList())
                    .isEqualTo("'CARD_TOKEN', 'BANK_ACCOUNT'");
            assertThat(PayeeCheck.sqlValueList())
                    .isEqualTo("'MATCH', 'CLOSE_MATCH', 'NO_MATCH', 'UNAVAILABLE'");
        }
    }

    @Nested
    @DisplayName("coherence, refused on rehydrate too")
    class Coherence {

        @Test
        @DisplayName("the status and the detachment instant are one fact")
        void statusAndInstantAreOneFact() {
            Instant now = Instant.now(CLOCK);
            assertThatThrownBy(() -> cardRow(PaymentMethodStatus.DETACHED, now, null))
                    .as("a detached method without its instant is corrupt")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cardRow(PaymentMethodStatus.ACTIVE, now, now))
                    .as("a live method with a detachment instant is corrupt")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    cardRow(
                                            PaymentMethodStatus.DETACHED,
                                            now,
                                            now.minusSeconds(60)))
                    .as("detachment cannot precede existence")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the kinds' coherence (P7-TSK-007): a foreign kind's facts are refused")
    class TheKindsCoherence {

        @Test
        @DisplayName("each payee check registers, and NO_MATCH alone carries the consent instant")
        void everyPayeeCheckRegisters() {
            for (PayeeCheck check : PayeeCheck.values()) {
                PaymentMethod method = registered(check, true);
                assertThat(method.kind()).isEqualTo(PaymentMethodKind.BANK_ACCOUNT);
                assertThat(method.status()).isEqualTo(PaymentMethodStatus.ACTIVE);
                assertThat(method.payeeCheck()).contains(check);
                assertThat(method.token()).isEmpty();
                assertThat(method.brand()).isEmpty();
                assertThat(method.expiryMonth()).isEmpty();
                assertThat(method.expiryYear()).isEmpty();
                if (check == PayeeCheck.NO_MATCH) {
                    assertThat(method.noMatchAcknowledgedAt())
                            .as("NO_MATCH records when the customer said yes anyway")
                            .contains(method.createdAt());
                } else {
                    assertThat(method.noMatchAcknowledgedAt())
                            .as("consent to a mismatch that did not happen is not a fact")
                            .isEmpty();
                }
            }
        }

        @Test
        @DisplayName("an unacknowledged NO_MATCH cannot be constructed (ADR-0062 §2)")
        void unacknowledgedNoMatchIsRefused() {
            assertThatThrownBy(() -> registered(PayeeCheck.NO_MATCH, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("acknowledgement");
            for (PayeeCheck check : PayeeCheck.values()) {
                if (check != PayeeCheck.NO_MATCH) {
                    assertThatCode(() -> registered(check, false)).doesNotThrowAnyException();
                }
            }
        }

        @Test
        @DisplayName("the bank suffix is exactly four alphanumerics - the exchange's own bound")
        void bankSuffixIsFourAlphanumerics() {
            assertThatCode(() -> withBankSuffix("6a9Z")).doesNotThrowAnyException();
            for (String bad : new String[] {"", "681", "68195", "68-1", "GB29NWBK60161331"}) {
                assertThatThrownBy(() -> withBankSuffix(bad))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("detach is kind-agnostic and preserves the bank facts")
        void detachPreservesBankFacts() {
            PaymentMethod live = registered(PayeeCheck.CLOSE_MATCH, false);
            PaymentMethod detached = live.detach(CLOCK);
            assertThat(detached.kind()).isEqualTo(PaymentMethodKind.BANK_ACCOUNT);
            assertThat(detached.destination()).isEqualTo(live.destination());
            assertThat(detached.payeeCheck()).isEqualTo(live.payeeCheck());
            assertThat(detached.detachedAt()).isPresent();
        }

        private static void withBankSuffix(String suffix) {
            PaymentMethod.registerBankAccount(
                    PaymentMethodId.next(IDS),
                    UUID.randomUUID(),
                    DestinationReference.of("dest-acct-7f31"),
                    suffix,
                    PayeeCheck.MATCH,
                    false,
                    CLOCK);
        }
    }

    /**
     * Top-level deliberately: `MUTATION_TESTING.md` §2 names this method and the register
     * guard resolves methods on swept simple names, which a nested class is not — the
     * `P7-TSK-007` gate's own find, fixed by hoisting rather than by loosening the guard.
     */
    @Test
    @DisplayName("a card cannot carry bank facts, a bank account cannot carry card facts")
    void foreignFactsAreRefusedBothWays() {
            Instant now = Instant.now(CLOCK);
            // A card row dressed with a destination.
            assertThatThrownBy(
                            () ->
                                    PaymentMethod.rehydrate(
                                            PaymentMethodId.next(IDS),
                                            UUID.randomUUID(),
                                            PaymentMethodKind.CARD_TOKEN,
                                            TokenReference.of("tok_1a"),
                                            "Visa",
                                            "4242",
                                            12,
                                            2030,
                                            DestinationReference.of("dest-acct-7f31"),
                                            null,
                                            null,
                                            PaymentMethodStatus.ACTIVE,
                                            now,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class);
            // A bank row dressed with a brand.
            assertThatThrownBy(
                            () ->
                                    PaymentMethod.rehydrate(
                                            PaymentMethodId.next(IDS),
                                            UUID.randomUUID(),
                                            PaymentMethodKind.BANK_ACCOUNT,
                                            null,
                                            "Visa",
                                            "6819",
                                            null,
                                            null,
                                            DestinationReference.of("dest-acct-7f31"),
                                            PayeeCheck.MATCH,
                                            null,
                                            PaymentMethodStatus.ACTIVE,
                                            now,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class);
            // A bank row missing its own facts.
            assertThatThrownBy(
                            () ->
                                    PaymentMethod.rehydrate(
                                            PaymentMethodId.next(IDS),
                                            UUID.randomUUID(),
                                            PaymentMethodKind.BANK_ACCOUNT,
                                            null,
                                            null,
                                            "6819",
                                            null,
                                            null,
                                            null,
                                            PayeeCheck.MATCH,
                                            null,
                                            PaymentMethodStatus.ACTIVE,
                                            now,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class);
            // An acknowledgement instant riding a MATCH is corrupt (the one-fact rule).
            assertThatThrownBy(
                            () ->
                                    PaymentMethod.rehydrate(
                                            PaymentMethodId.next(IDS),
                                            UUID.randomUUID(),
                                            PaymentMethodKind.BANK_ACCOUNT,
                                            null,
                                            null,
                                            "6819",
                                            null,
                                            null,
                                            DestinationReference.of("dest-acct-7f31"),
                                            PayeeCheck.MATCH,
                                            now,
                                            PaymentMethodStatus.ACTIVE,
                                            now,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class);
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
        @DisplayName("the card display suffix is exactly four digits - last4, and nothing more")
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
            PaymentMethod.attachCard(
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
            PaymentMethod.attachCard(
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
            PaymentMethod.attachCard(
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
    @DisplayName("no rendering carries the token or the destination (INV-AUD-02)")
    void renderingsNeverCarryTheReferences() {
        String tokenNeedle = "tok_needle-9999x";
        PaymentMethod card =
                PaymentMethod.attachCard(
                        PaymentMethodId.next(IDS),
                        UUID.randomUUID(),
                        TokenReference.of(tokenNeedle),
                        "Visa",
                        "4242",
                        12,
                        2030,
                        CLOCK);
        assertThat(card.toString()).doesNotContain(tokenNeedle);
        assertThat(card.token().toString()).doesNotContain(tokenNeedle);
        String destinationNeedle = "dest-needle-a4b2c9";
        PaymentMethod bank =
                PaymentMethod.registerBankAccount(
                        PaymentMethodId.next(IDS),
                        UUID.randomUUID(),
                        DestinationReference.of(destinationNeedle),
                        "6819",
                        PayeeCheck.MATCH,
                        false,
                        CLOCK);
        assertThat(bank.toString()).doesNotContain(destinationNeedle);
        assertThat(bank.destination().toString()).doesNotContain(destinationNeedle);
        // The transition exception's message names states and the id, never a reference.
        PaymentMethod detached = card.detach(CLOCK);
        assertThatThrownBy(() -> detached.detach(CLOCK))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(tokenNeedle));
    }

    @Test
    @DisplayName("detach stamps the instant and preserves everything else")
    void detachStampsAndPreserves() {
        PaymentMethod live = attached();
        PaymentMethod detached = live.detach(CLOCK);
        assertThat(detached.id()).isEqualTo(live.id());
        assertThat(detached.partyId()).isEqualTo(live.partyId());
        assertThat(detached.kind()).isEqualTo(live.kind());
        assertThat(detached.token()).isEqualTo(live.token());
        assertThat(detached.brand()).isEqualTo(live.brand());
        assertThat(detached.displaySuffix()).isEqualTo(live.displaySuffix());
        assertThat(detached.createdAt()).isEqualTo(live.createdAt());
        assertThat(detached.detachedAt()).isPresent();
        assertThat(live.detachedAt()).isEmpty();
        assertThat(live.status()).isEqualTo(PaymentMethodStatus.ACTIVE);
    }

    @Test
    @DisplayName(
            "a clock behind birth cannot fail a legal detach: the stamp clamps to createdAt"
                    + " (the P1-TSK-031 drift, met in domain code; ADR-0014)")
    void aClockBehindBirthCannotFailALegalDetach() {
        PaymentMethod live = attached();
        Clock behind = Clock.fixed(live.createdAt().minusMillis(250), ZoneOffset.UTC);

        PaymentMethod detached = live.detach(behind);
        assertThat(detached.status()).isEqualTo(PaymentMethodStatus.DETACHED);
        assertThat(detached.detachedAt()).contains(live.createdAt());

        // A floor, not a pin: a clock at or past birth stamps its own read.
        Clock ahead = Clock.fixed(live.createdAt().plusSeconds(5), ZoneOffset.UTC);
        assertThat(live.detach(ahead).detachedAt()).contains(live.createdAt().plusSeconds(5));
    }

    @Test
    @DisplayName(
            "an illegal detach under a behind clock is still the machine's refusal, never the"
                    + " constructor guard's")
    void anIllegalDetachUnderABehindClockIsStillTheMachinesRefusal() {
        PaymentMethod detached = inState(PaymentMethodStatus.DETACHED);
        Clock behind = Clock.fixed(detached.createdAt().minusSeconds(1), ZoneOffset.UTC);
        assertThatThrownBy(() -> detached.detach(behind))
                .isInstanceOf(IllegalPaymentMethodTransitionException.class);
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
