package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The payment-intent machine, held at the aggregate ({@code P5-TSK-006}, ADR-0045,
 * {@code INV-LIFE-01/-02}).
 *
 * <p>The transition sweep is <strong>derived from the machine</strong> — every ordered pair of
 * states, expectation read from {@code permittedTransitions()} — so a state or edge added later
 * is swept without anyone remembering. The machine itself is separately <strong>pinned</strong>,
 * because a sweep that trusts the machine cannot notice the machine changing: {@code SUCCEEDED}
 * gaining an outgoing edge — the refund-state-on-the-intent mistake ADR-0045 exists to refuse —
 * would move the sweep's own expectations and pass it, and only the pin catches it.
 *
 * <p>The SQL fragments are pinned by literal here because their consumer — {@code P5-TSK-008}'s
 * migration reconciliation — does not exist yet, and a generator nothing verifies is dead code
 * carrying confident javadoc (the {@code P1-TSK-013} class). When that task lands, these pins
 * become the harmless second reading and the migration test becomes the load-bearing one.
 */
@DisplayName("PaymentIntent (P5-TSK-006)")
class PaymentIntentTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(25_00, EUR);

    @Test
    @DisplayName("the machine is pinned: five states, four edges, SUCCEEDED with no exit")
    void theMachineIsPinned() {
        assertThat(PaymentIntentStatus.REQUIRES_CONFIRMATION.permittedTransitions())
                .containsExactlyInAnyOrder(
                        PaymentIntentStatus.PROCESSING, PaymentIntentStatus.CANCELLED);
        assertThat(PaymentIntentStatus.PROCESSING.permittedTransitions())
                .containsExactlyInAnyOrder(
                        PaymentIntentStatus.SUCCEEDED, PaymentIntentStatus.FAILED);

        // The backlog's own named property: SUCCEEDED has NO outgoing edge. Refund state is the
        // refund rows' fact (ADR-0045) — an edge added here is one fact in two places, and this
        // pin is the only assertion a machine-derived sweep cannot satisfy by following along.
        assertThat(PaymentIntentStatus.SUCCEEDED.permittedTransitions())
                .as("SUCCEEDED is stable with no outgoing edge (ADR-0045)")
                .isEmpty();
        assertThat(PaymentIntentStatus.FAILED.permittedTransitions()).isEmpty();
        assertThat(PaymentIntentStatus.CANCELLED.permittedTransitions()).isEmpty();

        // Nothing transitions TO REQUIRES_CONFIRMATION — birth is the only door. The aggregate's
        // API has no method targeting it, so this asserts the machine's half of a property the
        // API makes structural.
        for (PaymentIntentStatus from : PaymentIntentStatus.values()) {
            assertThat(from.canTransitionTo(PaymentIntentStatus.REQUIRES_CONFIRMATION))
                    .as("%s -> REQUIRES_CONFIRMATION must not exist", from)
                    .isFalse();
        }

        // Terminal is the structural derivation (no outgoing edge), so SUCCEEDED is IN the set —
        // the inverse of TransferStatus's recorded exclusion of COMPLETED, argued on the enum:
        // INV-LIFE-04's own text names refund as a NEW operation out of a terminal state.
        assertThat(EnumSet.allOf(PaymentIntentStatus.class).stream()
                        .filter(PaymentIntentStatus::isTerminal))
                .containsExactlyInAnyOrder(
                        PaymentIntentStatus.SUCCEEDED,
                        PaymentIntentStatus.FAILED,
                        PaymentIntentStatus.CANCELLED);
    }

    @Test
    @DisplayName("every transition in the cross-product behaves as the machine declares")
    void everyTransitionIsEnforced() {
        for (PaymentIntentStatus from : PaymentIntentStatus.values()) {
            for (Map.Entry<PaymentIntentStatus, UnaryOperator<PaymentIntent>> target :
                    transitionDoors().entrySet()) {
                PaymentIntent intent = intentIn(from);
                PaymentIntentStatus to = target.getKey();
                if (from.canTransitionTo(to)) {
                    assertThat(target.getValue().apply(intent).status())
                            .as("%s -> %s is permitted by the machine", from, to)
                            .isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> target.getValue().apply(intent))
                            .as("%s -> %s must be refused by the aggregate itself", from, to)
                            .isInstanceOf(IllegalPaymentIntentTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("both terminals and the stable state refuse every door, swept separately (INV-LIFE-04)")
    void terminalsAndTheStableStateRefuseEveryDoor() {
        // CANCELLED and FAILED are the terminals; SUCCEEDED is ADR-0045's stable state. All
        // three have no exit, and each is swept by name so the acceptance criterion's own
        // wording — "both terminals and the stable state" — is what the test says.
        for (PaymentIntentStatus noExit : EnumSet.of(
                PaymentIntentStatus.CANCELLED,
                PaymentIntentStatus.FAILED,
                PaymentIntentStatus.SUCCEEDED)) {
            for (Map.Entry<PaymentIntentStatus, UnaryOperator<PaymentIntent>> target :
                    transitionDoors().entrySet()) {
                PaymentIntent intent = intentIn(noExit);
                assertThatThrownBy(() -> target.getValue().apply(intent))
                        .as("%s must refuse a move to %s", noExit, target.getKey())
                        .isInstanceOf(IllegalPaymentIntentTransitionException.class);
            }
        }
    }

    @Test
    @DisplayName("creation is coherent, and a non-positive amount is refused naming no amount")
    void creationIsCoherentAndPositive() {
        PaymentIntent intent = PaymentIntent.create(
                IDS, CLOCK, IDS.next(), IDS.next(), IDS.next(),
                LedgerAccountId.next(IDS), AMOUNT);
        assertThat(intent.status()).isEqualTo(PaymentIntentStatus.REQUIRES_CONFIRMATION);
        assertThat(intent.id()).isNotNull();
        assertThat(intent.amount()).isEqualTo(AMOUNT);
        assertThat(intent.createdAt()).isNotNull();

        // Zero asserts nothing; negative is a credit wearing a debit's clothes. Never a
        // committed outcome — the boundary's 422 (P5-TSK-009) — refused here as defence in
        // depth. The value 98_76 is the needle: the refusal must name the fact and the
        // currency, never the amount, because the message reaches logs (INV-AUD-02).
        for (long minorUnits : new long[] {0, -98_76}) {
            assertThatThrownBy(() -> PaymentIntent.create(
                            IDS, CLOCK, IDS.next(), IDS.next(), IDS.next(),
                            LedgerAccountId.next(IDS),
                            Money.ofMinorUnits(minorUnits, EUR)))
                    .as("amount of %s minor units", minorUnits)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR")
                    .hasMessageNotContaining("9876")
                    .hasMessageNotContaining("98.76");
        }
    }

    @Test
    @DisplayName("rehydrate routes through the one constructor and refuses the corrupt row")
    void rehydrateRefusesTheCorruptRow() {
        // The coherent shape constructs in every status — the intent's coherence is
        // deliberately status-independent (every status-dependent payload lives on the attempt
        // or the refund rows, ADR-0045), so what rehydrate refuses is the row no writer that
        // passed the domain could have produced: the raw-SQL-shaped corruption, ahead of
        // P5-TSK-008's CHECKs.
        for (PaymentIntentStatus status : PaymentIntentStatus.values()) {
            assertThat(intentIn(status).status()).isEqualTo(status);
        }

        // A non-positive amount read back — the schema bypass — is refused with the same
        // amount-free message as at birth, because it is the same constructor.
        assertThatThrownBy(() -> PaymentIntent.rehydrate(
                        PaymentIntentId.next(IDS), IDS.next(), IDS.next(), IDS.next(),
                        LedgerAccountId.next(IDS),
                        Money.ofMinorUnits(-98_76, EUR),
                        PaymentIntentStatus.SUCCEEDED,
                        Instant.now(CLOCK)))
                .as("a stored non-positive amount")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR")
                .hasMessageNotContaining("9876");

        // Absent fields are refused whatever the status — a row with a null owner or a null
        // status is not an intent, whoever wrote it.
        assertThatThrownBy(() -> PaymentIntent.rehydrate(
                        PaymentIntentId.next(IDS), null, IDS.next(), IDS.next(),
                        LedgerAccountId.next(IDS), AMOUNT,
                        PaymentIntentStatus.PROCESSING, Instant.now(CLOCK)))
                .as("a rehydrated row with no party")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PaymentIntent.rehydrate(
                        PaymentIntentId.next(IDS), IDS.next(), IDS.next(), IDS.next(),
                        LedgerAccountId.next(IDS), AMOUNT, null, Instant.now(CLOCK)))
                .as("a rehydrated row with no status")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a transition changes the status and nothing else")
    void aTransitionChangesTheStatusAndNothingElse() {
        // No intent transition carries a payload — the design fact that lets P5-TSK-008 narrow
        // the UPDATE grant to the status column. Asserted, so a stamp or payload added to a
        // door is a decision rather than a drift.
        PaymentIntent created = PaymentIntent.create(
                IDS, CLOCK, IDS.next(), IDS.next(), IDS.next(),
                LedgerAccountId.next(IDS), AMOUNT);
        PaymentIntent confirmed = created.confirm();
        assertThat(confirmed.status()).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(confirmed.id()).isEqualTo(created.id());
        assertThat(confirmed.partyId()).isEqualTo(created.partyId());
        assertThat(confirmed.customerId()).isEqualTo(created.customerId());
        assertThat(confirmed.paymentMethodId()).isEqualTo(created.paymentMethodId());
        assertThat(confirmed.walletAccount()).isEqualTo(created.walletAccount());
        assertThat(confirmed.amount()).isEqualTo(created.amount());
        assertThat(confirmed.createdAt()).isEqualTo(created.createdAt());
    }

    @Test
    @DisplayName("the SQL fragments are pinned until P5-TSK-008's reconciliation consumes them")
    void theSqlFragmentsArePinned() {
        assertThat(PaymentIntentStatus.sqlValueList())
                .isEqualTo("'REQUIRES_CONFIRMATION', 'PROCESSING', 'SUCCEEDED', 'FAILED',"
                        + " 'CANCELLED'");
        // SUCCEEDED is IN the terminal list — the enum's recorded decision. A hand-list that
        // dropped it (or a derivation that stopped being structural) fails here by name.
        assertThat(PaymentIntentStatus.sqlTerminalValueList())
                .isEqualTo("'SUCCEEDED', 'FAILED', 'CANCELLED'");
    }

    /** The four doors out of a state, keyed by the state each targets. */
    private static Map<PaymentIntentStatus, UnaryOperator<PaymentIntent>> transitionDoors() {
        return Map.of(
                PaymentIntentStatus.PROCESSING, PaymentIntent::confirm,
                PaymentIntentStatus.CANCELLED, PaymentIntent::cancel,
                PaymentIntentStatus.SUCCEEDED, PaymentIntent::succeed,
                PaymentIntentStatus.FAILED, PaymentIntent::fail);
    }

    /** An intent rehydrated in {@code status} — every status's coherent shape is the same. */
    private static PaymentIntent intentIn(PaymentIntentStatus status) {
        return PaymentIntent.rehydrate(
                PaymentIntentId.next(IDS),
                IDS.next(),
                IDS.next(),
                IDS.next(),
                LedgerAccountId.next(IDS),
                AMOUNT,
                status,
                Instant.now(CLOCK));
    }

}
