package com.finapp.merchant;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What a successful key authentication establishes (`P6-TSK-002`): the merchant this request
 * acts as, and the key it acted on.
 *
 * <p><strong>The tenant, and the only legitimate source of one.</strong> Every merchant-scoped
 * statement carries {@code merchant_id = ?} from {@link #merchantId()} ({@code INV-MER-01});
 * no merchant-facing surface may take a tenant from a request body, a path variable or a
 * header, because a tenant a caller can name is not a tenant (ADR-0031's defect at the
 * multi-tenant boundary).
 *
 * <p>The key id travels too, so an audit record can name WHICH credential acted without ever
 * naming the secret — which is why {@link MerchantApiKeyId} is public.
 *
 * <p><strong>The key's scopes travel with it</strong> (`P12-TSK-021`): what the key was issued
 * to do beyond the merchant's own reads and payouts, read in the same authoritative lookup that
 * established the tenant - never cached, so a key revoked and re-issued without a scope loses it
 * on the next request on every instance. A door that needs one asks {@link #holds}.
 *
 * <p><strong>So does who issued it</strong> (the Phase 12 -> 13 gate's A5-F1): {@code issuedBy} is the issuing operator's
 * actor id as `V003` froze it ({@code merchant_api_key.issued_by}), read in the same lookup. A door that moves the
 * merchant's money to a customer - the BNPL refund, the BNPL dispute's acceptance - refuses a key whose issuer is that
 * customer's own party, or one it cannot place in a party (fail closed): the person who saw the secret never refunds
 * themselves through it. Empty only for a value built without a key row (a test's); a door that needs it then refuses.
 */
public record AuthenticatedMerchant(MerchantId merchantId, MerchantApiKeyId keyId, Set<MerchantApiKeyScope> scopes,
        java.util.Optional<String> issuedBy) {

    public AuthenticatedMerchant {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(keyId, "keyId must not be null");
        Objects.requireNonNull(scopes, "scopes must not be null");
        Objects.requireNonNull(issuedBy, "issuedBy must not be null");
        scopes = scopes.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(EnumSet.copyOf(scopes));
    }

    /** A key with {@code scopes} and no issuer known - a value built without the key row. */
    public AuthenticatedMerchant(MerchantId merchantId, MerchantApiKeyId keyId, Set<MerchantApiKeyScope> scopes) {
        this(merchantId, keyId, scopes, java.util.Optional.empty());
    }

    /** A key with no scope - every key issued before `P12-TSK-021`, and every key issued without one. */
    public AuthenticatedMerchant(MerchantId merchantId, MerchantApiKeyId keyId) {
        this(merchantId, keyId, Set.of());
    }

    /** Whether the authenticated key was issued with {@code scope}. */
    public boolean holds(MerchantApiKeyScope scope) {
        return scopes.contains(Objects.requireNonNull(scope, "scope must not be null"));
    }

    /**
     * Never the issuer: {@code issued_by} is an operator's identity, {@code RESTRICTED-PII} ({@code DATA_CLASSIFICATION.md})
     * - a record's generated {@code toString} in a log is the accident {@code INV-AUD-02} exists to stop.
     */
    @Override
    public String toString() {
        return "AuthenticatedMerchant[merchantId=" + merchantId + ", keyId=" + keyId + ", scopes=" + scopes + "]";
    }
}
