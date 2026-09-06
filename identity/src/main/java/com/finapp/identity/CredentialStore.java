package com.finapp.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * Persists credentials, in the caller's transaction.
 *
 * <p>The unit of work is passed in and never created here, exactly as for {@code OutboxWriter},
 * {@code AuditWriter} and {@code IdempotencyRecordStore}. A store that opened its own transaction
 * would look identical in every test and would silently allow a credential to commit while the
 * registration or the change that produced it rolled back - an active credential for an identity
 * that does not exist.
 *
 * <h2>There is no update, and that is the interface saying so</h2>
 *
 * <p>A credential's derivation is never edited. A change writes a <em>new</em> credential and
 * supersedes the old, so the superseded rows record when protection changed ({@code INV-HIST-01}'s
 * reasoning). {@link #supersede} is the only mutation, it moves one column pair, and the database
 * enforces the rest with a trigger - because the absence of a method from an interface is a
 * convention, and the next writer may not be this interface.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CredentialStore<T> {

    /**
     * Writes a new {@link CredentialStatus#ACTIVE} credential.
     *
     * @throws ActiveCredentialAlreadyExistsException the identity already holds an active
     *     credential of that type. Raised from the partial unique index, so it is the database
     *     arbitrating between two concurrent writers rather than a check that raced
     */
    void insert(T unitOfWork, Credential credential);

    /**
     * Moves an active credential to {@link CredentialStatus#SUPERSEDED}.
     *
     * <p>Conditional on it still being active, and the return value is that condition's answer
     * rather than a void. Two instances superseding the same credential is the ordinary case under
     * ADR-0014, and the loser must be <em>told</em> it lost: a void return would let it carry on
     * and insert a replacement for a credential somebody else has already replaced.
     *
     * @return true if this call performed the transition; false if it was already superseded, which
     *     is a domain outcome and not an error
     */
    boolean supersede(T unitOfWork, CredentialId credentialId, Instant supersededAt);

    /**
     * The identity's active credential of that type, if it has one.
     *
     * <p>Returns at most one because the partial unique index permits at most one - the query does
     * not need to choose, and if it ever returned two the index would be broken and that is worth
     * failing loudly over rather than picking the first row.
     */
    Optional<Credential> findActive(T unitOfWork, IdentityId identityId, CredentialType type);
}
