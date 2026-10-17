package com.finapp.merchant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for a merchant's BNPL pricing (`P12-TSK-006`): which fee schedule prices each BNPL product a merchant
 * offers, and the fee pin every BNPL purchase is born with. The pin is insert-and-read only - the
 * {@link PaymentFeePinStore} discipline: a port with no method that could reprice a purchase.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection} (ADR-0033)
 */
public interface BnplPricingStore<T> {

    /** The schedule pricing the merchant's {@code product}, or empty. */
    Optional<FeeScheduleId> findAssignment(T unitOfWork, MerchantId merchant, MerchantBnplProduct product);

    /**
     * Points the merchant's {@code product} at {@code schedule}, inserting or moving the pointer, and appends the
     * reasoned history row - under the merchant row's lock (the {@link FeeScheduleStore#assign} shape).
     */
    void assign(
            T unitOfWork,
            MerchantId merchant,
            MerchantBnplProduct product,
            Optional<FeeScheduleId> previous,
            FeeScheduleId schedule,
            String reason,
            String actorId,
            String actorType,
            Instant at);

    /**
     * Records the pin; {@code false} when the purchase already has one - the primary key answering, behind a savepoint so
     * the purchase's transaction stays usable.
     */
    boolean insertPin(T unitOfWork, BnplFeePin pin);

    /** The purchase's pin, or empty. */
    Optional<BnplFeePin> findPin(T unitOfWork, UUID purchaseRef);
}
