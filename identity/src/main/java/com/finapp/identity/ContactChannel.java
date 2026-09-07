package com.finapp.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the platform can reach a person, and whether they proved they control it (`P1-TSK-023`).
 *
 * <h2>An entity within Identity, never independently addressable</h2>
 *
 * <p>The same position {@code Credential} holds. A channel belongs to exactly one Identity for its
 * whole life and has no meaning apart from it, so nothing outside {@code identity} takes a
 * {@link ContactChannelId} and there is no endpoint that names one belonging to somebody else.
 *
 * <h2>{@code verifiedAt} is the control INV-IDN-06 names</h2>
 *
 * <p>Not a boolean. <em>When</em> control was proven is the question an investigator asks after a
 * takeover, and "recently changed channel" is one of the invariant's own abuse cases - a question a
 * boolean cannot answer at all.
 */
public record ContactChannel(
        ContactChannelId id,
        IdentityId identityId,
        ContactChannelKind kind,
        EmailAddress address,
        Optional<Instant> verifiedAt,
        Instant addedAt) {

    public ContactChannel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(address, "address must not be null");
        Objects.requireNonNull(verifiedAt, "verifiedAt must not be null; use Optional.empty()");
        Objects.requireNonNull(addedAt, "addedAt must not be null");
    }

    /** Whether this channel may receive a recovery token. */
    public boolean isVerified() {
        return verifiedAt.isPresent();
    }

    /**
     * How recently control was proven.
     *
     * <p>The input to INV-IDN-06's "recently changed channel" abuse case: a channel verified moments
     * ago is the signature of a takeover in progress, because the attacker's first move after
     * compromising a password is to point recovery at a mailbox they own.
     */
    public java.time.Duration verifiedFor(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        return verifiedAt
                .map(when -> java.time.Duration.between(when, at))
                .orElse(java.time.Duration.ZERO);
    }

    /** Never the address. It is RESTRICTED-PII and this reaches logs (`INV-AUD-02`). */
    @Override
    public String toString() {
        return "ContactChannel[" + id + ", " + kind + ", verified=" + isVerified() + "]";
    }
}
