package com.finapp.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transfer machine, held at the aggregate (`P4-TSK-003`, ADR-0044, {@code INV-LIFE-01/-02}).
 *
 * <p>The transition sweep is <strong>derived from the machine</strong> — every ordered pair of
 * states, expectation read from {@code permittedTransitions()} — the {@code CustomerAccountTest}
 * idiom, so a state or edge added later is swept without anyone remembering. The machine itself
 * is separately <strong>pinned</strong>, because a sweep that trusts the machine cannot notice
 * the machine changing: an edge added or removed must be a decision, never a silent edit.
 */
@DisplayName("Transfer (P4-TSK-003)")
class TransferTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(12_50, EUR);

    @Test
    @DisplayName("the machine is pinned: four states, three edges, COMPLETED with exactly one exit")
    void theMachineIsPinned() {
        // COMPLETED's single outgoing edge is the backlog's own named property: stable, not
        // terminal (ADR-0044's reading of INV-LIFE-04) — a second exit, or none, is a decision.
        assertThat(TransferStatus.INITIATED.permittedTransitions())
                .containsExactlyInAnyOrder(TransferStatus.COMPLETED, TransferStatus.FAILED);
        assertThat(TransferStatus.COMPLETED.permittedTransitions())
                .containsExactly(TransferStatus.REVERSED);
        assertThat(TransferStatus.FAILED.permittedTransitions()).isEmpty();
        assertThat(TransferStatus.REVERSED.permittedTransitions()).isEmpty();

        // Nothing transitions TO INITIATED — birth is the only door. The aggregate's API has no
        // method targeting it, so this asserts the machine's half of a property the API makes
        // structural.
        for (TransferStatus from : TransferStatus.values()) {
            assertThat(from.canTransitionTo(TransferStatus.INITIATED))
                    .as("%s -> INITIATED must not exist", from)
                    .isFalse();
        }

        assertThat(EnumSet.allOf(TransferStatus.class).stream()
                        .filter(TransferStatus::isTerminal))
                .containsExactlyInAnyOrder(TransferStatus.FAILED, TransferStatus.REVERSED);
    }

    @Test
    @DisplayName("every transition in the cross-product behaves as the machine declares")
    void everyTransitionIsEnforced() {
        for (TransferStatus from : TransferStatus.values()) {
            for (Map.Entry<TransferStatus, UnaryOperator<Transfer>> target :
                    transitionDoors().entrySet()) {
                Transfer transfer = transferIn(from);
                TransferStatus to = target.getKey();
                if (from.canTransitionTo(to)) {
                    assertThat(target.getValue().apply(transfer).status())
                            .as("%s -> %s is permitted by the machine", from, to)
                            .isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> target.getValue().apply(transfer))
                            .as("%s -> %s must be refused by the aggregate itself", from, to)
                            .isInstanceOf(IllegalTransferTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("both terminals refuse every transition, swept separately (INV-LIFE-04)")
    void bothTerminalsRefuseEveryTransition() {
        for (TransferStatus terminal : EnumSet.of(TransferStatus.FAILED, TransferStatus.REVERSED)) {
            for (Map.Entry<TransferStatus, UnaryOperator<Transfer>> target :
                    transitionDoors().entrySet()) {
                Transfer transfer = transferIn(terminal);
                assertThatThrownBy(() -> target.getValue().apply(transfer))
                        .as("terminal %s must refuse a move to %s", terminal, target.getKey())
                        .isInstanceOf(IllegalTransferTransitionException.class);
            }
        }
    }

    @Test
    @DisplayName("the constructor holds coherence in both directions per rule")
    void theConstructorHoldsCoherence() {
        // The reason exists exactly when FAILED.
        assertThatThrownBy(() -> rehydrateShape(TransferStatus.FAILED, null, null, false))
                .as("FAILED without its reason")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        rehydrateShape(TransferStatus.INITIATED, FailureReason.INSUFFICIENT_FUNDS,
                                null, false))
                .as("a reason on an unjudged transfer")
                .isInstanceOf(IllegalArgumentException.class);

        // The entry exists exactly when money moved.
        assertThatThrownBy(() -> rehydrateShape(TransferStatus.COMPLETED, null, null, false))
                .as("COMPLETED without its entry")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        rehydrateShape(TransferStatus.FAILED, FailureReason.INSUFFICIENT_FUNDS,
                                JournalEntryId.next(IDS), false))
                .as("an entry on a refusal that posted nothing")
                .isInstanceOf(IllegalArgumentException.class);

        // The reversal triple exists exactly when REVERSED — all three or none. transferIn()
        // proves the coherent REVERSED shape constructs; here each surplus direction.
        assertThatThrownBy(() -> Transfer.rehydrate(
                        TransferId.next(IDS), IDS.next(),
                        LedgerAccountId.next(IDS), LedgerAccountId.next(IDS),
                        AMOUNT, null, TransferStatus.COMPLETED, null,
                        JournalEntryId.next(IDS), JournalEntryId.next(IDS), IDS.next(),
                        Instant.now(CLOCK), IDS.next(), Instant.now(CLOCK)))
                .as("a reversal triple on a merely COMPLETED transfer")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Transfer.rehydrate(
                        TransferId.next(IDS), IDS.next(),
                        LedgerAccountId.next(IDS), LedgerAccountId.next(IDS),
                        AMOUNT, null, TransferStatus.REVERSED, null,
                        JournalEntryId.next(IDS), JournalEntryId.next(IDS), null, null,
                        IDS.next(), Instant.now(CLOCK)))
                .as("REVERSED without its actor and instant")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the pair rule follows the machine: equal accounts only unjudged or as the SELF_TRANSFER record")
    void thePairRuleFollowsTheMachine() {
        LedgerAccountId same = LedgerAccountId.next(IDS);

        // Money moved with an equal pair is a corrupt record — the balanced no-op entry the
        // validation exists to prevent (PHASE_4_PLAN.md §14.6).
        assertThatThrownBy(() -> rehydrateShape(TransferStatus.COMPLETED, null,
                        JournalEntryId.next(IDS), true))
                .as("a completed self-transfer")
                .isInstanceOf(IllegalArgumentException.class);

        // Any refusal but SELF_TRANSFER with the equal pair is likewise incoherent.
        assertThatThrownBy(() -> rehydrateShape(TransferStatus.FAILED,
                        FailureReason.INSUFFICIENT_FUNDS, null, true))
                .as("an equal-pair refusal under another reason")
                .isInstanceOf(IllegalArgumentException.class);

        // And SELF_TRANSFER must record the mistake it refused: two different accounts are
        // incoherent with that reason.
        assertThatThrownBy(() -> rehydrateShape(TransferStatus.FAILED,
                        FailureReason.SELF_TRANSFER, null, false))
                .as("a SELF_TRANSFER refusal naming two different accounts")
                .isInstanceOf(IllegalArgumentException.class);

        // The equal pair is admitted while unjudged — judging is the execution's act — and has
        // exactly one legal exit: the committed record of refusing exactly that mistake.
        Transfer unjudged = Transfer.initiate(
                IDS, CLOCK, IDS.next(), same, same, AMOUNT, null, IDS.next());
        assertThat(unjudged.fail(FailureReason.SELF_TRANSFER).status())
                .isEqualTo(TransferStatus.FAILED);
        assertThatThrownBy(() -> unjudged.complete(JournalEntryId.next(IDS)))
                .as("completing a self-transfer")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> unjudged.fail(FailureReason.INSUFFICIENT_FUNDS))
                .as("failing a self-transfer under any other reason")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("initiation is coherent, and a non-positive amount is refused naming no amount")
    void initiationIsCoherentAndPositive() {
        Transfer transfer = Transfer.initiate(
                IDS, CLOCK, IDS.next(),
                LedgerAccountId.next(IDS), LedgerAccountId.next(IDS),
                AMOUNT, "rent", IDS.next());
        assertThat(transfer.status()).isEqualTo(TransferStatus.INITIATED);
        assertThat(transfer.failureReason()).isNull();
        assertThat(transfer.journalEntryId()).isNull();
        assertThat(transfer.reference()).isEqualTo("rent");

        // Zero moves nothing; negative is a credit wearing a debit's clothes. Never a committed
        // outcome (not in FailureReason) — the boundary's 422, refused here as defence in depth.
        // The value 98_76 is the needle: the refusal must name the fact and the currency, never
        // the amount, because the message reaches logs (INV-AUD-02).
        for (long minorUnits : new long[] {0, -98_76}) {
            assertThatThrownBy(() -> Transfer.initiate(
                            IDS, CLOCK, IDS.next(),
                            LedgerAccountId.next(IDS), LedgerAccountId.next(IDS),
                            Money.ofMinorUnits(minorUnits, EUR), null, IDS.next()))
                    .as("amount of %s minor units", minorUnits)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR")
                    .hasMessageNotContaining("9876")
                    .hasMessageNotContaining("98.76");
        }
    }

    /** The three doors out of a state, keyed by the state each targets. */
    private static Map<TransferStatus, UnaryOperator<Transfer>> transitionDoors() {
        return Map.of(
                TransferStatus.COMPLETED, t -> t.complete(JournalEntryId.next(IDS)),
                TransferStatus.FAILED, t -> t.fail(FailureReason.INSUFFICIENT_FUNDS),
                TransferStatus.REVERSED,
                        t -> t.reverse(JournalEntryId.next(IDS), IDS.next(), CLOCK));
    }

    /** A transfer rehydrated in {@code status}, in that status's coherent shape. */
    private static Transfer transferIn(TransferStatus status) {
        return switch (status) {
            case INITIATED -> rehydrateShape(status, null, null, false);
            case COMPLETED -> rehydrateShape(status, null, JournalEntryId.next(IDS), false);
            case FAILED ->
                    rehydrateShape(status, FailureReason.INSUFFICIENT_FUNDS, null, false);
            case REVERSED -> Transfer.rehydrate(
                    TransferId.next(IDS), IDS.next(),
                    LedgerAccountId.next(IDS), LedgerAccountId.next(IDS),
                    AMOUNT, null, status, null,
                    JournalEntryId.next(IDS), JournalEntryId.next(IDS), IDS.next(),
                    Instant.now(CLOCK), IDS.next(), Instant.now(CLOCK));
        };
    }

    private static Transfer rehydrateShape(
            TransferStatus status,
            FailureReason reason,
            JournalEntryId entry,
            boolean equalPair) {
        LedgerAccountId source = LedgerAccountId.next(IDS);
        return Transfer.rehydrate(
                TransferId.next(IDS), IDS.next(),
                source, equalPair ? source : LedgerAccountId.next(IDS),
                AMOUNT, null, status, reason, entry, null, null, null,
                IDS.next(), Instant.now(CLOCK));
    }
}
