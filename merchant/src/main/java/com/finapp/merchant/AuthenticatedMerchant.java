package com.finapp.merchant;

import java.util.Objects;

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
 */
public record AuthenticatedMerchant(MerchantId merchantId, MerchantApiKeyId keyId) {

    public AuthenticatedMerchant {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(keyId, "keyId must not be null");
    }
}
