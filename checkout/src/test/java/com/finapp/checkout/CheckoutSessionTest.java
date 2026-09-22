package com.finapp.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CheckoutSession aggregate and its machine (`P6-TSK-006`, ADR-0053).
 *
 * <p>The illegal edges are swept from the machine's <strong>own cross-product</strong> rather
 * than from a hand-written list, so a state added later is held to the property without anybody
 * editing this test — the stale-list defect, closed the way this repository closes it.
 */
@DisplayName("the CheckoutSession aggregate (P6-TSK-006)")
class CheckoutSessionTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(100_00L, EUR);
    private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(30));

    @Test
    @DisplayName("every illegal edge is refused, swept from the machine's own cross-product")
    void everyIllegalEdgeIsRefused() {
        for (CheckoutSessionStatus from : CheckoutSessionStatus.values()) {
            for (CheckoutSessionStatus to : CheckoutSessionStatus.values()) {
                if (from.canTransitionTo(to)) {
                    continue;
                }
                CheckoutSession session = sessionIn(from);
                assertThatThrownBy(() -> move(session, to))
                        .as("%s -> %s is not on the machine", from, to)
                        .isInstanceOfAny(
                                IllegalCheckoutSessionTransitionException.class,
                                CheckoutSessionExpiredException.class);
            }
        }
    }

    @Test
    @DisplayName("every LEGAL edge lands, swept from the same cross-product")
    void everyLegalEdgeLands() {
        // The complement, and it is worth its own test: a machine that refuses EVERYTHING would
        // pass the sweep above.
        int landed = 0;
        for (CheckoutSessionStatus from : CheckoutSessionStatus.values()) {
            for (CheckoutSessionStatus to : from.permittedTransitions()) {
                assertThat(move(sessionIn(from), to).status())
                        .as("%s -> %s is on the machine", from, to)
                        .isEqualTo(to);
                landed++;
            }
        }
        assertThat(landed).as("six edges: 3 + 2 + 1").isEqualTo(6);
    }

    @Test
    @DisplayName("the three terminal states are terminal, held as a machine property")
    void theTerminalStatesAreTerminal() {
        Set<CheckoutSessionStatus> terminal =
                EnumSet.copyOf(
                        java.util.Arrays.stream(CheckoutSessionStatus.values())
                                .filter(CheckoutSessionStatus::isTerminal)
                                .toList());
        assertThat(terminal)
                .as("a completed, late-completed or abandoned session has nowhere to go")
                .containsExactlyInAnyOrder(
                        CheckoutSessionStatus.COMPLETED,
                        CheckoutSessionStatus.COMPLETED_LATE,
                        CheckoutSessionStatus.ABANDONED);
    }

    @Test
    @DisplayName("EXPIRED is NOT terminal - landed money always wins (INV-MER-06)")
    void expiredIsNotTerminal() {
        // The edge the whole race rule rests on. If EXPIRED were terminal, a capture that
        // landed after the clock ran out would have nowhere to go, and the platform's only
        // options would be to drop it or auto-reverse it - which is exactly what ADR-0053 §5
        // refuses, because undoing a movement that succeeded means starting a second one.
        assertThat(CheckoutSessionStatus.EXPIRED.isTerminal()).isFalse();
        assertThat(CheckoutSessionStatus.EXPIRED.permittedTransitions())
                .containsExactly(CheckoutSessionStatus.COMPLETED_LATE);
    }

    @Test
    @DisplayName("a PAYMENT_PENDING session cannot be abandoned - money is in flight")
    void aPendingSessionCannotBeAbandoned() {
        assertThatThrownBy(() -> sessionIn(CheckoutSessionStatus.PAYMENT_PENDING).abandon(CLOCK))
                .isInstanceOf(IllegalCheckoutSessionTransitionException.class);
    }

    @Test
    @DisplayName("there is no failure state: a declined payment is the PAYMENT's state")
    void thereIsNoFailureState() {
        assertThat(CheckoutSessionStatus.values())
                .as("a FAILED session would end an offer the customer has not given up on")
                .noneMatch(status -> status.name().contains("FAIL"));
    }

    @Test
    @DisplayName("refusal messages carry STATES only - no identifier, no amount, no token"
            + " (INV-AUD-02)")
    void refusalMessagesCarryStatesOnly() {
        CheckoutSession session = sessionIn(CheckoutSessionStatus.COMPLETED);
        String message =
                org.assertj.core.api.Assertions.catchThrowable(() -> session.abandon(CLOCK))
                        .getMessage();
        assertThat(message)
                .contains("COMPLETED")
                .contains("ABANDONED")
                .doesNotContain(session.id().value().toString())
                .doesNotContain(session.merchantRef().toString())
                .doesNotContain("100")
                .doesNotContain(session.tokenHash().expose());
    }

    // ----------------------------------------------------------------- the clock

    @Test
    @DisplayName("THE CLOCK AND THE STATE ARE DIFFERENT QUESTIONS: a session past its deadline"
            + " still reads OPEN until the sweeper arrives, and still refuses new work")
    void theClockRefusesEvenWhileTheRowSaysOpen() {
        CheckoutSession open = open();
        Clock afterDeadline = Clock.fixed(DEADLINE.plusSeconds(1), ZoneOffset.UTC);

        assertThat(open.status())
                .as("the state is the sweeper's to produce - it has not run")
                .isEqualTo(CheckoutSessionStatus.OPEN);
        assertThat(open.acceptsNewWork()).as("the machine's half of the gate").isTrue();
        assertThat(open.hasExpired(afterDeadline)).as("the clock's half").isTrue();

        assertThatThrownBy(() -> open.confirm(afterDeadline, UUID.randomUUID()))
                .as("a sweeper one minute behind is not a minute in which money may land")
                .isInstanceOf(CheckoutSessionExpiredException.class);
    }

    @Test
    @DisplayName("the deadline is exclusive at its own instant - at expires_at, it has expired")
    void theDeadlineIsInclusiveOfExpiry() {
        assertThat(open().hasExpired(Clock.fixed(DEADLINE, ZoneOffset.UTC))).isTrue();
        assertThat(open().hasExpired(Clock.fixed(DEADLINE.minusMillis(1), ZoneOffset.UTC)))
                .isFalse();
    }

    @Test
    @DisplayName("a confirmation before the deadline lands, and attaches the intent")
    void aTimelyConfirmationAttachesTheIntent() {
        UUID intent = UUID.randomUUID();
        CheckoutSession confirmed = open().confirm(CLOCK, intent);
        assertThat(confirmed.status()).isEqualTo(CheckoutSessionStatus.PAYMENT_PENDING);
        assertThat(confirmed.paymentIntentRef()).contains(intent);
    }

    @Test
    @DisplayName("the payment intent is SET ONCE - a session opens one and keeps it")
    void thePaymentIntentIsSetOnce() {
        // The aggregate's half; V002's trigger holds the same rule for raw SQL. A second value
        // would mean the session had quietly started a second payment.
        CheckoutSession confirmed = open().confirm(CLOCK, UUID.randomUUID());
        assertThatThrownBy(() -> confirmed.confirm(CLOCK, UUID.randomUUID()))
                .isInstanceOf(IllegalCheckoutSessionTransitionException.class);
    }

    // ----------------------------------------------------------------- coherence

    @Test
    @DisplayName("the constructor holds the coherence: positive amount, bounded summary,"
            + " a deadline after birth")
    void theConstructorHoldsTheCoherence() {
        assertThatThrownBy(() -> openWith(Money.ofMinorUnits(0L, EUR), "Two coffees", DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> openWith(AMOUNT, "  ", DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                openWith(
                                        AMOUNT,
                                        "x".repeat(CheckoutSession.MAX_LINE_SUMMARY_LENGTH + 1),
                                        DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> openWith(AMOUNT, "Two coffees", NOW))
                .as("an offer that expires before it exists is not an offer")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString names the identifier and the state, never the contents (INV-AUD-02)")
    void toStringNamesNoContents() {
        CheckoutSession session = open();
        assertThat(session.toString())
                .contains(session.id().value().toString())
                .contains("OPEN")
                .doesNotContain("Two coffees")
                .doesNotContain(session.tokenHash().expose());
    }

    @Test
    @DisplayName("a session authenticates its own token and no other")
    void aSessionAuthenticatesItsOwnToken() {
        SecureRandom randomness = new SecureRandom();
        CheckoutSessionToken mine = CheckoutSessionToken.issue(randomness);
        CheckoutSessionToken theirs = CheckoutSessionToken.issue(randomness);
        CheckoutSession session =
                CheckoutSession.open(
                        IDS, CLOCK, UUID.randomUUID(), AMOUNT, "Two coffees",
                        UUID.randomUUID(), mine, DEADLINE);

        assertThat(session.authenticates(mine)).isTrue();
        assertThat(session.authenticates(theirs)).isFalse();
    }

    // -----------------------------------------------------------------

    private static CheckoutSession open() {
        return openWith(AMOUNT, "Two coffees", DEADLINE);
    }

    private static CheckoutSession openWith(Money amount, String summary, Instant expiresAt) {
        return CheckoutSession.open(
                IDS,
                CLOCK,
                UUID.randomUUID(),
                amount,
                summary,
                UUID.randomUUID(),
                CheckoutSessionToken.issue(new SecureRandom()),
                expiresAt);
    }

    /** A session already in {@code status} — rehydrated, the way storage would hand one back. */
    private static CheckoutSession sessionIn(CheckoutSessionStatus status) {
        CheckoutSession born = open();
        return CheckoutSession.rehydrate(
                born.id(),
                born.merchantRef(),
                born.amount(),
                born.lineSummary(),
                born.feeScheduleVersionRef(),
                born.tokenHash(),
                born.algorithm(),
                status == CheckoutSessionStatus.OPEN
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(UUID.randomUUID()),
                status,
                born.expiresAt(),
                born.createdAt(),
                born.statusChangedAt());
    }

    private static CheckoutSession move(CheckoutSession session, CheckoutSessionStatus to) {
        return switch (to) {
            case PAYMENT_PENDING -> session.confirm(CLOCK, UUID.randomUUID());
            case COMPLETED -> session.complete(CLOCK);
            case COMPLETED_LATE -> session.completeLate(CLOCK);
            case EXPIRED -> session.expire(CLOCK);
            case ABANDONED -> session.abandon(CLOCK);
            // No producer, at the aggregate as at the schema: birth is the only door.
            case OPEN -> throw new IllegalCheckoutSessionTransitionException(
                    session.status(), CheckoutSessionStatus.OPEN);
        };
    }
}
