package com.finapp.party;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * A relationship a {@link Party} holds toward the platform (ADR-0029).
 *
 * <p>Not a person. A Customer is a <em>role</em>, and the distinction earns itself in the four
 * cases a merged model cannot represent: a beneficial owner who must be recorded and is not a
 * customer; an organisation that is a customer and is not a person; a Party who holds two
 * relationships over time; and a Party who ceases to be a customer while every record referring to
 * them must stay exactly as it was.
 *
 * <h2>The transition rules live here</h2>
 *
 * <p>{@code INV-LIFE-02} requires an invalid transition to be rejected <em>by the aggregate</em>,
 * not merely to be unreachable through the API. That distinction is the whole point: every
 * aggregate is eventually driven by a second caller — a background job, an operator tool, a
 * migration script — and an API-layer check protects none of them.
 *
 * <p>So {@link #suspend}, {@link #activate} and {@link #close} each ask {@link CustomerStatus}
 * whether the move is legal and throw if it is not. There is no setter, and no method that takes a
 * target state, because a method that accepts any state is a method whose caller decides the
 * machine.
 *
 * <h2>Closed is terminal</h2>
 *
 * <p>{@code close()} from {@code CLOSED} throws rather than being a no-op. A silently idempotent
 * close would hide a caller that believes it is ending a relationship which ended months ago,
 * which is a defect worth a stack trace ({@code INV-LIFE-04}).
 *
 * <h2>State changes return a new instance</h2>
 *
 * <p>The aggregate is immutable, so a rejected transition cannot leave a half-changed object behind
 * and a caller cannot hold a reference that mutates underneath it. It also means the caller has to
 * do something with the result, which is what makes a persistence step visible rather than implied
 * by a setter — the same reasoning ADR-0033 gives for rejecting dirty checking.
 */
public final class Customer {

    private final CustomerId id;
    private final PartyId partyId;
    private final CustomerStatus status;
    private final Instant openedAt;
    private final Instant statusChangedAt;

    private Customer(
            CustomerId id,
            PartyId partyId,
            CustomerStatus status,
            Instant openedAt,
            Instant statusChangedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.partyId = Objects.requireNonNull(partyId, "partyId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        this.statusChangedAt =
                Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
    }

    /**
     * Opens a relationship for a Party, in {@link CustomerStatus#PENDING}.
     *
     * <p>Pending rather than active, because "we have recorded this person" and "this person may
     * transact" are different facts and Phase 2's KYC decision sits between them. Starting active
     * and adding a check later would mean every existing row had silently been treated as verified.
     */
    public static Customer open(IdGenerator ids, Clock clock, PartyId partyId) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Instant now = Instant.now(clock);
        return new Customer(CustomerId.next(ids), partyId, CustomerStatus.PENDING, now, now);
    }

    /** Reconstitutes from storage. Applies no transition rules: the row was already valid. */
    public static Customer rehydrate(
            CustomerId id,
            PartyId partyId,
            CustomerStatus status,
            Instant openedAt,
            Instant statusChangedAt) {
        return new Customer(id, partyId, status, openedAt, statusChangedAt);
    }

    /** {@code PENDING → ACTIVE}, or back from {@code SUSPENDED}. */
    public Customer activate(Clock clock) {
        return transitionTo(CustomerStatus.ACTIVE, clock);
    }

    /** {@code ACTIVE → SUSPENDED}. Reversible; the relationship has not ended. */
    public Customer suspend(Clock clock) {
        return transitionTo(CustomerStatus.SUSPENDED, clock);
    }

    /**
     * {@code → CLOSED}, from any non-terminal state. Terminal.
     *
     * <p>Re-establishing a relationship with the same Party is a new Customer, with its own
     * identifier and its own dates. This one stays closed and stays true.
     */
    public Customer close(Clock clock) {
        return transitionTo(CustomerStatus.CLOSED, clock);
    }

    private Customer transitionTo(CustomerStatus target, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalCustomerTransitionException(id, status, target);
        }
        return new Customer(id, partyId, target, openedAt, Instant.now(clock));
    }

    public CustomerId id() {
        return id;
    }

    /** The Party this relationship belongs to. Fixed for the life of the Customer. */
    public PartyId partyId() {
        return partyId;
    }

    public CustomerStatus status() {
        return status;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Customer customer && id.equals(customer.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Carries no personal data: two identifiers and a status. */
    @Override
    public String toString() {
        return "Customer[" + id + ", party=" + partyId + ", " + status + "]";
    }
}
