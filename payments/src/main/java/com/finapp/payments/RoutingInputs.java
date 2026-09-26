package com.finapp.payments;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * What a routing decision is judged from (`P7-TSK-003`, ADR-0060 §2): the payment's
 * direction, its instrument's kind, its amount — and, for an outbound payment, whether the
 * destination is reachable on a candidate rail. Every input is a stored fact of the payment
 * or a recorded observation; nothing here can be instance-local.
 *
 * <p><strong>{@code destinationReachable} is three-valued on purpose.</strong> A pay-in has
 * no destination, so the question does not apply (empty — the reachability check is skipped
 * and the step records nothing about it). An outbound payment answers it from the grant
 * exchange's stored result (ADR-0062, `P7-TSK-008`'s fact), per candidate evaluation arriving
 * with the withdrawal's task; until then the one caller passes empty.
 *
 * <p>A class rather than a record: the amount is {@code RESTRICTED-FINANCIAL}
 * ({@code INV-AUD-02}) and a generated {@code toString} would print it — the
 * {@code PaymentIntent} form, kept for the same reason.
 */
public final class RoutingInputs {

    private final PaymentDirection direction;
    private final InstrumentKind instrumentKind;
    private final Money amount;
    private final Optional<Boolean> destinationReachable;

    public RoutingInputs(
            PaymentDirection direction,
            InstrumentKind instrumentKind,
            Money amount,
            Optional<Boolean> destinationReachable) {
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.instrumentKind =
                Objects.requireNonNull(instrumentKind, "instrumentKind must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.destinationReachable = Objects.requireNonNull(
                destinationReachable,
                "destinationReachable must not be null - not applicable is empty");
    }

    public PaymentDirection direction() {
        return direction;
    }

    public InstrumentKind instrumentKind() {
        return instrumentKind;
    }

    public Money amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return amount.currency();
    }

    public Optional<Boolean> destinationReachable() {
        return destinationReachable;
    }
}
