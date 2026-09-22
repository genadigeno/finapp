package com.finapp.merchant;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A merchant's API credential (`P6-TSK-002`, ADR-0052): the platform's fourth authentication
 * vocabulary, and the first whose subject is a party the platform does not own.
 *
 * <p><strong>What this aggregate holds is a HASH, never a secret.</strong> The plaintext exists
 * for the length of one issuance response and is then unreachable from every read path — there
 * is no column to leak and no accessor to misuse ({@code INV-IDN-01}). What produced the hash
 * is recorded beside it ({@code INV-IDN-02}), which is why {@link #algorithm()} is stored
 * rather than assumed: a credential that misreports its own derivation is worse than one that
 * does not report it.
 *
 * <p><strong>An API key has none of the controls a session gets from being short-lived</strong>
 * — no expiry, no human at a keyboard, no browser to close — so every one of them is replaced
 * deliberately: the lookup is authoritative on every request (never cached, so revocation and
 * the merchant's own suspension bite on the next call on every instance), and the merchant's
 * status is joined into that lookup rather than consulted afterwards. Those are the
 * authenticator's properties; what lives here is the key's own state.
 */
public final class MerchantApiKey {

    private final MerchantApiKeyId id;
    private final MerchantId merchantId;
    private final Sensitive<String> secretHash;
    private final String algorithm;
    private final MerchantApiKeyStatus status;
    private final Instant issuedAt;
    private final String issuedBy;
    private final Instant revokedAt;

    private MerchantApiKey(
            MerchantApiKeyId id,
            MerchantId merchantId,
            Sensitive<String> secretHash,
            String algorithm,
            MerchantApiKeyStatus status,
            Instant issuedAt,
            String issuedBy,
            Instant revokedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(secretHash, "secretHash must not be null");
        requireText(secretHash.expose(), "secretHash");
        this.secretHash = secretHash;
        this.algorithm = requireText(algorithm, "algorithm");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        this.issuedBy = requireText(issuedBy, "issuedBy");
        this.revokedAt = revokedAt;
        // The coherence the schema also holds: a revocation instant exists exactly when the
        // key is revoked, and never precedes issuance.
        if ((status == MerchantApiKeyStatus.REVOKED) != (revokedAt != null)) {
            throw new IllegalArgumentException(
                    "revokedAt is present exactly when the key is REVOKED");
        }
        if (revokedAt != null && revokedAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("revokedAt must not precede issuedAt");
        }
    }

    /** A freshly issued key. Born {@code ACTIVE}; the secret is the caller's to hand over once. */
    public static MerchantApiKey issue(
            IdGenerator ids,
            Clock clock,
            MerchantId merchantId,
            MerchantApiKeySecret secret,
            String issuedBy) {
        Objects.requireNonNull(secret, "secret must not be null");
        return new MerchantApiKey(
                MerchantApiKeyId.next(ids),
                merchantId,
                secret.hash(),
                MerchantApiKeySecret.ALGORITHM,
                MerchantApiKeyStatus.ACTIVE,
                Instant.now(clock),
                issuedBy,
                null);
    }

    /** Reconstitutes from storage. Applies the coherence; a corrupt row is refused at read. */
    public static MerchantApiKey rehydrate(
            MerchantApiKeyId id,
            MerchantId merchantId,
            Sensitive<String> secretHash,
            String algorithm,
            MerchantApiKeyStatus status,
            Instant issuedAt,
            String issuedBy,
            Instant revokedAt) {
        return new MerchantApiKey(
                id, merchantId, secretHash, algorithm, status, issuedAt, issuedBy, revokedAt);
    }

    /** {@code ACTIVE → REVOKED}, terminal. A revoked key is never reinstated. */
    public MerchantApiKey revoke(Clock clock) {
        if (!status.permittedTransitions().contains(MerchantApiKeyStatus.REVOKED)) {
            throw new IllegalMerchantApiKeyTransitionException(status);
        }
        return new MerchantApiKey(
                id,
                merchantId,
                secretHash,
                algorithm,
                MerchantApiKeyStatus.REVOKED,
                issuedAt,
                issuedBy,
                Instant.now(clock));
    }

    /**
     * True when {@code presented} is this key's secret — the constant-time comparison lives in
     * {@link MerchantApiKeySecret#matches}, and the stored hash never leaves this aggregate.
     */
    public boolean verifies(MerchantApiKeySecret presented) {
        Objects.requireNonNull(presented, "presented must not be null");
        return presented.matches(secretHash.expose());
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /**
     * The stored hash — <strong>package-private, deliberately</strong>: the one writer that
     * persists it lives beside this class, and no accessor exists for any other module. The
     * hash is {@code INV-AUD-02}'s subject as firmly as the secret is, so the boundary that
     * keeps it out of {@code app} is the language's, not a convention.
     */
    Sensitive<String> storedHash() {
        return secretHash;
    }

    public MerchantApiKeyId id() {
        return id;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public String algorithm() {
        return algorithm;
    }

    public MerchantApiKeyStatus status() {
        return status;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public String issuedBy() {
        return issuedBy;
    }

    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    /**
     * Masked: the hash is {@code INV-AUD-02}'s subject as firmly as the secret is, and a
     * record's generated {@code toString} in a log is the accident that rule exists to stop.
     */
    @Override
    public String toString() {
        return "MerchantApiKey[id=" + id + ", merchant=" + merchantId + ", status=" + status
                + ", secretHash=" + Sensitive.MASK + "]";
    }
}
