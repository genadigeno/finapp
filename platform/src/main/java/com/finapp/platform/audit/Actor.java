package com.finapp.platform.audit;

import java.util.Objects;

/**
 * Who performed an audited action: an identifier and what kind of thing it identifies.
 *
 * <p>The {@code ActorId}/{@code ActorType} abstraction ADR-0010 requires in Phase 0. It is a
 * plain bounded string rather than an {@link com.finapp.sharedkernel.id.EntityId}, deliberately:
 * an actor is not one aggregate. It may be a customer, an employee, a named external service or
 * the platform itself, and those identifiers come from different sources and will not all be
 * UUIDs — an external identity provider's subject claim least of all. Forcing them into one
 * typed identifier would mean either inventing a mapping or rejecting a legitimate actor.
 *
 * <p>Bounds mirror the {@code CHECK} constraints on {@code platform.audit_record}: asserted here
 * so a caller gets a domain error rather than a constraint violation from three layers down, and
 * asserted there so the guarantee does not depend on this class being the only writer.
 */
public record Actor(String id, ActorType type) {

    /** Matches {@code audit_record_actor_id_bounded}. */
    public static final int MAX_ID_LENGTH = 200;

    /** The actor recorded for work the platform performs on its own initiative. */
    public static final Actor SYSTEM = new Actor("system", ActorType.SYSTEM);

    public Actor {
        Objects.requireNonNull(id, "actor id must not be null");
        Objects.requireNonNull(type, "actor type must not be null");
        if (id.isBlank()) {
            // Never defaulted to "unknown" or to the system actor. An action whose actor was
            // not established is not an action attributable to the platform - it is an action
            // nobody can answer for, and recording a plausible actor would make the record
            // worse than absent by making it wrong.
            throw new IllegalArgumentException("actor id must not be blank");
        }
        if (id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "actor id must be at most " + MAX_ID_LENGTH + " characters but was " + id.length());
        }
    }

    @Override
    public String toString() {
        return type + ":" + id;
    }
}
