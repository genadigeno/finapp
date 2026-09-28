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
    @DisplayName("the two-step machine is pinned on its model: seven states, eleven edges,"
            + " CAPTURED with no exit (P7-TSK-002 moved the edges to InteractionModel)")
    void theMachineIsPinned() {
        var twoStep = InteractionModel.TWO_STEP.edges();
        assertThat(twoStep.get(PaymentAttemptStatus.AUTH_DISPATCHED))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.AUTHORIZED,
                        PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.AUTH_UNKNOWN);
        assertThat(twoStep.get(PaymentAttemptStatus.AUTH_UNKNOWN))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.AUTHORIZED, PaymentAttemptStatus.FAILED);
        // AUTHORIZED exits to the capture and to the void, and STILL never to FAILED
        // directly (P7-TSK-004): abandoning an authorization is the void's job, performed
        // now - a direct AUTHORIZED -> FAILED edge would have no producer, and the drawn
        // one in ADR-0059's first diagram was corrected by its implementing task. A
        // declined or never-received void lands FAILED from the VOID states.
        assertThat(twoStep.get(PaymentAttemptStatus.AUTHORIZED))
                .as("AUTHORIZED exits to the capture and the void, never FAILED directly")
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.CAPTURE_DISPATCHED,
                        PaymentAttemptStatus.VOID_DISPATCHED);
        // The capture stages may enter the void: the declined-capture redirect releases
        // the promise instead of leaving it to lapse (P7-TSK-004).
        assertThat(twoStep.get(PaymentAttemptStatus.CAPTURE_DISPATCHED))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.CAPTURED,
                        PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.CAPTURE_UNKNOWN,
                        PaymentAttemptStatus.VOID_DISPATCHED);
        assertThat(twoStep.get(PaymentAttemptStatus.CAPTURE_UNKNOWN))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.CAPTURED, PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.VOID_DISPATCHED);
        assertThat(twoStep.get(PaymentAttemptStatus.VOID_DISPATCHED))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.VOIDED, PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.VOID_UNKNOWN);
        assertThat(twoStep.get(PaymentAttemptStatus.VOID_UNKNOWN))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.VOIDED, PaymentAttemptStatus.FAILED);
        assertThat(twoStep.get(PaymentAttemptStatus.VOIDED)).isEmpty();

        // CAPTURED is the attempt's stable state: NO outgoing edge. Refunds reference the
        // captured attempt and never transition it (ADR-0045) — the SUCCEEDED pin's argument,
        // and the assertion a machine-derived sweep cannot make.
        assertThat(twoStep.get(PaymentAttemptStatus.CAPTURED))
                .as("CAPTURED is stable with no outgoing edge (ADR-0045)")
                .isEmpty();
        assertThat(twoStep.get(PaymentAttemptStatus.FAILED)).isEmpty();
        assertThat(twoStep.keySet())
                .as("the two-step vocabulary is Phase 5's seven plus the void trio")
                .hasSize(10);

        // Nothing transitions TO AUTH_DISPATCHED in ANY machine — birth is the only door.
        for (InteractionModel model : InteractionModel.values()) {
            for (PaymentAttemptStatus from : PaymentAttemptStatus.values()) {
                assertThat(model.permits(from, PaymentAttemptStatus.AUTH_DISPATCHED))
                        .as("%s: %s -> AUTH_DISPATCHED must not exist", model, from)
                        .isFalse();
            }
        }

        // Terminal is the structural union across the machines, so CAPTURED is IN the set —
        // captured is not settled (INV-SET-01) — and EXECUTED joins it (P7-TSK-002): final
        // on its rails, and in the one-live index's predicate with the other two.
        assertThat(EnumSet.allOf(PaymentAttemptStatus.class).stream()
                        .filter(PaymentAttemptStatus::isTerminal))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.CAPTURED,
                        PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.EXECUTED,
                        PaymentAttemptStatus.VOIDED);

        // The deliberately-absent states asserted absent: exactly these eleven, the push four
        // appended after the two-step seven so every generated list only extends — no VOIDED
        // (P7-TSK-004), no REQUIRES_ACTION, no CLEARING/SETTLED (Phase 8), no retry states.
        assertThat(PaymentAttemptStatus.values())
                .containsExactly(
                        PaymentAttemptStatus.AUTH_DISPATCHED,
                        PaymentAttemptStatus.AUTH_UNKNOWN,
                        PaymentAttemptStatus.AUTHORIZED,
                        PaymentAttemptStatus.CAPTURE_DISPATCHED,
                        PaymentAttemptStatus.CAPTURE_UNKNOWN,
                        PaymentAttemptStatus.CAPTURED,
                        PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.AWAITING_PAYER,
                        PaymentAttemptStatus.EXECUTION_DISPATCHED,
                        PaymentAttemptStatus.EXECUTION_UNKNOWN,
                        PaymentAttemptStatus.EXECUTED,
                        PaymentAttemptStatus.VOID_DISPATCHED,
                        PaymentAttemptStatus.VOID_UNKNOWN,
                        PaymentAttemptStatus.VOIDED);
    }

    @Test
    @DisplayName("every transition in the cross-product behaves as the machine declares")
    void everyTransitionIsEnforced() {
        for (PaymentAttemptStatus from : InteractionModel.TWO_STEP.statuses()) {
            for (Map.Entry<PaymentAttemptStatus, UnaryOperator<PaymentAttempt>> target :
                    transitionDoors().entrySet()) {
                PaymentAttempt attempt = attemptAt(from);
                PaymentAttemptStatus to = target.getKey();
                if (InteractionModel.TWO_STEP.permits(from, to)) {
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
                EnumSet.of(PaymentAttemptStatus.CAPTURED, PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.VOIDED)) {
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
                PaymentAttempt.create(IDS, CLOCK, PaymentIntentId.next(IDS), SimulatedCardPspAdapter.RAIL.id(), reference);
        assertThat(attempt.status()).isEqualTo(PaymentAttemptStatus.AUTH_DISPATCHED);
        assertThat(attempt.authorizationReference()).isEqualTo(reference);
        assertThat(attempt.id()).isNotNull();
        assertThat(attempt.createdAt()).isNotNull();
        assertThat(attempt.authorizedAmount()).isNull();
        assertThat(attempt.failureReason()).isNull();

        // No two-step attempt without its dispatch reference — the dispatch commits before
        // the provider is asked (ADR-0046), and the reference is what a sweeper queries by
        // (INV-PAY-04). Since P7-TSK-002 the rule is the model's (only the two-step model
        // carries one at all), so the refusal is the coherence IllegalArgumentException.
        assertThatThrownBy(() ->
                        PaymentAttempt.create(IDS, CLOCK, PaymentIntentId.next(IDS), SimulatedCardPspAdapter.RAIL.id(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two-step");
        assertThatThrownBy(() -> PaymentAttempt.create(IDS, CLOCK, null, SimulatedCardPspAdapter.RAIL.id(), idem()))
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
        for (PaymentAttemptStatus status : InteractionModel.TWO_STEP.statuses()) {
            assertThat(attemptAt(status).status()).isEqualTo(status);
        }

        // FAILED without its mapped reason — the reason is THIS row's fact (INV-PAY-03).
        assertThatThrownBy(() -> rehydrated(
                        null, null, null, null, null, null, null, null, PaymentAttemptStatus.FAILED))
                .as("FAILED without a reason")
                .isInstanceOf(IllegalArgumentException.class);
        // A reason on a live row — failure data on a row that has not failed.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null, null, null,
                        PaymentFailureReason.DECLINED, PaymentAttemptStatus.AUTHORIZED))
                .as("a reason outside FAILED")
                .isInstanceOf(IllegalArgumentException.class);

        // The issuer's promise split in half — reference without amount, amount without
        // reference: one fact, refused in both directions.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), null, null, null, null, null, null,
                        PaymentAttemptStatus.AUTHORIZED))
                .as("a provider reference without its amount")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        null, null, AMOUNT, null, null, null, null, null, PaymentAttemptStatus.AUTHORIZED))
                .as("an authorized amount without its reference")
                .isInstanceOf(IllegalArgumentException.class);

        // Pre-auth states carry nothing beyond birth; AUTHORIZED has no capture reference yet;
        // capture-stage states require one.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null, null, null, null,
                        PaymentAttemptStatus.AUTH_DISPATCHED))
                .as("AUTH_DISPATCHED holding an authorization")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, null, null, null, null, null,
                        PaymentAttemptStatus.AUTHORIZED))
                .as("AUTHORIZED already holding a capture reference")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null, null, null, null,
                        PaymentAttemptStatus.CAPTURE_DISPATCHED))
                .as("CAPTURE_DISPATCHED without the capture's idempotency reference")
                .isInstanceOf(IllegalArgumentException.class);

        // The unreachable FAILED shape: the issuer's promise held, no capture and no
        // void dispatched. AUTHORIZED -> FAILED is still not an edge - abandoning a promise
        // is the void's job (P7-TSK-004) - so no path writes this row — rehydrate refuses
        // it as corruption rather than trusting the database.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null, null, null,
                        PaymentFailureReason.DECLINED, PaymentAttemptStatus.FAILED))
                .as("FAILED holding the promise but no capture dispatch — no path writes this")
                .isInstanceOf(IllegalArgumentException.class);

        // CAPTURED must hold the capture pair; a captured amount anywhere else is incoherent.
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, null, null, null, null, null,
                        PaymentAttemptStatus.CAPTURED))
                .as("CAPTURED without the capture pair")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, capRef(), AMOUNT, null, null, null,
                        PaymentAttemptStatus.CAPTURE_DISPATCHED))
                .as("a captured amount before CAPTURED")
                .isInstanceOf(IllegalArgumentException.class);

        // The stored over-capture — the schema bypass INV-PAY-05's constructor placement
        // exists for: caught on read-back ALONE if the door check were ever weakened.
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, capRef(), Money.ofMinorUnits(98_77, EUR), null, null,
                        null, PaymentAttemptStatus.CAPTURED))
                .as("a stored captured amount exceeding the authorization")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9877")
                .hasMessageNotContaining("98.77");

        // A stored non-positive promise.
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), Money.ofMinorUnits(-98_76, EUR), null, null, null, null, null,
                        PaymentAttemptStatus.AUTHORIZED))
                .as("a stored non-positive authorized amount")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9876");

        // The void's shapes (P7-TSK-004): each void state requires the promise and the
        // void's idempotency reference, the provider's acknowledgement exists exactly when
        // VOIDED, and nothing voided ever holds a captured pair (INV-REV-03).
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null, null, null, null,
                        PaymentAttemptStatus.VOID_DISPATCHED))
                .as("VOID_DISPATCHED without the void's idempotency reference")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null, voidRef(),
                        new ProviderReference("psp-void-live"), null,
                        PaymentAttemptStatus.VOID_UNKNOWN))
                .as("a void acknowledgement outside VOIDED")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        null, authRef(), AMOUNT, null, null, voidRef(), null, null,
                        PaymentAttemptStatus.VOIDED))
                .as("VOIDED without the provider's acknowledgement")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, capRef(), AMOUNT, voidRef(), null, null,
                        PaymentAttemptStatus.VOID_DISPATCHED))
                .as("a captured pair on a void-stage row")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        idem(), authRef(), AMOUNT, null, null, voidRef(), null, null,
                        PaymentAttemptStatus.CAPTURE_DISPATCHED))
                .as("a void reference on a capture-stage row")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        null, null, null, null, null, voidRef(), null,
                        PaymentFailureReason.DECLINED, PaymentAttemptStatus.FAILED))
                .as("a FAILED void without the promise it tried to release")
                .isInstanceOf(IllegalArgumentException.class);
        // And the legal third FAILED shape constructs: the declined void, the capture
        // reference riding along from the declined-capture redirect (P7-TSK-004).
        assertThat(rehydrated(
                        idem(), authRef(), AMOUNT, null, null, voidRef(), null,
                        PaymentFailureReason.DECLINED, PaymentAttemptStatus.FAILED)
                        .status())
                .isEqualTo(PaymentAttemptStatus.FAILED);

        // Absent identity facts are refused whatever the status.
        assertThatThrownBy(() -> PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), null, SimulatedCardPspAdapter.RAIL.id(), InteractionModel.TWO_STEP, idem(), null, null, null, null, null, null, null,
                        null, PaymentAttemptStatus.AUTH_DISPATCHED, Instant.now(CLOCK),
                null, null, null, null, null))
                .as("no intent reference")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), PaymentIntentId.next(IDS), SimulatedCardPspAdapter.RAIL.id(), InteractionModel.TWO_STEP, null, null, null,
                        null, null, null, null, null, null, PaymentAttemptStatus.AUTH_DISPATCHED,
                        Instant.now(CLOCK),
                null, null, null, null, null))
                .as("no authorization idempotency reference on a two-step row - the"
                        + " model's coherence rule since P7-TSK-002")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two-step");
        assertThatThrownBy(() -> PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), PaymentIntentId.next(IDS), SimulatedCardPspAdapter.RAIL.id(), InteractionModel.TWO_STEP, idem(), null,
                        null, null, null, null, null, null, null, null, Instant.now(CLOCK),
                null, null, null, null, null))
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
                PaymentAttempt.create(IDS, CLOCK, PaymentIntentId.next(IDS), SimulatedCardPspAdapter.RAIL.id(), idem());
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
                        + " 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED',"
                        + " 'AWAITING_PAYER', 'EXECUTION_DISPATCHED', 'EXECUTION_UNKNOWN',"
                        + " 'EXECUTED', 'VOID_DISPATCHED', 'VOID_UNKNOWN', 'VOIDED'");
        // CAPTURED is IN the terminal list — the enum's recorded decision - EXECUTED joined it at
        // P7-TSK-002, VOIDED at P7-TSK-004: appended states keep every earlier fragment a
        // prefix, which is what lets V003, V011 and V012 stay reconciled as applied history.
        assertThat(PaymentAttemptStatus.sqlTerminalValueList())
                .isEqualTo("'CAPTURED', 'FAILED', 'EXECUTED', 'VOIDED'");
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
                        attempt -> attempt.fail(PaymentFailureReason.DECLINED),
                PaymentAttemptStatus.VOID_DISPATCHED,
                        attempt -> attempt.dispatchVoid(idem()),
                PaymentAttemptStatus.VOID_UNKNOWN, PaymentAttempt::voidOutcomeUnknown,
                PaymentAttemptStatus.VOIDED,
                        attempt -> attempt.voided(new ProviderReference("psp-void-1")));
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
                    rehydrated(null, null, null, null, null, null, null, null, status);
            case AUTHORIZED ->
                    rehydrated(null, authRef(), AMOUNT, null, null, null, null, null, status);
            case CAPTURE_DISPATCHED, CAPTURE_UNKNOWN ->
                    rehydrated(idem(), authRef(), AMOUNT, null, null, null, null, null, status);
            case CAPTURED ->
                    rehydrated(
                            idem(), authRef(), AMOUNT, capRef(), AMOUNT, null, null, null,
                            status);
            // The void shapes (P7-TSK-004): the promise plus OUR void reference; the
            // acknowledgement exactly when VOIDED. The redirect's capture-reference-bearing
            // variant is proven by the doors sweep out of the capture-stage fixtures.
            case VOID_DISPATCHED, VOID_UNKNOWN ->
                    rehydrated(null, authRef(), AMOUNT, null, null, voidRef(), null, null,
                            status);
            case VOIDED ->
                    rehydrated(
                            null, authRef(), AMOUNT, null, null, voidRef(),
                            new ProviderReference("psp-void-evidence"), null, status);
            case FAILED -> rehydrated(
                    null, null, null, null, null, null, null,
                    PaymentFailureReason.DECLINED, status);
            // The push and book states have no two-step shape at all: this helper serves the
            // TWO_STEP sweeps, and their own fixtures rehydrate with their model directly.
            default -> throw new IllegalArgumentException(
                    status + " is not a two-step state");
        };
    }

    private static ProviderIdempotencyReference voidRef() {
        return new ProviderIdempotencyReference("void-" + UUID.randomUUID());
    }

    private static PaymentAttempt rehydrated(
            ProviderIdempotencyReference captureReference,
            ProviderReference authorizationProviderReference,
            Money authorizedAmount,
            ProviderReference captureProviderReference,
            Money capturedAmount,
            ProviderIdempotencyReference voidReference,
            ProviderReference voidProviderReference,
            PaymentFailureReason failureReason,
            PaymentAttemptStatus status) {
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS),
                PaymentIntentId.next(IDS),
                SimulatedCardPspAdapter.RAIL.id(),
                InteractionModel.TWO_STEP,
                idem(),
                captureReference,
                authorizationProviderReference,
                authorizedAmount,
                captureProviderReference,
                capturedAmount,
                voidReference,
                voidProviderReference,
                failureReason,
                status,
                Instant.now(CLOCK),
                null, null, null, null, null);
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

    @Test
    @DisplayName("the rail is a birth fact: stamped at creation, carried by every transition,"
            + " refused absent (P7-TSK-001, ADR-0059)")
    void theRailIsABirthFact() {
        PaymentAttempt born =
                PaymentAttempt.create(
                        IDS, CLOCK, PaymentIntentId.next(IDS),
                        SimulatedCardPspAdapter.RAIL.id(), idem());
        assertThat(born.rail()).isEqualTo(SimulatedCardPspAdapter.RAIL.id());
        assertThat(born.authorize(new ProviderReference("psp-rail-1"), AMOUNT).rail())
                .as("a transition carries the birth fact, never re-decides it")
                .isEqualTo(born.rail());

        assertThatThrownBy(() ->
                        PaymentAttempt.create(
                                IDS, CLOCK, PaymentIntentId.next(IDS), null, idem()))
                .as("no rail at birth")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rail");
        assertThatThrownBy(() -> PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), PaymentIntentId.next(IDS), null, InteractionModel.TWO_STEP, idem(),
                        null, null, null, null, null, null, null, null,
                        PaymentAttemptStatus.AUTH_DISPATCHED, Instant.now(CLOCK),
                null, null, null, null, null))
                .as("no rail on read-back: a corrupt row is refused ahead of the schema")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rail");
    }

    // ------------------------------------------------- the push pay-in (P7-TSK-009)

    /** `MUTATION_TESTING.md` §2 names this method; top-level so the register guard's
     * simple-name sweep resolves it (the `P7-TSK-007` find, met at authoring time). */
    @Test
    @DisplayName("the push machine's inbound edge is pinned: the payer's execution concludes"
            + " AWAITING_PAYER directly, and nothing else changed (P7-TSK-009)")
    void theInboundEdgeIsPinned() {
        var push = InteractionModel.PUSH.edges();
        assertThat(push.get(PaymentAttemptStatus.AWAITING_PAYER))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.EXECUTION_DISPATCHED,
                        PaymentAttemptStatus.FAILED,
                        PaymentAttemptStatus.EXECUTED);
        assertThat(push.get(PaymentAttemptStatus.EXECUTION_DISPATCHED))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.EXECUTION_UNKNOWN,
                        PaymentAttemptStatus.EXECUTED,
                        PaymentAttemptStatus.FAILED);
        assertThat(push.get(PaymentAttemptStatus.EXECUTION_UNKNOWN))
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.EXECUTED, PaymentAttemptStatus.FAILED);
        assertThat(push.get(PaymentAttemptStatus.EXECUTED)).isEmpty();
        assertThat(push.get(PaymentAttemptStatus.FAILED)).isEmpty();
    }

    @Test
    @DisplayName("a push birth mints OUR reference and its permit, truncated to the column's"
            + " resolution (INV-PAY-04; the P7-TSK-004 clock-precision class, prevented)")
    void aPushBirthMintsItsReference() {
        PaymentAttempt born = pushBorn();
        assertThat(born.status()).isEqualTo(PaymentAttemptStatus.AWAITING_PAYER);
        assertThat(born.interactionModel()).isEqualTo(InteractionModel.PUSH);
        assertThat(born.endToEndReference().value()).matches("[a-f0-9]{32}");
        assertThat(born.endToEndReference().value().length())
                .isLessThanOrEqualTo(EndToEndReference.MAX_LENGTH);
        assertThat(born.lastDispatchedAt()).isEqualTo(born.createdAt());
        assertThat(born.lastDispatchedAt())
                .isEqualTo(born.lastDispatchedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(born.authorizationHandle()).isEmpty();
        assertThat(born.authorizationReference())
                .as("no two-step fact rides the push row")
                .isNull();
    }

    @Test
    @DisplayName("the initiation opens once: the handle stores on the waiting row and never"
            + " twice, never elsewhere (P7-TSK-009)")
    void theInitiationOpensOnce() {
        PaymentAttempt born = pushBorn();
        PaymentAttempt opened =
                born.openInitiation(
                        com.finapp.sharedkernel.security.Sensitive.of("https://psp/auth/1"));
        assertThat(opened.status()).isEqualTo(PaymentAttemptStatus.AWAITING_PAYER);
        assertThat(opened.authorizationHandle()).isPresent();
        assertThatThrownBy(() ->
                        opened.openInitiation(
                                com.finapp.sharedkernel.security.Sensitive.of("https://psp/2")))
                .as("one initiation, one handle - the scheme's dedupe premise")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        opened.execute(new ProviderReference("sch-x"), java.util.Optional.empty())
                                .openInitiation(
                                        com.finapp.sharedkernel.security.Sensitive.of("h")))
                .as("a concluded row opens nothing")
                .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
    }

    @Test
    @DisplayName("the execution carries the scheme's pair, and only it does - coherence both"
            + " ways on rehydrate too (Phase 8's keys)")
    void theExecutionCarriesTheSchemesPair() {
        PaymentAttempt executed =
                pushBorn().execute(
                        new ProviderReference("sch-exec-1"), java.util.Optional.of("C1"));
        assertThat(executed.status()).isEqualTo(PaymentAttemptStatus.EXECUTED);
        assertThat(executed.schemeReference()).isPresent();
        assertThat(executed.settlementCycle()).contains("C1");
        // EXECUTED without the scheme's reference, and the reference elsewhere.
        assertThatThrownBy(() -> pushRehydrated(PaymentAttemptStatus.EXECUTED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly when");
        assertThatThrownBy(() ->
                        pushRehydrated(
                                PaymentAttemptStatus.AWAITING_PAYER, "sch-elsewhere", null))
                .isInstanceOf(IllegalArgumentException.class);
        // A settlement cycle rides only beside the reference.
        assertThatThrownBy(() ->
                        pushRehydrated(PaymentAttemptStatus.AWAITING_PAYER, null, "C1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settlement cycle");
        // And the birth facts are required: a push row without its reference or permit.
        assertThatThrownBy(() -> PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), PaymentIntentId.next(IDS),
                        RailId.of("instant"), InteractionModel.PUSH,
                        null, null, null, null, null, null, null, null, null,
                        PaymentAttemptStatus.AWAITING_PAYER, Instant.now(CLOCK),
                        null, null, null, null, Instant.now(CLOCK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end-to-end reference");
    }

    @Test
    @DisplayName("the initiation permit only moves forward, and only on the waiting row"
            + " (ADR-0062 section 3 adapted)")
    void theInitiationPermitOnlyMovesForward() {
        PaymentAttempt born = pushBorn();
        assertThatThrownBy(() -> born.withInitiationPermit(born.lastDispatchedAt().minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        PaymentAttempt renewed = born.withInitiationPermit(born.lastDispatchedAt().plusSeconds(5));
        assertThat(renewed.lastDispatchedAt())
                .isEqualTo(born.lastDispatchedAt().plusSeconds(5));
        PaymentAttempt done =
                born.execute(new ProviderReference("sch-perm-1"), java.util.Optional.empty());
        assertThatThrownBy(() -> done.withInitiationPermit(Instant.now(CLOCK).plusSeconds(60)))
                .as("a concluded initiation is never contacted again")
                .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
    }

    private static PaymentAttempt pushBorn() {
        return PaymentAttempt.createPush(
                IDS, CLOCK, PaymentIntentId.next(IDS), RailId.of("instant"));
    }

    @Test
    @DisplayName("a book birth is EXECUTED with nothing to reference: no wire exists, so"
            + " the attempt exists exactly when its posting commits (P7-TSK-011)")
    void aBookBirthIsExecuted() {
        PaymentAttempt born =
                PaymentAttempt.createBook(
                        IDS, CLOCK, PaymentIntentId.next(IDS), RailId.of("book"));
        assertThat(born.status()).isEqualTo(PaymentAttemptStatus.EXECUTED);
        assertThat(born.interactionModel()).isEqualTo(InteractionModel.BOOK);
        assertThat(born.authorizationReference())
                .as("no idempotency reference is minted: no external dedupe exists to"
                        + " present it to - the posting key is the once-arbiter")
                .isNull();
        assertThat(born.endToEndReference()).isNull();
        assertThat(born.schemeReference()).isEmpty();
        assertThat(born.lastDispatchedAt()).isNull();
        // The book machine is born terminal: no door moves it anywhere.
        assertThat(InteractionModel.BOOK.edges().get(PaymentAttemptStatus.EXECUTED)).isEmpty();
        assertThat(InteractionModel.BOOK.edges().get(PaymentAttemptStatus.FAILED)).isEmpty();
    }

    private static PaymentAttempt pushRehydrated(
            PaymentAttemptStatus status, String schemeReference, String cycle) {
        Instant born = Instant.now(CLOCK);
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS),
                PaymentIntentId.next(IDS),
                RailId.of("instant"),
                InteractionModel.PUSH,
                null, null, null, null, null, null, null, null,
                status == PaymentAttemptStatus.FAILED
                        ? PaymentFailureReason.DECLINED
                        : null,
                status,
                born,
                new EndToEndReference(UUID.randomUUID().toString().replace("-", "")),
                null,
                schemeReference == null ? null : new ProviderReference(schemeReference),
                cycle,
                born);
    }
}
