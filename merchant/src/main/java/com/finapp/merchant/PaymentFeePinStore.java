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
     * Records the pin. Commits with the intent it prices, or neither exists.
     *
     * @throws MerchantStorageException if a pin already exists for this intent — a second
     *     price for one payment, refused by the primary key rather than by a read-then-write
     */
    void insert(T unitOfWork, PaymentFeePin pin);

    /**
     * The pin for this intent, or empty when the payment is nobody's merchant's — which is how
     * a wallet top-up looks from here, and why {@code Optional} rather than a refusal.
     */
    Optional<PaymentFeePin> findFor(T unitOfWork, UUID paymentIntentRef);
}
