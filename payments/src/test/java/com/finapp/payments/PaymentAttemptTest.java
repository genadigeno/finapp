package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payment-attempt machine, held at the aggregate ({@code P5-TSK-007}, ADR-0045,
 * {@code INV-LIFE-01/-02/-03}).
 *
 * <p>The transition sweep is derived from the machine; the machine itself is separately
 * <strong>pinned</strong> — {@code PaymentIntentTest}'s recorded reasoning: a sweep that trusts
 * the machine follows it when it changes. The pin here carries two of ADR-0045's refusals by
 * name: {@code CAPTURED} gaining an exit (refund-as-an-attempt-edge) and {@code AUTHORIZED →
 * FAILED} appearing ({@code VOIDED}'s job, which has no producer until Phase 6).
 *
 * <p>{@code values()} is pinned exactly, because the backlog's acceptance is worded as an
 * absence: no state without a producer — a smuggled {@code VOIDED}, {@code REQUIRES_ACTION} or
 * {@code SETTLED} fails this suite before anything consumes it.
 *
 * <p>The SQL fragments are pinned by literal until {@code P5-TSK-008}'s reconciliation consumes
 * the generators (the {@code P1-TSK-013} class).
 */
@DisplayName("PaymentAttempt (P5-TSK-007)")
class PaymentAttemptTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Money AMOUNT = Money.ofMinorUnits(98_76, EUR);

    @Test
    @DisplayName("the machine is pinned: seven states, eleven edges, CAPTURED with no exit")
    void theMachineIsPinned() {
        assertThat(PaymentAttemptStatus.AUTH_DISPATCHED.permittedTransitions())
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.AUTHORIZED,
                        PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.AUTH_UNKNOWN);
        assertThat(PaymentAttemptStatus.AUTH_UNKNOWN.permittedTransitions())
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.AUTHORIZED, PaymentAttemptStatus.FAILED);
        // AUTHORIZED has exactly ONE exit. AUTHORIZED -> FAILED is not an edge: abandoning an
        // authorization is VOIDED's job, absent until its producer arrives (checkout expiry,
        // Phase 6, ADR-0045 §5) — an edge added here is that decision taken silently.
        assertThat(PaymentAttemptStatus.AUTHORIZED.permittedTransitions())
                .as("AUTHORIZED exits only to CAPTURE_DISPATCHED (no VOIDED yet, ADR-0045)")
                .containsExactly(PaymentAttemptStatus.CAPTURE_DISPATCHED);
        assertThat(PaymentAttemptStatus.CAPTURE_DISPATCHED.permittedTransitions())
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.CAPTURED,
                        PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.CAPTURE_UNKNOWN);
        assertThat(PaymentAttemptStatus.CAPTURE_UNKNOWN.permittedTransitions())
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.CAPTURED, PaymentAttemptStatus.FAILED);

        // CAPTURED is the attempt's stable state: NO outgoing edge. Refunds reference the
        // captured attempt and never transition it (ADR-0045) — the SUCCEEDED pin's argument,
        // and the assertion a machine-derived sweep cannot make.
        assertThat(PaymentAttemptStatus.CAPTURED.permittedTransitions())
                .as("CAPTURED is stable with no outgoing edge (ADR-0045)")
                .isEmpty();
        assertThat(PaymentAttemptStatus.FAILED.permittedTransitions()).isEmpty();

        // Nothing transitions TO AUTH_DISPATCHED — birth is the only door.
        for (PaymentAttemptStatus from : PaymentAttemptStatus.values()) {
            assertThat(from.canTransitionTo(PaymentAttemptStatus.AUTH_DISPATCHED))
                    .as("%s -> AUTH_DISPATCHED must not exist", from)
                    .isFalse();
        }

        // Terminal is the structural derivation, so CAPTURED is IN the set — captured is not
        // settled (INV-SET-01), and settlement attaches in Phase 8 without touching this enum.
        assertThat(EnumSet.allOf(PaymentAttemptStatus.class).stream()
                        .filter(PaymentAttemptStatus::isTerminal))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.CAPTURED, PaymentAttemptStatus.FAILED);

        // The deliberately-absent states asserted absent (the backlog's acceptance): exactly
        // these seven, in machine order — no VOIDED, no REQUIRES_ACTION, no CLEARING/SETTLED,
        // no retry states. A state without a producer fails here before anything consumes it.
        assertThat(PaymentAttemptStatus.values())
                .containsExactly(
                        PaymentAttemptStatus.AUTH_DISPATCHED,
                        PaymentAttemptStatus.AUTH_UNKNOWN,
                        PaymentAttemptStatus.AUTHORIZED,
                        PaymentAttemptStatus.CAPTURE_DISPATCHED,
                        PaymentAttemptStatus.CAPTURE_UNKNOWN,
                        PaymentAttemptStatus.CAPTURED,
                        PaymentAttemptStatus.FAILED);
    }

    @Test
    @DisplayName("every transition in the cross-product behaves as the machine declares")
    void everyTransitionIsEnforced() {
        for (PaymentAttemptStatus from : PaymentAttemptStatus.values()) {
            for (Map.Entry<PaymentAttemptStatus, UnaryOperator<PaymentAttempt>> target :
                    transitionDoors().entrySet()) {
                PaymentAttempt attempt = attemptAt(from);
                PaymentAttemptStatus to = target.getKey();
                if (from.canTransitionTo(to)) {
                    assertThat(target.getValue().apply(attempt).status())
                            .as("%s -> %s is permitted by the machine", from, to)
                            .isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> target.getValue().apply(attempt))
                            .as("%s -> %s must be refused by the aggregate itself", from, to)
                            .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("both terminals refuse every door, swept separately by name (INV-LIFE-04)")
    void theTerminalsRefuseEveryDoor() {
        // CAPTURED is terminal AND the stable state; FAILED is terminal. Swept by name so the
        // acceptance criterion's wording is what the test says.
        for (PaymentAttemptStatus terminal :
                EnumSet.of(PaymentAttemptStatus.CAPTURED, PaymentAttemptStatus.FAILED)) {
            for (Map.Entry<PaymentAttemptStatus, UnaryOperator<PaymentAttempt>> target :
                    transitionDoors().entrySet()) {
                PaymentAttempt attempt = attemptAt(terminal);
                assertThatThrownBy(() -> target.getValue().apply(attempt))
                        .as("%s must refuse a move to %s", terminal, target.getKey())
                        .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
            }
        }
    }

    @Test
    @DisplayName("birth commits AUTH_DISPATCHED with the idempotency reference already minted")
    void creationIsCoherent() {
        ProviderIdempotencyReference reference = idem();
        PaymentAttempt attempt =
                PaymentAttempt.create(IDS, CLOCK, PaymentIntentId.next(IDS), reference);
        assertThat(attempt.status()).isEqualTo(PaymentAttemptStatus.AUTH_DISPATCHED);
        assertThat(attempt.authorizationReference()).isEqualTo(reference);
        assertThat(attempt.id()).isNotNull();
        assertThat(attempt.createdAt()).isNotNull();
        assertThat(attempt.authorizedAmount()).isNull();
        assertThat(attempt.failureReason()).isNull();

        // No attempt without its dispatch reference — the dispatch commits before the provider
        // is asked (ADR-0046), and the reference is what a sweeper queries by (INV-PAY-04).
        assertThatThrownBy(() ->
                        PaymentAttempt.create(IDS, CLOCK, PaymentIntentId.next(IDS), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PaymentAttempt.create(IDS, CLOCK, null, idem()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("capture is bounded by authorization (INV-PAY-05), refused naming no amount")
    void captureIsBoundedByAuthorization() {
        PaymentAttempt dispatched = attemptAt(PaymentAttemptStatus.CAPTURE_DISPATCHED);

        // Equal capture is legal — the bound is <=, and the top-up flow captures in full.
        assertThat(dispatched.capture(new ProviderReference("psp-cap-full"), AMOUNT)
                        .capturedAmount())
                .isEqualTo(AMOUNT);
        // Partial capture is legal domain-wise; taking less than promised violates nothing.
        assertThat(dispatched.capture(
                                new ProviderReference("psp-cap-part"),
                                Money.ofMinorUnits(50_00, EUR))
                        .capturedAmount())
                .isEqualTo(Money.ofMinorUnits(50_00, EUR));

        // One minor unit over the authorization is money the customer never approved. The
        // needle: 98_76 authorized, 98_77 captured — the refusal names the invariant and the
        // currency, never either value (INV-AUD-02).
        assertThatThrownBy(() -> dispatched.capture(
                        new ProviderReference("psp-cap-over"), Money.ofMinorUnits(98_77, EUR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9876")
                .hasMessageNotContaining("98.76")
                .hasMessageNotContaining("9877")
                .hasMessageNotContaining("98.77");

        // A capture in a different currency is not comparable to the promise at all.
        assertThatThrownBy(() -> dispatched.capture(
                        new ProviderReference("psp-cap-usd"), Money.ofMinorUnits(1_00, USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9876");
    }

    @Test
    @DisplayName("rehydrate routes through the one constructor and refuses the corrupt row")
    void rehydrateRefusesTheCorruptRow() {
        // Every status's coherent shape constructs — the shapes the fixture defines are the
        // shapes the machine's paths produce.
        for (PaymentAttemptStatus status : PaymentAttemptStatus.values()) {
            assertThat(attemptAt(status).status()).isEqualTo(status);
        }

        // FAILED without its mapped reason — the reason is THIS row's fact (INV-PAY-03).
        assertThatThrownBy(() -> rehydrated(
                        null, null, null, null, null, null, PaymentAttemptStatus.FAILED))
                .as("FAILED without a reason")
                .isInstanceOf(IllegalArgumentException.class);
        // A reason on a live row — failure data on a row that has not failed.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null,
                        PaymentFailureReason.DECLINED, PaymentAttemptStatus.AUTHORIZED))
                .as("a reason outside FAILED")
                .isInstanceOf(IllegalArgumentException.class);

        // The issuer's promise split in half — reference without amount, amount without
        // reference: one fact, refused in both directions.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), null, null, null, null,
                        PaymentAttemptStatus.AUTHORIZED))
                .as("a provider reference without its amount")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        null, null, AMOUNT, null, null, null, PaymentAttemptStatus.AUTHORIZED))
                .as("an authorized amount without its reference")
                .isInstanceOf(IllegalArgumentException.class);

        // Pre-auth states carry nothing beyond birth; AUTHORIZED has no capture reference yet;
        // capture-stage states require one.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null, null,
                        PaymentAttemptStatus.AUTH_DISPATCHED))
                .as("AUTH_DISPATCHED holding an authorization")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, null, null, null,
                        PaymentAttemptStatus.AUTHORIZED))
                .as("AUTHORIZED already holding a capture reference")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null, null,
                        PaymentAttemptStatus.CAPTURE_DISPATCHED))
                .as("CAPTURE_DISPATCHED without the capture's idempotency reference")
                .isInstanceOf(IllegalArgumentException.class);

        // The unreachable FAILED shape: the issuer's promise held, no capture dispatched.
        // AUTHORIZED -> FAILED is not an edge, so no path writes this row — rehydrate refuses
        // it as corruption rather than trusting the database.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null,
                        PaymentFailureReason.DECLINED, PaymentAttemptStatus.FAILED))
                .as("FAILED holding the promise but no capture dispatch — no path writes this")
                .isInstanceOf(IllegalArgumentException.class);

        // CAPTURED must hold the capture pair; a captured amount anywhere else is incoherent.
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, null, null, null,
                        PaymentAttemptStatus.CAPTURED))
                .as("CAPTURED without the capture pair")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, capRef(), AMOUNT, null,
                        PaymentAttemptStatus.CAPTURE_DISPATCHED))
                .as("a captured amount before CAPTURED")
                .isInstanceOf(IllegalArgumentException.class);

        // The stored over-capture — the schema bypass INV-PAY-05's constructor placement
        // exists for: caught on read-back ALONE if the door check were ever weakened.
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, capRef(), Money.ofMinorUnits(98_77, EUR),
                        null, PaymentAttemptStatus.CAPTURED))
                .as("a stored captured amount exceeding the authorization")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9877")
                .hasMessageNotContaining("98.77");

        // A stored non-positive promise.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), Money.ofMinorUnits(-98_76, EUR), null, null, null,
                        PaymentAttemptStatus.AUTHORIZED))
                .as("a stored non-positive authorized amount")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9876");

        // Absent identity facts are refused whatever the status.
        assertThatThrownBy(() -> PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), null, idem(), null, null, null, null, null,
                        null, PaymentAttemptStatus.AUTH_DISPATCHED, Instant.now(CLOCK)))
                .as("no intent reference")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), PaymentIntentId.next(IDS), null, null, null,
                        null, null, null, null, PaymentAttemptStatus.AUTH_DISPATCHED,
                        Instant.now(CLOCK)))
                .as("no authorization idempotency reference")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), PaymentIntentId.next(IDS), idem(), null,
                        null, null, null, null, null, null, Instant.now(CLOCK)))
                .as("no status")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a transition carries exactly its payload and nothing else")
    void aTransitionCarriesExactlyItsPayload() {
        // Unlike the intent, attempt transitions carry the fact that arrived with them — and
        // ONLY that fact. Asserted per field so a stamp or extra payload added to a door is a
        // decision, not a drift; P5-TSK-008 reads its UPDATE grant off this shape.
        PaymentAttempt born =
                PaymentAttempt.create(IDS, CLOCK, PaymentIntentId.next(IDS), idem());
        ProviderReference promise = new ProviderReference("psp-auth-42");
        PaymentAttempt authorized = born.authorize(promise, AMOUNT);
        assertThat(authorized.status()).isEqualTo(PaymentAttemptStatus.AUTHORIZED);
        assertThat(authorized.authorizationProviderReference()).isEqualTo(promise);
        assertThat(authorized.authorizedAmount()).isEqualTo(AMOUNT);
        assertThat(authorized.id()).isEqualTo(born.id());
        assertThat(authorized.intentId()).isEqualTo(born.intentId());
        assertThat(authorized.authorizationReference())
                .isEqualTo(born.authorizationReference());
        assertThat(authorized.captureReference()).isNull();
        assertThat(authorized.captureProviderReference()).isNull();
        assertThat(authorized.capturedAmount()).isNull();
        assertThat(authorized.failureReason()).isNull();
        assertThat(authorized.createdAt()).isEqualTo(born.createdAt());

        PaymentAttempt failed = born.fail(PaymentFailureReason.PROVIDER_UNAVAILABLE);
        assertThat(failed.status()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo(PaymentFailureReason.PROVIDER_UNAVAILABLE);
        assertThat(failed.authorizedAmount()).isNull();
        assertThat(failed.id()).isEqualTo(born.id());
        assertThat(failed.createdAt()).isEqualTo(born.createdAt());
    }

    @Test
    @DisplayName("the SQL fragments are pinned until P5-TSK-008's reconciliation consumes them")
    void theSqlFragmentsArePinned() {
        assertThat(PaymentAttemptStatus.sqlValueList())
                .isEqualTo("'AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'AUTHORIZED',"
                        + " 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED'");
        // CAPTURED is IN the terminal list — the enum's recorded decision.
        assertThat(PaymentAttemptStatus.sqlTerminalValueList())
                .isEqualTo("'CAPTURED', 'FAILED'");
    }

    /** The six doors out of a state, keyed by the state each targets. */
    private static Map<PaymentAttemptStatus, UnaryOperator<PaymentAttempt>> transitionDoors() {
        return Map.of(
                PaymentAttemptStatus.AUTHORIZED,
                        attempt -> attempt.authorize(new ProviderReference("psp-auth-1"), AMOUNT),
                PaymentAttemptStatus.AUTH_UNKNOWN,
                        PaymentAttempt::authorizationOutcomeUnknown,
                PaymentAttemptStatus.CAPTURE_DISPATCHED,
                        attempt -> attempt.dispatchCapture(idem()),
                PaymentAttemptStatus.CAPTURE_UNKNOWN, PaymentAttempt::captureOutcomeUnknown,
                PaymentAttemptStatus.CAPTURED,
                        attempt -> attempt.capture(new ProviderReference("psp-cap-1"), AMOUNT),
                PaymentAttemptStatus.FAILED,
                        attempt -> attempt.fail(PaymentFailureReason.DECLINED));
    }

    /**
     * An attempt rehydrated in {@code status}, in that status's coherent shape — which shape is
     * coherent is exactly what the constructor's stage rules define. {@code FAILED} uses the
     * failed-at-authorization shape; the failed-at-capture shape is separately proven by the
     * sweep's {@code fail} door out of the capture-stage fixtures.
     */
    private static PaymentAttempt attemptAt(PaymentAttemptStatus status) {
        return switch (status) {
            case AUTH_DISPATCHED, AUTH_UNKNOWN ->
                    rehydrated(null, null, null, null, null, null, status);
            case AUTHORIZED -> rehydrated(null, authRef(), AMOUNT, null, null, null, status);
            case CAPTURE_DISPATCHED, CAPTURE_UNKNOWN ->
                    rehydrated(idem(), authRef(), AMOUNT, null, null, null, status);
            case CAPTURED ->
                    rehydrated(idem(), authRef(), AMOUNT, capRef(), AMOUNT, null, status);
            case FAILED -> rehydrated(
                    null, null, null, null, null, PaymentFailureReason.DECLINED, status);
        };
    }

    private static PaymentAttempt rehydrated(
            ProviderIdempotencyReference captureReference,
            ProviderReference authorizationProviderReference,
            Money authorizedAmount,
            ProviderReference captureProviderReference,
            Money capturedAmount,
            PaymentFailureReason failureReason,
            PaymentAttemptStatus status) {
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS),
                PaymentIntentId.next(IDS),
                idem(),
                captureReference,
                authorizationProviderReference,
                authorizedAmount,
                captureProviderReference,
                capturedAmount,
                failureReason,
                status,
                Instant.now(CLOCK));
    }

    private static ProviderIdempotencyReference idem() {
        return new ProviderIdempotencyReference("ref-" + UUID.randomUUID());
    }

    private static ProviderReference authRef() {
        return new ProviderReference("psp-auth-evidence");
    }

    private static ProviderReference capRef() {
        return new ProviderReference("psp-cap-evidence");
    }
}
