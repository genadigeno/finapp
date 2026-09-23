package com.finapp.app.merchant;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * `/v1/operator/merchants/{id}/api-keys` (`P6-TSK-002`, ADR-0052): provisioning a merchant's
 * credentials, as an <strong>operator</strong> act.
 *
 * <h2>Why the operator issues, and the merchant does not</h2>
 *
 * <p>A merchant cannot mint its own first key — there is no credential to authenticate the
 * request with — and letting a key mint further keys would make a single disclosed secret
 * self-perpetuating: revoking it would not end the access, because the attacker would already
 * hold one it issued. So issuance is the platform's act, audited with the operator who
 * performed it, and every key's lineage ends at a person. Merchant self-service key
 * management is a later product surface with its own security design, not an omission here.
 *
 * <p><strong>The secret appears in exactly one response and never again.</strong> The
 * {@code 201} body carries it; the list below carries metadata only; no read can produce it,
 * because it is not stored ({@code INV-IDN-01}). A retried issuance converges on the same key
 * and answers with {@code alreadyIssued} and a null secret rather than inventing a second
 * credential — see {@link com.finapp.merchant.MerchantApiKeys}.
 */
@RestController
@RequestMapping(
        path = "/operator/merchants/{id}/api-keys",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class MerchantApiKeyController {

    @NonNull private final MerchantApiKeyOperations keys;

    /**
     * Issues a key. The {@code 201} body carries the secret <strong>once</strong>; a retried
     * key converges on the same credential with {@code alreadyIssued: true} and no secret.
     */
    @PostMapping
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantApiKeyOperations.IssuedKeyView issue(
            @PathVariable("id") String merchantId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return keys.issue(merchantId, idempotencyKey);
    }

    /**
     * The merchant's keys, newest first — metadata only, never a secret and never a hash.
     *
     * <p>Named {@code listApiKeys} rather than {@code list} for the reason
     * {@code MerchantOperationsController.viewMerchant} records — and this is the SECOND
     * occurrence, one task later: a generic handler name collides with another controller's,
     * and springdoc renumbers the OTHER endpoint's {@code operationId}, breaking a surface
     * nobody touched. Here it was {@code /v1/beneficiaries} {@code list -> list_1}. Twice is
     * a mechanism rather than a third comment, so {@code OpenApiContractTest} now refuses any
     * suffixed {@code operationId} outright.
     */
    @GetMapping
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public List<MerchantApiKeyOperations.KeyView> listApiKeys(
            @PathVariable("id") String merchantId) {
        return keys.list(merchantId);
    }

    /**
     * Revokes a key with the operator's required reason ({@code INV-AUD-03}). Terminal: a
     * revoked key is never reinstated, and a repeat converges on this same {@code 204}.
     * Unknown, malformed and another merchant's key are one {@code 404}.
     *
     * <p>No idempotency key: the machine is the idempotency ({@code INV-IDEM-01} through
     * state), the adjustment-rejection reasoning.
     */
    @DeleteMapping("/{keyId}")
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable("id") String merchantId,
            @PathVariable("keyId") String keyId,
            @Valid @RequestBody MerchantStandingRequest body) {
        keys.revoke(merchantId, keyId, body);
    }
}
