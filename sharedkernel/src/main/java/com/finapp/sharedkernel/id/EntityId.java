package com.finapp.sharedkernel.id;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The base of every aggregate identifier in the platform.
 *
 * <p><strong>Why identifiers are typed at all.</strong> A bare {@code UUID} parameter accepts
 * any identifier in the system. {@code transfer(customerId, accountId)} and
 * {@code transfer(accountId, customerId)} are the same signature, so the compiler cannot tell
 * them apart and the mistake surfaces as a posting against the wrong entity — the kind of
 * defect that is found at reconciliation, if at all. A typed identifier makes the substitution
 * a compile error, which is the cheapest place a financial defect can be caught.
 *
 * <p><strong>Why identity includes the concrete type.</strong> Two identifiers of different
 * kinds are never equal, even when they carry the same {@link UUID}. Without that, a
 * {@code Set<EntityId>} or a map keyed by identifier would silently conflate an account with a
 * customer that happened to share a value. Equality is {@code final} so no subclass can relax
 * it.
 *
 * <p><strong>Why only UUIDv7 is accepted.</strong> {@link #createdAt()} reads the creation
 * instant out of the identifier, and an identifier that is not time-ordered has no such
 * instant. Accepting a v4 value would make {@code createdAt()} return a fabricated time — a
 * plausible-looking number derived from randomness. Rejecting it at construction keeps the
 * accessor honest, and keeps the index-locality property of ADR-0013 true of every identifier
 * in the system rather than of most of them.
 *
 * <p><strong>What this class is not.</strong> It is not an identifier for any particular
 * thing. {@code CustomerId} belongs to the {@code party} module and {@code AccountId} to
 * {@code accounts}; a shared kernel that accumulated them would become the coupling sink a
 * modular monolith exists to prevent. Subclasses are three lines:
 *
 * <pre>{@code
 * public final class AccountId extends EntityId {
 *     private AccountId(UUID value) { super(value); }
 *     public static AccountId next(IdGenerator ids) { return new AccountId(ids.next()); }
 *     public static AccountId of(String text) { return new AccountId(UUID.fromString(text)); }
 * }
 * }</pre>
 */
public abstract class EntityId {

    /** RFC 9562 time-ordered UUID. */
    private static final int UUID_VERSION_7 = 7;

    /** RFC 9562 variant {@code 10x}, reported as 2 by {@link UUID#variant()}. */
    private static final int RFC_VARIANT = 2;

    private final UUID value;

    /**
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is not a UUIDv7
     */
    protected EntityId(UUID value) {
        Objects.requireNonNull(value, "identifier value must not be null");
        if (value.version() != UUID_VERSION_7 || value.variant() != RFC_VARIANT) {
            throw new IllegalArgumentException(
                    "Identifier must be a UUIDv7 (ADR-0013) but was version "
                            + value.version()
                            + ", variant "
                            + value.variant()
                            + ": "
                            + value);
        }
        this.value = value;
    }

    /** The underlying value, for persistence and transport. */
    public final UUID value() {
        return value;
    }

    /**
     * The instant this identifier was generated, read from its own bytes.
     *
     * <p>Millisecond precision, which is all a UUIDv7 timestamp carries. This is a convenience
     * for diagnosis and ordering, <strong>not</strong> a business timestamp: a created-at that
     * matters to the domain is a field on the aggregate, recorded from an injected clock, and
     * subject to the posting-date/value-date distinction. An identifier's timestamp records
     * when a value was minted, which is not necessarily when anything happened.
     */
    public final Instant createdAt() {
        return Instant.ofEpochMilli(value.getMostSignificantBits() >>> 16);
    }

    /**
     * Equal only to an identifier of the same concrete type carrying the same value.
     *
     * <p>{@code final} deliberately: a subclass relaxing this would reintroduce exactly the
     * cross-aggregate confusion the type is here to prevent.
     */
    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return value.equals(((EntityId) o).value);
    }

    @Override
    public final int hashCode() {
        // The class is part of identity, so it is part of the hash: otherwise two identifiers
        // that are deliberately unequal would always collide.
        return 31 * getClass().hashCode() + value.hashCode();
    }

    /** For example {@code AccountId(0199b3c1-...)}. Names the type, because the type matters. */
    @Override
    public final String toString() {
        return getClass().getSimpleName() + "(" + value + ")";
    }
}
