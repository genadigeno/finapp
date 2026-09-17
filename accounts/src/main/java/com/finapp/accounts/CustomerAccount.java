package com.finapp.accounts;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The Customer Account: the product agreement a customer holds (`P3-TSK-012`, ADR-0042).
 *
 * <p><strong>An agreement, never a balance.</strong> There is no amount anywhere on this type,
 * and that absence is the design ({@code INV-BAL-01}): the money lives in the ledger accounts
 * whose opaque {@code owner_ref} is this aggregate's identifier, one per currency, and a balance
 * question about the product is a question against those. A balance field here is
 * {@code balance = balance + amount} waiting for an author under deadline.
 *
 * <p><strong>The customer is a {@code UUID}, deliberately.</strong> {@code party} owns the typed
 * {@code CustomerId} and this module cannot see {@code party} — the identifier arrives through
 * {@link AccountHolderVerification}, resolved from authoritative state inside the opening
 * transaction, never from a request.
 *
 * <p><strong>One factory, one transition method.</strong> {@link #open} is the only way a new
 * aggregate exists and it creates {@code ACTIVE} — the verification gate is opening's only
 * precondition, and it is the caller's ({@link AccountOpening}); see
 * {@link CustomerAccountStatus} for why {@code PENDING} has no producer. {@link #moveTo} is the
 * machine's single door ({@code INV-LIFE-02}): per-act methods with no caller would be dead
 * code carrying confident javadoc (the {@code P1-TSK-013} finding) — `P3-TSK-014`'s close is
 * the first caller.
 */
public final class CustomerAccount {

    private final CustomerAccountId id;
    private final UUID customerId;
    private final ProductType productType;
    private final CustomerAccountStatus status;
    private final Instant openedAt;
    private final Instant statusChangedAt;

    private CustomerAccount(
            CustomerAccountId id,
            UUID customerId,
            ProductType productType,
            CustomerAccountStatus status,
            Instant openedAt,
            Instant statusChangedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.productType = Objects.requireNonNull(productType, "productType must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        this.statusChangedAt =
                Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
    }

    /** A new agreement for a verified customer. {@code ACTIVE} from birth. */
    public static CustomerAccount open(
            IdGenerator ids, Clock clock, UUID customerId, ProductType productType) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Instant now = Instant.now(clock);
        return new CustomerAccount(
                CustomerAccountId.next(ids),
                customerId,
                productType,
                CustomerAccountStatus.ACTIVE,
                now,
                now);
    }

    /** A row read back from storage. The database already validated it; this re-asserts shape. */
    public static CustomerAccount rehydrate(
            CustomerAccountId id,
            UUID customerId,
            ProductType productType,
            CustomerAccountStatus status,
            Instant openedAt,
            Instant statusChangedAt) {
        return new CustomerAccount(id, customerId, productType, status, openedAt, statusChangedAt);
    }

    /**
     * This agreement in {@code target} state, or a refusal the machine makes itself
     * ({@code INV-LIFE-02} — an invalid transition is rejected by the aggregate, not merely
     * unreachable through an API, because every aggregate eventually meets a second caller).
     */
    public CustomerAccount moveTo(CustomerAccountStatus target, Clock clock) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalCustomerAccountTransitionException(id, status, target);
        }
        return new CustomerAccount(
                id, customerId, productType, target, openedAt, Instant.now(clock));
    }

    public CustomerAccountId id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public ProductType productType() {
        return productType;
    }

    public CustomerAccountStatus status() {
        return status;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }
}
