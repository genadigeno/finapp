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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three machines, pinned on their owner (`P7-TSK-002`, ADR-0059 §2) — and the structural
 * facts the rest of the design leans on: the models' non-terminal vocabularies are disjoint
 * (what makes {@link InteractionModel#anyPermits} exact and a history row's vocabulary
 * unambiguous), the terminal set is the structural union ({@code isTerminal}'s pin), and the
 * aggregate refuses a foreign model's state, payload and door.
 *
 * <p>The push and book operations deliberately do not exist yet — their doors, payload columns
 * and births arrive with their rails (`P7-TSK-006`, `-009`, `-011`) — so the aggregate layer
 * for those models is the constructor's coherence plus the guard's refusal of every existing
 * door, proven here on rehydrated rows; the register's §3 states the remainder honestly.
 */
@DisplayName("the machines per interaction model (P7-TSK-002)")
class InteractionModelMachinesTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Money AMOUNT = Money.ofMinorUnits(98_76, CurrencyCode.of("EUR"));

    @Test
    @DisplayName("the push machine is pinned: AWAITING_PAYER for a pay-in, dispatch, unknown,"
            + " EXECUTED - each non-terminal also failing")
    void thePushMachineIsPinned() {
        var push = InteractionModel.PUSH.edges();
        // The inbound edge joined at P7-TSK-009 (ADR-0062 section 5): the payer's execution
        // concludes the waiting state directly - the platform never dispatches it.
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
        assertThat(push.keySet()).hasSize(5);
    }

    @Test
    @DisplayName("the book machine has NO edges: born EXECUTED or FAILED, never a history row")
    void theBookMachineHasNoEdges() {
        var book = InteractionModel.BOOK.edges();
        assertThat(book.keySet())
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.EXECUTED, PaymentAttemptStatus.FAILED);
        assertThat(book.values()).allSatisfy(exits -> assertThat(exits).isEmpty());
        for (PaymentAttemptStatus from : PaymentAttemptStatus.values()) {
            for (PaymentAttemptStatus to : PaymentAttemptStatus.values()) {
                assertThat(InteractionModel.BOOK.permits(from, to))
                        .as("BOOK permits no transition at all (%s -> %s)", from, to)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("non-terminal vocabularies are model-exclusive, so anyPermits is exact and a"
            + " history row's vocabulary names its machine")
    void nonTerminalVocabulariesAreDisjoint() {
        for (PaymentAttemptStatus status : PaymentAttemptStatus.values()) {
            long owners = EnumSet.allOf(InteractionModel.class).stream()
                    .filter(model -> model.statuses().contains(status)
                            && !model.edges().get(status).isEmpty())
                    .count();
            assertThat(owners)
                    .as("%s must have edges in at most one machine", status)
                    .isLessThanOrEqualTo(1);
        }
        // And so every EDGE has exactly one owner: the exists-check the store's writer-side
        // guard uses cannot bless a cross-model move.
        for (PaymentAttemptStatus from : PaymentAttemptStatus.values()) {
            for (PaymentAttemptStatus to : PaymentAttemptStatus.values()) {
                long permitting = EnumSet.allOf(InteractionModel.class).stream()
                        .filter(model -> model.permits(from, to))
                        .count();
                assertThat(permitting)
                        .as("%s -> %s must belong to at most one machine", from, to)
                        .isLessThanOrEqualTo(1);
            }
        }
        // isTerminal is the structural union, one definition (the V012 index's predicate).
        for (PaymentAttemptStatus status : PaymentAttemptStatus.values()) {
            boolean anyExit = EnumSet.allOf(InteractionModel.class).stream()
                    .anyMatch(model -> !model.edges()
                            .getOrDefault(status, EnumSet.noneOf(PaymentAttemptStatus.class))
                            .isEmpty());
            assertThat(status.isTerminal())
                    .as("%s: isTerminal must equal no-exit-in-any-machine", status)
                    .isEqualTo(!anyExit);
        }
    }

    @Test
    @DisplayName("the aggregate refuses a foreign model's state, a foreign payload, and every"
            + " two-step door on a push or book row")
    void theAggregateHoldsTheModelApart() {
        // A two-step row cannot occupy a push state, whatever the payload says.
        assertThatThrownBy(() -> rehydrated(
                        InteractionModel.TWO_STEP, idem(),
                        PaymentAttemptStatus.EXECUTION_DISPATCHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another machine's");
        // A push row cannot occupy a two-step state, nor a book row a mid-flight one.
        assertThatThrownBy(() -> rehydrated(
                        InteractionModel.PUSH, null, PaymentAttemptStatus.AUTHORIZED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another machine's");
        assertThatThrownBy(() -> rehydrated(
                        InteractionModel.BOOK, null, PaymentAttemptStatus.EXECUTION_UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another machine's");
        // No two-step fact rides a foreign row: the dispatch reference and the stage facts.
        assertThatThrownBy(() -> rehydrated(
                        InteractionModel.PUSH, idem(), PaymentAttemptStatus.AWAITING_PAYER))
                .as("a push row holding the two-step dispatch reference")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two-step");
        assertThatThrownBy(() -> PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), PaymentIntentId.next(IDS),
                        RailId.of("push-test"), InteractionModel.PUSH, null, null,
                        new ProviderReference("psp-x"), AMOUNT, null, null, null, null, null,
                        PaymentAttemptStatus.AWAITING_PAYER, Instant.now(CLOCK),
                        null, null, null, null, null))
                .as("a push row holding the issuer's promise")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no two-step fact");

        // The coherent foreign rows construct - and refuse every existing door: the doors are
        // the two-step operations, and a cross-model move is refused by the guard itself.
        for (PaymentAttemptStatus status : InteractionModel.PUSH.statuses()) {
            PaymentAttempt push = rehydrated(InteractionModel.PUSH, null, status);
            assertThat(push.interactionModel()).isEqualTo(InteractionModel.PUSH);
            assertThatThrownBy(() -> push.authorize(new ProviderReference("psp-1"), AMOUNT))
                    .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
            assertThatThrownBy(() -> push.dispatchCapture(idem()))
                    .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
            assertThatThrownBy(push::captureOutcomeUnknown)
                    .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
        }
        PaymentAttempt book =
                rehydrated(InteractionModel.BOOK, null, PaymentAttemptStatus.EXECUTED);
        assertThatThrownBy(() -> book.authorize(new ProviderReference("psp-1"), AMOUNT))
                .isInstanceOf(IllegalPaymentAttemptTransitionException.class);
    }

    // ----------------------------------------------------------------- fixtures

    /** A bare foreign-model row; FAILED gets its mapped reason, the one fact every model
     * owes — and a PUSH row its own birth facts (`P7-TSK-009`): the end-to-end reference
     * and the initiation permit always, the scheme's reference exactly when EXECUTED. */
    private static PaymentAttempt rehydrated(
            InteractionModel model,
            ProviderIdempotencyReference authorizationReference,
            PaymentAttemptStatus status) {
        java.time.Instant born = Instant.now(CLOCK);
        boolean push = model == InteractionModel.PUSH;
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS),
                PaymentIntentId.next(IDS),
                RailId.of("push-test"),
                model,
                authorizationReference,
                null, null, null, null, null,
                null,
                null,
                status == PaymentAttemptStatus.FAILED
                        ? PaymentFailureReason.PROVIDER_UNAVAILABLE
                        : null,
                status,
                born,
                push ? new EndToEndReference(UUID.randomUUID().toString().replace("-", "")) : null,
                null,
                push && status == PaymentAttemptStatus.EXECUTED
                        ? new ProviderReference("sch-" + UUID.randomUUID())
                        : null,
                null,
                push ? born : null);
    }

    private static ProviderIdempotencyReference idem() {
        return new ProviderIdempotencyReference("model-" + UUID.randomUUID());
    }
}
