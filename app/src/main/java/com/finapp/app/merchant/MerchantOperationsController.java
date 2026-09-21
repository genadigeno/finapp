package com.finapp.app.merchant;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * `/v1/operator/merchants` (`P6-TSK-003`): the commercial counterparty's operator surface —
 * onboarding gated on the KYB projection, and the three reasoned standing moves.
 *
 * <p><strong>Two permissions, one population</strong> ({@code MERCHANT_ADMINISTRATOR}):
 * onboarding checks {@code MERCHANT_ONBOARD}; the standing moves check
 * {@code MERCHANT_ADMINISTER} — precise vocabulary, coarse bundling, the ledger surfaces'
 * recorded reasoning. Every move demands the operator's reason in the body
 * ({@code INV-AUD-03}); none carries an idempotency key, because the machine is the
 * idempotency ({@code INV-IDEM-01} through state — the adjustment-approval reasoning): a
 * retried suspension converges on the suspended merchant with this same {@code 200}.
 *
 * <p><strong>Onboarding keeps the full keyed machinery</strong>: a duplicated onboarding is
 * the duplicate-effect vector — a second merchant and a second payable account.
 */
@RestController
@RequestMapping(path = "/operator/merchants", produces = MediaType.APPLICATION_JSON_VALUE)
public class MerchantOperationsController {

    private final MerchantOperations merchants;

    public MerchantOperationsController(MerchantOperations merchants) {
        this.merchants = Objects.requireNonNull(merchants, "merchants must not be null");
    }

    /**
     * Onboards the merchant and opens its books, or replays the recorded outcome for a
     * retried key ({@code INV-IDEM-01}); a reused key for a different request is the distinct
     * {@code 409} ({@code INV-IDEM-03}). The KYB refusal is one {@code 422} that conflates
     * its causes — the surface is not an oracle over parties.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ONBOARD)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantOperations.MerchantView onboard(
            @Valid @RequestBody OnboardMerchantRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return merchants.onboard(body, idempotencyKey);
    }

    /**
     * The merchant as it stands. Unknown and malformed are one {@code 404}.
     *
     * <p><strong>Named {@code viewMerchant}, not {@code view} — and the contract test is why.</strong>
     * springdoc derives each operation's {@code operationId} from the handler's method name and
     * disambiguates collisions with numeric suffixes, so a third {@code view} on this platform
     * renumbered the two that already existed ({@code /v1/me/kyb} {@code view -> view_1},
     * {@code /v1/ledger/adjustments/'{id}'} {@code view_1 -> view_2}). A generated client keys its
     * method names off {@code operationId}: that is a breaking change to two shipped endpoints,
     * caused by a name chosen here. Found by {@code OpenApiContractTest}'s exact comparison —
     * precisely the failure mode its javadoc says a cleverer classifier would miss.
     */
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public MerchantOperations.MerchantView viewMerchant(@PathVariable("id") String id) {
        return merchants.view(id);
    }

    /** Freezes new dispatches, reasoned. Landed money still lands; reversible. */
    @PostMapping(path = "/{id}/suspension", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public MerchantOperations.MerchantView suspend(
            @PathVariable("id") String id, @Valid @RequestBody MerchantStandingRequest body) {
        return merchants.suspend(id, body);
    }

    /** Lifts the freeze, reasoned. */
    @PostMapping(path = "/{id}/reinstatement", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public MerchantOperations.MerchantView reinstate(
            @PathVariable("id") String id, @Valid @RequestBody MerchantStandingRequest body) {
        return merchants.reinstate(id, body);
    }

    /** Ends the relationship, reasoned. Terminal; the books remain ({@code INV-HIST-01}). */
    @PostMapping(path = "/{id}/closure", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public MerchantOperations.MerchantView close(
            @PathVariable("id") String id, @Valid @RequestBody MerchantStandingRequest body) {
        return merchants.close(id, body);
    }
}
