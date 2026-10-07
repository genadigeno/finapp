package com.finapp.app.crossborder;

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
 * The corridor administrator's doors (`P9-TSK-015`, `PHASE_9_PLAN.md` §9, ADR-0080 section 4):
 * corridor policy versions under four eyes, and the corridor kill switch - every route behind
 * {@code CROSSBORDER_ADMINISTER}, held by {@code FX_CONTROLLER}.
 */
@RestController
@RequestMapping(path = "/operator/cross-border", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CorridorAdministrationController {

    @NonNull private final CorridorAdministrationDesk desk;

    /** The versions, newest first - each with its corridors. */
    @GetMapping("/corridor-policies")
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    public CorridorAdministrationDesk.CorridorPolicyList listCorridorPolicies() {
        return desk.policies();
    }

    /** Proposes a whole new version - keyed per principal; frozen from here. */
    @PostMapping(path = "/corridor-policies", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public CorridorAdministrationDesk.CorridorPolicyReceipt proposeCorridorPolicy(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody CorridorPolicyRequest body) {
        return desk.propose(idempotencyKey, body);
    }

    /** Activates a pending version - a different person's act; retires its predecessor. */
    @PostMapping(path = "/corridor-policies/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    public CorridorAdministrationDesk.CorridorPolicyReceipt approveCorridorPolicy(
            @PathVariable("id") String id, @Valid @RequestBody CorridorDecisionRequest body) {
        return desk.approve(id, body.reason());
    }

    /** Rejects a pending version - anyone, the proposer's withdrawal included. */
    @PostMapping(path = "/corridor-policies/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    public CorridorAdministrationDesk.CorridorPolicyReceipt rejectCorridorPolicy(
            @PathVariable("id") String id, @Valid @RequestBody CorridorDecisionRequest body) {
        return desk.reject(id, body.reason());
    }

    /**
     * Sets a corridor's availability: {@code available=false} disables it at once (one person);
     * {@code available=true} proposes enabling it (a different person approves). Keyed.
     */
    @PostMapping(path = "/corridors/{corridor}/availability", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    @RequiresIdempotencyKey
    public CorridorAdministrationDesk.CorridorAvailabilityReceipt setCorridorAvailability(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("corridor") String corridor,
            @Valid @RequestBody CorridorAvailabilityRequest body) {
        return desk.availability(idempotencyKey, corridor, body);
    }

    /** Approves a corridor enable request - a different person's act; appends the enabling fact. */
    @PostMapping(path = "/corridor-enable-requests/{rid}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    public CorridorAdministrationDesk.CorridorEnableReceipt approveCorridorEnableRequest(
            @PathVariable("rid") String requestId, @Valid @RequestBody CorridorDecisionRequest body) {
        return desk.approveEnable(requestId, body.reason());
    }

    /** Rejects a corridor enable request - anyone, the proposer's withdrawal included. */
    @PostMapping(path = "/corridor-enable-requests/{rid}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CROSSBORDER_ADMINISTER)
    public CorridorAdministrationDesk.CorridorEnableReceipt rejectCorridorEnableRequest(
            @PathVariable("rid") String requestId, @Valid @RequestBody CorridorDecisionRequest body) {
        return desk.rejectEnable(requestId, body.reason());
    }

    // -----------------------------------------------------------------

    /**
     * A whole corridor policy version. Amounts and fractions are decimal strings - never JSON numbers -
     * so no fee or limit passes through a double.
     */
    @ClosedBody
    public record CorridorPolicyRequest(
            @NotEmpty List<@Valid @NotNull CorridorRequest> corridors,
            @NotBlank @Size(min = 1, max = 1000) String reason) {}

    /** One corridor: every term the version freezes. */
    @ClosedBody
    public record CorridorRequest(
            @NotBlank @Size(min = 1, max = 3) String source,
            @NotBlank @Size(min = 1, max = 3) String destination,
            @NotBlank @Size(min = 1, max = 2) String country,
            @NotEmpty @Size(min = 1, max = 16) List<@NotBlank @Size(min = 1, max = 32) String> rails,
            @NotBlank @Size(min = 1, max = 32) String feeFixed,
            @NotBlank @Size(min = 1, max = 32) String feeMargin,
            @NotBlank @Size(min = 1, max = 32) String feeRounding,
            @NotBlank @Size(min = 1, max = 32) String maximum,
            @NotNull Long screeningValidityHours,
            @NotNull Long deliveryEstimateHours,
            @NotNull @Size(max = 16) List<@NotBlank @Size(min = 1, max = 32) String> requiredData) {}

    /** A decision's reason. */
    @ClosedBody
    public record CorridorDecisionRequest(@NotBlank @Size(min = 1, max = 1000) String reason) {}

    /** Disable ({@code false}) or propose enabling ({@code true}), with the reason. */
    @ClosedBody
    public record CorridorAvailabilityRequest(
            @NotNull Boolean available, @NotBlank @Size(min = 1, max = 1000) String reason) {}
}
