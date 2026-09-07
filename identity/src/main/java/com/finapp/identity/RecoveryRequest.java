package com.finapp.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A single-use, expiring proof of channel control (`P1-TSK-023`, {@code INV-IDN-06}).
 *
 * <h2>What holding this permits, and what it does not</h2>
 *
 * <p>It permits <strong>replacing the credential</strong>. It does not issue a session and it does
 * not remove a factor - both directly from {@code INV-IDN-06}'s second clause, <em>"recovery never
 * lowers the assurance required to reach an account"</em>. A session handed out on completion IS
 * that lowering; an attacker holding the mailbox would skip the credential and everything behind it.
 */
public record RecoveryRequest(
        RecoveryRequestId id,
        IdentityId identityId,
        ContactChannelId channelId,
        RecoveryStatus status,
        Optional<CredentialId> credentialId,
        Instant initiatedAt,
        Instant expiresAt) {

    public RecoveryRequest {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(channelId, "channelId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(credentialId, "credentialId must not be null; use Optional.empty()");
        Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        if (!expiresAt.isAfter(initiatedAt)) {
            throw new IllegalArgumentException("a recovery request cannot expire before it begins");
        }
    }

    /**
     * Whether this request can still be spent.
     *
     * <p>The <strong>definition</strong> of liveness, and the SQL that consumes a token is an
     * implementation of it. Kept even though the store re-checks both clauses, for
     * {@code P1-TSK-013}'s reason: without it the rule would live only in a {@code WHERE} clause,
     * where no reader and no architecture rule would ever find it.
     */
    public boolean isLiveAt(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        return status == RecoveryStatus.INITIATED && at.isBefore(expiresAt);
    }
}
