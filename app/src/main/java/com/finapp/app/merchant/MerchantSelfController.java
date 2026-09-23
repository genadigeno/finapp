package com.finapp.app.merchant;

import com.finapp.merchant.AuthenticatedMerchant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * `/v1/merchant/me` (`P6-TSK-002`): what the merchant sees of itself — the platform's first
 * surface authenticated by an API key, and the one that makes the tenancy primitive real.
 *
 * <h2>Why {@code /me} and not an id-addressed route</h2>
 *
 * <p>The tenant comes from the <strong>authenticated credential</strong> and from nowhere else
 * ({@code INV-MER-01}). A route of the shape {@code /v1/merchant/'{id}'} would invite a caller
 * to name a tenant, and a tenant a caller can name is not a tenant — ADR-0031's defect at the
 * multi-tenant boundary. {@code /me} has nothing to name, which is the point: it is
 * structurally incapable of addressing another merchant, and the merchant-scoped routes that
 * DO take identifiers ({@code P6-TSK-009}'s transactions, the key routes below) carry
 * {@code merchant_id = ?} in the statement beside whatever the caller supplied.
 *
 * <p>The {@code /v1/me} precedent for a new actor population — and it earns its place rather
 * than merely echoing one: an integration that has just been handed a credential needs a call
 * that proves the credential works and says which merchant it speaks for, before it commits
 * to a checkout flow that would fail obscurely.
 */
@RestController
@RequestMapping(path = "/merchant", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class MerchantSelfController {

    @NonNull private final MerchantSelfView view;

    /**
     * The authenticated merchant's own record. Carries no other tenant's anything, and
     * carries nothing about the key beyond the id the caller already holds.
     */
    @GetMapping("/me")
    @RequiresMerchantKey
    public MerchantSelfView.SelfView me(HttpServletRequest request) {
        AuthenticatedMerchant tenant = MerchantKeyAuthenticationInterceptor.require(request);
        return view.of(tenant);
    }
}
