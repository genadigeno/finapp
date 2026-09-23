package com.finapp.merchant;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link PaymentFeePin} (`P6-TSK-005`). A port on ADR-0033's recorded
 * reasoning; the unit of work is the caller's, because the pin commits with the intent it
 * prices, and the read happens inside the capture's own transaction.
 *
 * <p><strong>Insert and read, and nothing else</strong> — the {@code FeeScheduleStore} shape
 * for the same reason: a pin that could be updated or deleted would let a payment be repriced
 * after the fact, which is the whole of what {@code INV-MER-03} forbids. The absent methods
 * are the cheapest of the three enforcements; `V005`'s trigger and the withheld grant are the
 * other two.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PaymentFeePinStore<T> {

    /**
     * Records the pin if this intent has none. Commits with the intent it prices, or neither
     * exists.
     *
     * <p><strong>The primary key is the arbiter and the queue</strong>
     * ({@code JdbcFeeScheduleStore.insertVersionIfNumberIsFree}'s shape at a second table): ten
     * instances racing to price one payment all attempt the insert, exactly one writes, and the
     * rest are told {@code false} by the key rather than by a read-then-write that two of them
     * could pass. A losing attempt writes nothing and — behind its savepoint — costs one
     * statement rather than the whole transaction, which matters because the intent this pin
     * prices was created in that same transaction.
     *
     * <p>{@code false} is <strong>not</strong> "already correct": it says only that a pin is
     * there. Whether it is the <em>same</em> decision is {@link PaymentFeePin#pricesTheSameAs}'s
     * question, and {@code MerchantSettlement.pin} is where it is asked ({@code INV-MER-03}).
     *
     * @return {@code true} if this call wrote the pin; {@code false} if one was already there
     */
    boolean insertIfAbsent(T unitOfWork, PaymentFeePin pin);

    /**
     * The pin for this intent, or empty when the payment is nobody's merchant's — which is how
     * a wallet top-up looks from here, and why {@code Optional} rather than a refusal.
     */
    Optional<PaymentFeePin> findFor(T unitOfWork, UUID paymentIntentRef);
}
