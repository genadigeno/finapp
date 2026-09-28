package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.HoldId;
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
 * The refund machine and {@code INV-PAY-05}'s domain half, held at the aggregate
 * ({@code P5-TSK-007}, ADR-0045, ADR-0048).
 *
 * <p>The bound is judged at creation with the sibling sum as an explicit argument — the
 * signature's contract is that the sum was read under the command's lock on the attempt row
 * ({@code P5-TSK-015}); the concurrent half of the same bound is the schema trigger's
 * ({@code P5-TSK-008}). This suite proves the aggregate's half: the exact-remaining refund
 * legal, one minor unit past it refused, and the refusal naming no amount ({@code INV-AUD-02}).
 */
@DisplayName("Refund (P5-TSK-007)")
class RefundTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Money CAPTURED = Money.ofMinorUnits(98_76, EUR);
    private static final Money AMOUNT = Money.ofMinorUnits(20_00, EUR);

    @Test
    @DisplayName("the machine is pinned: four states, five edges, one unknown")
    void theMachineIsPinned() {
        assertThat(RefundStatus.DISPATCHED.permittedTransitions())
                .containsExactlyInAnyOrder(
                        RefundStatus.COMPLETED, RefundStatus.FAILED, RefundStatus.UNKNOWN);
        assertThat(RefundStatus.UNKNOWN.permittedTransitions())
                .containsExactlyInAnyOrder(RefundStatus.COMPLETED, RefundStatus.FAILED);
        assertThat(RefundStatus.COMPLETED.permittedTransitions()).isEmpty();
        assertThat(RefundStatus.FAILED.permittedTransitions()).isEmpty();

        // Nothing transitions TO DISPATCHED — birth is the only door, and an UNKNOWN never
        // "re-dispatches": the reference already exists, the provider is queried by it.
        for (RefundStatus from : RefundStatus.values()) {
            assertThat(from.canTransitionTo(RefundStatus.DISPATCHED))
                    .as("%s -> DISPATCHED must not exist", from)
                    .isFalse();
        }

        assertThat(EnumSet.allOf(RefundStatus.class).stream().filter(RefundStatus::isTerminal))
                .containsExactlyInAnyOrder(RefundStatus.COMPLETED, RefundStatus.FAILED);

        // The deliberately-absent states asserted absent: exactly these four — no
        // REQUESTED/approval state (creation IS the privileged act), no aggregate
        // PARTIALLY_REFUNDED (derived by views, stored nowhere, ADR-0045).
        assertThat(RefundStatus.values())
                .containsExactly(
                        RefundStatus.DISPATCHED,
                        RefundStatus.UNKNOWN,
                        RefundStatus.COMPLETED,
                        RefundStatus.FAILED);
    }

    @Test
    @DisplayName("every transition in the cross-product behaves as the machine declares")
    void everyTransitionIsEnforced() {
        for (RefundStatus from : RefundStatus.values()) {
            for (Map.Entry<RefundStatus, UnaryOperator<Refund>> target :
                    transitionDoors().entrySet()) {
                Refund refund = refundAt(from);
                RefundStatus to = target.getKey();
                if (from.canTransitionTo(to)) {
                    assertThat(target.getValue().apply(refund).status())
                            .as("%s -> %s is permitted by the machine", from, to)
                            .isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> target.getValue().apply(refund))
                            .as("%s -> %s must be refused by the aggregate itself", from, to)
                            .isInstanceOf(IllegalRefundTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("both terminals refuse every door, swept separately by name (INV-LIFE-04)")
    void theTerminalsRefuseEveryDoor() {
        for (RefundStatus terminal : EnumSet.of(RefundStatus.COMPLETED, RefundStatus.FAILED)) {
            for (Map.Entry<RefundStatus, UnaryOperator<Refund>> target :
                    transitionDoors().entrySet()) {
                Refund refund = refundAt(terminal);
                assertThatThrownBy(() -> target.getValue().apply(refund))
                        .as("%s must refuse a move to %s", terminal, target.getKey())
                        .isInstanceOf(IllegalRefundTransitionException.class);
            }
        }
    }

    @Test
    @DisplayName("creation is bounded by the capture (INV-PAY-05), refused naming no amount")
    void creationIsBoundedByTheCapture() {
        PaymentAttempt captured = capturedAttempt();

        // Born DISPATCHED with the hold placed and the idempotency reference minted — the
        // ADR-0046 shape: both commit before the provider is asked.
        HoldId hold = HoldId.next(IDS);
        ProviderIdempotencyReference reference = idem();
        Refund refund = Refund.create(
                IDS, CLOCK, captured, AMOUNT, Money.zero(EUR), "customer complaint upheld",
                hold, reference);
        assertThat(refund.status()).isEqualTo(RefundStatus.DISPATCHED);
        assertThat(refund.attemptId()).isEqualTo(captured.id());
        assertThat(refund.holdReference()).isEqualTo(hold);
        assertThat(refund.providerIdempotencyReference()).isEqualTo(reference);
        assertThat(refund.providerReference()).isNull();

        // Refund to the penny is legal — the bound is <=: 20.00 against 78.76 already gone
        // leaves exactly zero.
        assertThat(Refund.create(
                                IDS, CLOCK, captured, AMOUNT, Money.ofMinorUnits(78_76, EUR),
                                "final partial refund", HoldId.next(IDS), idem())
                        .status())
                .isEqualTo(RefundStatus.DISPATCHED);

        // One minor unit past the capture creates money (INV-REV-02's reasoning at the payment
        // boundary). The needle: the refusal names the invariant and the currency, never the
        // captured amount, the refund amount or the sum (INV-AUD-02).
        assertThatThrownBy(() -> Refund.create(
                        IDS, CLOCK, captured, AMOUNT, Money.ofMinorUnits(78_77, EUR),
                        "one unit too far", HoldId.next(IDS), idem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9876")
                .hasMessageNotContaining("98.76")
                .hasMessageNotContaining("7877")
                .hasMessageNotContaining("78.77")
                .hasMessageNotContaining("2000");

        // Only a CAPTURED attempt can be refunded — an authorized-but-uncaptured attempt has
        // taken nothing to return.
        assertThatThrownBy(() -> Refund.create(
                        IDS, CLOCK, authorizedAttempt(), AMOUNT, Money.zero(EUR),
                        "nothing captured yet", HoldId.next(IDS), idem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CAPTURED");

        // Currency discipline on both money arguments.
        assertThatThrownBy(() -> Refund.create(
                        IDS, CLOCK, captured, Money.ofMinorUnits(1_00, USD), Money.zero(EUR),
                        "wrong currency", HoldId.next(IDS), idem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("EUR");
        assertThatThrownBy(() -> Refund.create(
                        IDS, CLOCK, captured, AMOUNT, Money.zero(USD),
                        "wrong sum currency", HoldId.next(IDS), idem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD");

        // A negative sibling sum is an upstream query bug, not a wider budget.
        assertThatThrownBy(() -> Refund.create(
                        IDS, CLOCK, captured, AMOUNT, Money.ofMinorUnits(-1_00, EUR),
                        "negative sum", HoldId.next(IDS), idem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("100")
                .hasMessageNotContaining("1.00");

        // A refund of nothing asserts nothing; the reason is part of the privileged act.
        assertThatThrownBy(() -> Refund.create(
                        IDS, CLOCK, captured, Money.ofMinorUnits(0, EUR), Money.zero(EUR),
                        "zero refund", HoldId.next(IDS), idem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR");
        assertThatThrownBy(() -> Refund.create(
                        IDS, CLOCK, captured, AMOUNT, Money.zero(EUR), "   ",
                        HoldId.next(IDS), idem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");

        // The bound is the schema's CHECK's one definition (P5-TSK-008): exactly the bound
        // accepted, one character past it refused.
        assertThat(Refund.create(
                                IDS, CLOCK, captured, AMOUNT, Money.zero(EUR),
                                "r".repeat(Refund.MAX_REASON_LENGTH), HoldId.next(IDS), idem())
                        .reason())
                .hasSize(Refund.MAX_REASON_LENGTH);
        assertThatThrownBy(() -> Refund.create(
                        IDS, CLOCK, captured, AMOUNT, Money.zero(EUR),
                        "r".repeat(Refund.MAX_REASON_LENGTH + 1), HoldId.next(IDS), idem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(Refund.MAX_REASON_LENGTH));
    }

    @Test
    @DisplayName("a return is bounded by the execution (INV-PAY-05's push half): EXECUTED"
            + " only, the wire's reference bound judged at mint, no amount in a refusal")
    void aReturnIsBoundedByTheExecution() {
        PaymentAttempt executed = executedPushAttempt();

        Refund returned = Refund.createReturn(
                IDS, CLOCK, executed, CAPTURED, AMOUNT, Money.zero(EUR),
                "customer complaint upheld", HoldId.next(IDS), returnIdem());
        assertThat(returned.status())
                .as("a return is the same four-state machine, born DISPATCHED")
                .isEqualTo(RefundStatus.DISPATCHED);
        assertThat(returned.attemptId()).isEqualTo(executed.id());

        // The eligible state is the push machine's own terminal - EXECUTED, never CAPTURED:
        // a card attempt is refused by MODEL, a waiting pay-in by STATE.
        assertThatThrownBy(() -> Refund.createReturn(
                        IDS, CLOCK, capturedAttempt(), CAPTURED, AMOUNT, Money.zero(EUR),
                        "wrong machine", HoldId.next(IDS), returnIdem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTED push");
        assertThatThrownBy(() -> Refund.createReturn(
                        IDS, CLOCK, awaitingPushAttempt(), CAPTURED, AMOUNT, Money.zero(EUR),
                        "nothing executed yet", HoldId.next(IDS), returnIdem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTED");

        // The reference rides the scheme wire as the end-to-end reference: the ISO 20022
        // bound is judged AT MINT, not at send (P7-TSK-010).
        assertThatThrownBy(() -> Refund.createReturn(
                        IDS, CLOCK, executed, CAPTURED, AMOUNT, Money.zero(EUR),
                        "reference too long", HoldId.next(IDS),
                        new ProviderIdempotencyReference(
                                "r".repeat(EndToEndReference.MAX_LENGTH + 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(EndToEndReference.MAX_LENGTH));

        // The bound and the currency discipline, against the EXECUTED amount - and the
        // needle: the refusal names the invariant and the currency, never an amount.
        assertThatThrownBy(() -> Refund.createReturn(
                        IDS, CLOCK, executed, CAPTURED, AMOUNT,
                        Money.ofMinorUnits(78_77, EUR), "one unit too far",
                        HoldId.next(IDS), returnIdem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9876")
                .hasMessageNotContaining("7877")
                .hasMessageNotContaining("2000");
        assertThatThrownBy(() -> Refund.createReturn(
                        IDS, CLOCK, executed, CAPTURED, Money.ofMinorUnits(1_00, USD),
                        Money.zero(EUR), "wrong currency", HoldId.next(IDS), returnIdem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("EUR");
        assertThatThrownBy(() -> Refund.createReturn(
                        IDS, CLOCK, executed, CAPTURED, AMOUNT,
                        Money.ofMinorUnits(-1_00, EUR), "negative sum",
                        HoldId.next(IDS), returnIdem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("100");
    }

    @Test
    @DisplayName("a book refund is bounded by the execution exactly as the return is"
            + " (INV-PAY-05): EXECUTED book subjects only, no wire bound to judge")
    void aBookRefundIsBoundedByTheExecution() {
        PaymentAttempt executed = executedBookAttempt();

        Refund refund = Refund.createBookRefund(
                IDS, CLOCK, executed, CAPTURED, AMOUNT, Money.zero(EUR),
                "customer complaint upheld", HoldId.next(IDS),
                new ProviderIdempotencyReference("bkr-" + UUID.randomUUID()));
        assertThat(refund.status()).isEqualTo(RefundStatus.DISPATCHED);
        assertThat(refund.attemptId()).isEqualTo(executed.id());

        // The wrong machine and the wrong state refuse: a push subject is refused by
        // MODEL (the return factory owns it), and only EXECUTED has anything to return.
        assertThatThrownBy(() -> Refund.createBookRefund(
                        IDS, CLOCK, executedPushAttempt(), CAPTURED, AMOUNT, Money.zero(EUR),
                        "wrong machine", HoldId.next(IDS),
                        new ProviderIdempotencyReference("bkr-" + UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTED book");
        assertThatThrownBy(() -> Refund.createBookRefund(
                        IDS, CLOCK, failedBookAttempt(), CAPTURED, AMOUNT, Money.zero(EUR),
                        "nothing executed", HoldId.next(IDS),
                        new ProviderIdempotencyReference("bkr-" + UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTED");

        // The shared bound (the return's own checks, one definition): one unit past the
        // executed amount refuses, naming the invariant and the currency, never a value.
        assertThatThrownBy(() -> Refund.createBookRefund(
                        IDS, CLOCK, executed, CAPTURED, AMOUNT,
                        Money.ofMinorUnits(78_77, EUR), "one unit too far",
                        HoldId.next(IDS),
                        new ProviderIdempotencyReference("bkr-" + UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9876")
                .hasMessageNotContaining("7877");
    }

    @Test
    @DisplayName("rehydrate routes through the one constructor and refuses the corrupt row")
    void rehydrateRefusesTheCorruptRow() {
        for (RefundStatus status : RefundStatus.values()) {
            assertThat(refundAt(status).status()).isEqualTo(status);
        }

        // The provider's reference <=> COMPLETED, both directions: a COMPLETED refund that
        // cannot name the provider's refund is not evidence Phase 8 can reconcile, and a
        // reference on a live row is an outcome nobody committed.
        assertThatThrownBy(() -> rehydrated(null, RefundStatus.COMPLETED))
                .as("COMPLETED without the provider's reference")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrated(
                        new ProviderReference("psp-ref-1"), RefundStatus.DISPATCHED))
                .as("a provider reference before an outcome")
                .isInstanceOf(IllegalArgumentException.class);

        // A stored non-positive amount — the schema bypass — refused with the amount-free
        // message, because it is the same constructor (INV-AUD-02).
        assertThatThrownBy(() -> Refund.rehydrate(
                        RefundId.next(IDS), PaymentAttemptId.next(IDS),
                        Money.ofMinorUnits(-98_76, EUR), "stored corruption", HoldId.next(IDS),
                        idem(), null, RefundStatus.DISPATCHED, Instant.now(CLOCK)))
                .as("a stored non-positive amount")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9876")
                .hasMessageNotContaining("98.76");

        // A blank reason read back is as refused as a blank reason at birth.
        assertThatThrownBy(() -> Refund.rehydrate(
                        RefundId.next(IDS), PaymentAttemptId.next(IDS), AMOUNT, " ",
                        HoldId.next(IDS), idem(), null, RefundStatus.DISPATCHED,
                        Instant.now(CLOCK)))
                .as("a blank reason")
                .isInstanceOf(IllegalArgumentException.class);

        // Absent facts are refused whatever the status — no hold, no idempotency reference,
        // no status: not a refund, whoever wrote it.
        assertThatThrownBy(() -> Refund.rehydrate(
                        RefundId.next(IDS), PaymentAttemptId.next(IDS), AMOUNT, "no hold",
                        null, idem(), null, RefundStatus.DISPATCHED, Instant.now(CLOCK)))
                .as("no hold reference")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Refund.rehydrate(
                        RefundId.next(IDS), PaymentAttemptId.next(IDS), AMOUNT, "no idem ref",
                        HoldId.next(IDS), null, null, RefundStatus.DISPATCHED,
                        Instant.now(CLOCK)))
                .as("no provider idempotency reference")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Refund.rehydrate(
                        RefundId.next(IDS), PaymentAttemptId.next(IDS), AMOUNT, "no status",
                        HoldId.next(IDS), idem(), null, null, Instant.now(CLOCK)))
                .as("no status")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a transition carries exactly its payload and nothing else")
    void aTransitionCarriesExactlyItsPayload() {
        Refund dispatched = refundAt(RefundStatus.DISPATCHED);
        ProviderReference reference = new ProviderReference("psp-refund-7");
        Refund completed = dispatched.complete(reference);
        assertThat(completed.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(completed.providerReference()).isEqualTo(reference);
        assertThat(completed.id()).isEqualTo(dispatched.id());
        assertThat(completed.attemptId()).isEqualTo(dispatched.attemptId());
        assertThat(completed.amount()).isEqualTo(dispatched.amount());
        assertThat(completed.reason()).isEqualTo(dispatched.reason());
        assertThat(completed.holdReference()).isEqualTo(dispatched.holdReference());
        assertThat(completed.providerIdempotencyReference())
                .isEqualTo(dispatched.providerIdempotencyReference());
        assertThat(completed.createdAt()).isEqualTo(dispatched.createdAt());

        Refund failed = dispatched.fail();
        assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(failed.providerReference()).isNull();
        assertThat(failed.id()).isEqualTo(dispatched.id());
        assertThat(failed.amount()).isEqualTo(dispatched.amount());
        assertThat(failed.createdAt()).isEqualTo(dispatched.createdAt());
    }

    @Test
    @DisplayName("the SQL fragments are pinned until P5-TSK-008's reconciliation consumes them")
    void theSqlFragmentsArePinned() {
        assertThat(RefundStatus.sqlValueList())
                .isEqualTo("'DISPATCHED', 'UNKNOWN', 'COMPLETED', 'FAILED'");
        assertThat(RefundStatus.sqlTerminalValueList()).isEqualTo("'COMPLETED', 'FAILED'");
    }

    /** The three doors out of a state, keyed by the state each targets. */
    private static Map<RefundStatus, UnaryOperator<Refund>> transitionDoors() {
        return Map.of(
                RefundStatus.COMPLETED,
                        refund -> refund.complete(new ProviderReference("psp-refund-1")),
                RefundStatus.FAILED, Refund::fail,
                RefundStatus.UNKNOWN, Refund::outcomeUnknown);
    }

    /** A refund rehydrated in {@code status}, in that status's coherent shape. */
    private static Refund refundAt(RefundStatus status) {
        return rehydrated(
                status == RefundStatus.COMPLETED ? new ProviderReference("psp-refund-9") : null,
                status);
    }

    private static Refund rehydrated(ProviderReference providerReference, RefundStatus status) {
        return Refund.rehydrate(
                RefundId.next(IDS),
                PaymentAttemptId.next(IDS),
                AMOUNT,
                "operator-recorded reason",
                HoldId.next(IDS),
                idem(),
                providerReference,
                status,
                Instant.now(CLOCK));
    }

    /** A coherent CAPTURED attempt for 98.76 EUR — what every bound in this suite is against. */
    private static PaymentAttempt capturedAttempt() {
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS),
                PaymentIntentId.next(IDS),
                SimulatedCardPspAdapter.RAIL.id(),
                InteractionModel.TWO_STEP,
                idem(),
                idem(),
                new ProviderReference("psp-auth-1"),
                CAPTURED,
                new ProviderReference("psp-cap-1"),
                CAPTURED, null, null,
                null,
                PaymentAttemptStatus.CAPTURED,
                Instant.now(CLOCK),
                null, null, null, null, null);
    }

    private static PaymentAttempt authorizedAttempt() {
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS),
                PaymentIntentId.next(IDS),
                SimulatedCardPspAdapter.RAIL.id(),
                InteractionModel.TWO_STEP,
                idem(),
                null,
                new ProviderReference("psp-auth-2"),
                CAPTURED,
                null,
                null, null, null,
                null,
                PaymentAttemptStatus.AUTHORIZED,
                Instant.now(CLOCK),
                null, null, null, null, null);
    }

    /** An EXECUTED push pay-in for 98.76 EUR — what every return bound is against. */
    private static PaymentAttempt executedPushAttempt() {
        return pushAttempt(PaymentAttemptStatus.EXECUTED, "scheme_tx_77");
    }

    /** A pay-in the payer has not executed — nothing has arrived to return. */
    private static PaymentAttempt awaitingPushAttempt() {
        return pushAttempt(PaymentAttemptStatus.AWAITING_PAYER, null);
    }

    /** An EXECUTED book attempt for 98.76 EUR — the book refund's subject (P7-TSK-011). */
    private static PaymentAttempt executedBookAttempt() {
        return bookAttempt(PaymentAttemptStatus.EXECUTED);
    }

    private static PaymentAttempt failedBookAttempt() {
        return bookAttempt(PaymentAttemptStatus.FAILED);
    }

    private static PaymentAttempt bookAttempt(PaymentAttemptStatus status) {
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS),
                PaymentIntentId.next(IDS),
                RailId.of("book"),
                InteractionModel.BOOK,
                null, null, null, null, null, null, null, null,
                status == PaymentAttemptStatus.FAILED ? PaymentFailureReason.DECLINED : null,
                status,
                Instant.now(CLOCK),
                null, null, null, null, null);
    }

    private static PaymentAttempt pushAttempt(
            PaymentAttemptStatus status, String schemeReference) {
        Instant born = Instant.now(CLOCK);
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS),
                PaymentIntentId.next(IDS),
                RailId.of("instant"),
                InteractionModel.PUSH,
                null, null, null, null, null, null, null, null, null,
                status,
                born,
                new EndToEndReference(UUID.randomUUID().toString().replace("-", "")),
                null,
                schemeReference == null ? null : new ProviderReference(schemeReference),
                null,
                born);
    }

    private static ProviderIdempotencyReference idem() {
        return new ProviderIdempotencyReference("ref-" + UUID.randomUUID());
    }

    /** The return's mint (`P7-TSK-010`): 32 hex, inside the wire's 35-character bound. */
    private static ProviderIdempotencyReference returnIdem() {
        return new ProviderIdempotencyReference(
                UUID.randomUUID().toString().replace("-", ""));
    }
}
