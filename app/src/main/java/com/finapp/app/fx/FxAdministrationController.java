package com.finapp.app.fx;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * The FX controller's doors (`P9-TSK-007`, `PHASE_9_PLAN.md` §9, ADR-0075 §7): pricing policy
 * versions under four eyes, and the pair and provider kill switch - every route behind
 * {@code FX_ADMINISTER}, held by {@code FX_CONTROLLER} alone.
 *
 * <p><strong>Deliberately absent</strong>: any door that types a rate, books a trade or executes a
 * cover. Rates come only from providers ({@code INV-FX-02}); this surface sets the margin around
 * them and whether money may move at all.
 */
@RestController
@RequestMapping(path = "/operator/fx", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FxAdministrationController {

    @NonNull private final FxAdministrationDesk desk;

    /** The versions, newest first - each with its pairs. */
    @GetMapping("/pricing-policies")
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    public FxAdministrationDesk.PolicyList listFxPricingPolicies() {
        return desk.policies();
    }

    /** Proposes a whole new version - keyed per principal; frozen from here. */
    @PostMapping(path = "/pricing-policies", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public FxAdministrationDesk.PolicyReceipt proposeFxPricingPolicy(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody PricingPolicyRequest body) {
        return desk.propose(idempotencyKey, body);
    }

    /** Activates a pending version - a different person's act; retires its predecessor. */
    @PostMapping(path = "/pricing-policies/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    public FxAdministrationDesk.PolicyReceipt approveFxPricingPolicy(
            @PathVariable("id") String id, @Valid @RequestBody FxDecisionRequest body) {
        return desk.approve(id, body.reason());
    }

    /** Rejects a pending version - anyone, the proposer's withdrawal included. */
    @PostMapping(path = "/pricing-policies/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    public FxAdministrationDesk.PolicyReceipt rejectFxPricingPolicy(
            @PathVariable("id") String id, @Valid @RequestBody FxDecisionRequest body) {
        return desk.reject(id, body.reason());
    }

    /**
     * Sets a pair's availability: {@code available=false} disables it at once (one person);
     * {@code available=true} proposes enabling it (a different person approves). Keyed.
     */
    @PostMapping(path = "/pairs/{pair}/availability", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    @RequiresIdempotencyKey
    public FxAdministrationDesk.AvailabilityReceipt setFxPairAvailability(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("pair") String pair,
            @Valid @RequestBody AvailabilityRequest body) {
        return desk.pairAvailability(idempotencyKey, pair, body);
    }

    /** Sets a declared provider's availability - the pair route's rules. Keyed. */
    @PostMapping(path = "/providers/{code}/availability", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    @RequiresIdempotencyKey
    public FxAdministrationDesk.AvailabilityReceipt setFxProviderAvailability(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("code") String code,
            @Valid @RequestBody AvailabilityRequest body) {
        return desk.providerAvailability(idempotencyKey, code, body);
    }

    /** Approves an enable request - a different person's act; appends the enabling fact. */
    @PostMapping(path = "/enable-requests/{rid}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    public FxAdministrationDesk.EnableReceipt approveFxEnableRequest(
            @PathVariable("rid") String requestId, @Valid @RequestBody FxDecisionRequest body) {
        return desk.approveEnable(requestId, body.reason());
    }

    /** Rejects an enable request - anyone, the proposer's withdrawal included. */
    @PostMapping(path = "/enable-requests/{rid}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FX_ADMINISTER)
    public FxAdministrationDesk.EnableReceipt rejectFxEnableRequest(
            @PathVariable("rid") String requestId, @Valid @RequestBody FxDecisionRequest body) {
        return desk.rejectEnable(requestId, body.reason());
    }

    // -----------------------------------------------------------------

    /**
     * A whole pricing policy version. Amounts and fractions are decimal strings - never JSON
     * numbers - so no margin or bound passes through a double.
     */
    @ClosedBody
    public record PricingPolicyRequest(
            @NotEmpty List<@Valid @NotNull PairRequest> pairs,
            @NotNull Integer openQuoteCap,
            @NotBlank @Size(min = 1, max = 1000) String reason) {}

    /** One (pair, purpose): every term the version freezes. */
    @ClosedBody
    public record PairRequest(
            @NotBlank String source,
            @NotBlank String destination,
            @NotBlank String purpose,
            @NotEmpty List<@NotBlank String> providers,
            @NotBlank String spread,
            @NotBlank String markup,
            @NotNull Integer rateScale,
            @NotBlank String rateRounding,
            @NotBlank String amountRounding,
            @NotBlank String marginRounding,
            @NotNull Long windowSeconds,
            @NotNull Long coverMarginSeconds,
            @NotBlank String band,
            @NotNull Long referenceMaxAgeSeconds,
            @NotBlank String sourceMinimum,
            @NotBlank String sourceMaximum,
            @NotBlank String destinationMinimum,
            @NotBlank String destinationMaximum) {}

    /** A decision's reason. */
    @ClosedBody
    public record FxDecisionRequest(@NotBlank @Size(min = 1, max = 1000) String reason) {}

    /** Disable ({@code false}) or propose enabling ({@code true}), with the reason. */
    @ClosedBody
    public record AvailabilityRequest(@NotNull Boolean available, @NotBlank @Size(min = 1, max = 1000) String reason) {}
}
