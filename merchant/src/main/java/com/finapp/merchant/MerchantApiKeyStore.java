package com.finapp.merchant;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link MerchantApiKey} (`P6-TSK-002`).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface MerchantApiKeyStore<T> {

    /** Inserts a freshly issued key. */
    void insert(T unitOfWork, MerchantApiKey key);

    /**
     * The key to authenticate against: {@code ACTIVE}, and belonging to a merchant that is
     * itself {@code ACTIVE} — <strong>the merchant's standing is joined into the lookup, not
     * consulted after it</strong>.
     *
     * <p>That is the design's load-bearing line. A suspended merchant's keys must stop working
     * without anyone revoking them, and they must stop on <em>every</em> instance at the next
     * request; both follow from asking one question of authoritative state per request rather
     * than from any cache, listener or broadcast ({@code INV-MER-01}'s precondition, and
     * {@code INV-IDN-03}'s discipline applied to a credential with no expiry).
     *
     * <p>Empty conflates its causes deliberately: no such key, a revoked key, a suspended
     * merchant and a closed one are one answer, because an authentication surface that
     * distinguishes them is an oracle ({@code INV-IDN-07}'s reasoning).
     */
    Optional<MerchantApiKey> findLiveFor(T unitOfWork, MerchantApiKeyId id);

    /**
     * The merchant's own keys, newest first — <strong>metadata only</strong>; no hash leaves
     * this port in a shape any surface renders. Tenant-scoped: the predicate is the caller's
     * authenticated merchant, in the statement ({@code INV-MER-01}).
     */
    List<MerchantApiKey> listFor(T unitOfWork, MerchantId tenant);

    /**
     * One key of this merchant, locked — the revocation's serialization point. Tenant-scoped:
     * {@code merchant_id = ?} rides in the statement, so an operator naming another merchant's
     * key gets the same empty answer as naming one that does not exist.
     */
    Optional<MerchantApiKey> findOwnedForUpdate(T unitOfWork, MerchantId tenant, MerchantApiKeyId id);

    /**
     * Applies {@code revoked}'s status conditionally on the from-state and appends the history
     * row when the write landed. {@code false} means another writer revoked it first, and the
     * caller converges rather than assuming ({@code INV-CON-01}).
     */
    boolean revoke(T unitOfWork, MerchantApiKey before, MerchantApiKey revoked, String reason);
}
