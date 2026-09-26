package com.finapp.app.payments;

import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
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
 * The operator's routing surface (`P7-TSK-003`, ADR-0060): how money travels, as three acts
 * under {@link PermissionName#PAYMENT_ROUTING_ADMINISTER}.
 *
 * <p><strong>No {@code PUT} and no {@code DELETE} on the policy, and that is the
 * contract</strong> (the fee-schedule surface's sentence): a version is immutable, and a
 * routing change is a {@code POST} of a new version effective forward. Availability is the
 * one mutable fact, and its {@code POST} records an act rather than editing a document —
 * the newest act is the fact.
 *
 * <p>Version creation is <strong>keyed</strong> ({@code INV-IDEM-01}; the divergence from
 * the unkeyed fee version is reasoned at {@code RoutingAdministration.IDEMPOTENCY_SCOPE}).
 *
 * <p>Handler names are deliberately distinctive (the springdoc {@code operationId} rule,
 * {@code OpenApiContractTest}).
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RoutingPolicyController {

    @NonNull private final RoutingPolicyOperations routing;

    /** Adds an immutable version, effective forward. Backdated is a 422 (`INV-HIST-04`). */
    @PostMapping(
            path = "/operator/routing-policy/versions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYMENT_ROUTING_ADMINISTER)
    @ResponseStatus(HttpStatus.CREATED)
    public RoutingPolicyOperations.VersionView createRoutingPolicyVersion(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody CreateRoutingPolicyVersionRequest body) {
        return routing.createVersion(body, idempotencyKey);
    }

    /**
     * Records a rail as in or out of service (ADR-0060 §4) — the fact every instance routes
     * by, reasoned and audited. Setting the state a rail already has converges on it.
     */
    @PostMapping(
            path = "/operator/rails/{rail}/availability",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYMENT_ROUTING_ADMINISTER)
    public RoutingPolicyOperations.AvailabilityView setRailAvailability(
            @PathVariable("rail") String rail, @Valid @RequestBody SetRailAvailabilityRequest body) {
        return routing.setAvailability(rail, body);
    }

    /**
     * A payment's routing explanation: the pinned version, the judged inputs, every step.
     * A privileged read of somebody else's payment — audited per read. Unknown and unrouted
     * are one 404.
     */
    @GetMapping("/operator/payments/{intentId}/routing")
    @RequiresPermission(PermissionName.PAYMENT_ROUTING_ADMINISTER)
    public RoutingPolicyOperations.ExplanationView viewPaymentRouting(
            @PathVariable("intentId") String intentId) {
        return routing.explain(intentId);
    }
}
