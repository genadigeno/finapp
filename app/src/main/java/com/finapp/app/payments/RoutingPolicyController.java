package com.finapp.app.payments;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.identity.AssuranceLevel;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
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
import org.springframework.web.bind.annotation.RequestParam;
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
@RecordsRefusals
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RoutingPolicyController {

    @NonNull private final RoutingPolicyOperations routing;

    /** Adds an immutable version, effective forward. Backdated is a 422 (`INV-HIST-04`). */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(
            path = "/operator/routing-policy/versions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYMENT_ROUTING_ADMINISTER)
    // The keyed contract's own gate (P7-DOC-001): without it a missing key met Spring's
    // generic 400 instead of api.IdempotencyKeyRequired, and the key's charset went
    // unchecked - the one keyed handler the review found without it.
    @RequiresIdempotencyKey
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
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(
            path = "/operator/rails/{rail}/availability",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYMENT_ROUTING_ADMINISTER)
    // Keyed since P17-TSK-032 (finapp-admin-panel ADR-C135 decision 2): a lost response retried answers the original act and
    // records no second one on the trail.
    @RequiresIdempotencyKey
    public RoutingPolicyOperations.AvailabilityView setRailAvailability(
            @PathVariable("rail") String rail,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody SetRailAvailabilityRequest body) {
        return routing.setAvailability(rail, body, idempotencyKey);
    }

    /**
     * Every declared rail's availability, as every instance routes by it (`P17-TSK-032`, gap G-47): the newest act's fact
     * with who recorded it, or available with no reason for a rail never switched. Configuration - unrecorded.
     */
    @GetMapping("/operator/rails")
    @RequiresPermission(PermissionName.PAYMENT_ROUTING_ADMINISTER)
    public RoutingPolicyOperations.RailList listRails() {
        return routing.rails();
    }

    /**
     * The routing policy versions, newest number first (`P17-TSK-032`, gap G-46): a §7 collection - {@code limit} 50 unless
     * named, 200 at most - each version with its rules. Configuration - unrecorded.
     */
    @GetMapping("/operator/routing-policy/versions")
    @RequiresPermission(PermissionName.PAYMENT_ROUTING_ADMINISTER)
    public RoutingPolicyOperations.VersionPage listRoutingPolicyVersions(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return routing.versions(cursor, limit);
    }

    /** One routing policy version by its number - what a payment's explanation names. Unknown and malformed are one 404. */
    @GetMapping("/operator/routing-policy/versions/{version}")
    @RequiresPermission(PermissionName.PAYMENT_ROUTING_ADMINISTER)
    public RoutingPolicyOperations.VersionView viewRoutingPolicyVersion(@PathVariable("version") String version) {
        return routing.version(version);
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
