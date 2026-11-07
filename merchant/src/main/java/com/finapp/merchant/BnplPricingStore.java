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
     * Records the pin and answers it <strong>as stored</strong> - its instant the insert's own stamp on the database clock
     * (merchant {@code V013}, `P14-DOC-001`: the trigger stamps {@code pinned_at} over the {@code pinnedAt} handed in, the
     * resolution's instant) - or empty when the purchase already has one: the primary key answering, behind a savepoint so
     * the purchase's transaction stays usable.
     */
    Optional<BnplFeePin> insertPin(T unitOfWork, BnplFeePin pin);

    /** The purchase's pin, or empty. */
    Optional<BnplFeePin> findPin(T unitOfWork, UUID purchaseRef);

    /**
     * The database's clock now - {@code clock_timestamp()}, read on the caller's transaction (the Phase 12 -> 13 gate's
     * A3-M1) and never before that transaction's start ({@code GREATEST(clock_timestamp(), transaction_timestamp())},
     * `P14-DOC-001`: a clock stepped back before the read cannot resolve a version superseded when the transaction began):
     * the one instant every instance shares, at which the fee version in force is resolved, read after the standing's
     * share locks were granted. Never an instance's own clock. The pin's own instant is its insert's stamp
     * ({@link #insertPin}).
     */
    java.time.Instant databaseNow(T unitOfWork);
}
