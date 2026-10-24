package com.finapp.app.risk;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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
 * The risk officer's policy doors (`P13-TSK-002`; ADR-0109, {@code PHASE_13_PLAN.md} sections 9 and 11,
 * {@code RISK_LIFECYCLES.md} section 4.1): risk policy versions under four eyes, each act keyed per principal
 * ({@code risk.policy:<type>:<id>}), the activation a {@code MULTI_FACTOR} act, and the reads - the version in force
 * for an operation, and any version by id (what the second person reviews before deciding) - every route behind
 * {@code RISK_POLICY_ADMINISTER}, held by {@code RISK_OFFICER}. Thresholds, weights and bands are the policy's content
 * (CONFIDENTIAL; the amounts RESTRICTED-FINANCIAL) and leave the platform only here, on the operator surface; reasons
 * are RESTRICTED-PII and never served. The analyst reads a policy through the assessment that pinned it (`-004`),
 * never here; no customer or merchant route reads a policy ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/policies", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskPolicyController {

    @NonNull private final RiskPolicyDesk desk;

    /** Proposes a whole new policy version for an operation - complete, judged, and frozen from this moment. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RiskPolicyDesk.RiskPolicyReceipt proposeRiskPolicy(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody RiskPolicyProposalRequest body) {
        return desk.propose(idempotencyKey, body);
    }

    /**
     * Activates a pending version - a different person's act, from a {@code MULTI_FACTOR} session; retires the
     * operation's predecessor in the same transaction.
     */
    @PostMapping(path = "/{versionId}/activation", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public RiskPolicyDesk.RiskPolicyReceipt activateRiskPolicy(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody RiskPolicyDecisionRequest body) {
        return desk.activate(idempotencyKey, versionId, body.reason());
    }

    /** Rejects a pending version - anyone, the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{versionId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    public RiskPolicyDesk.RiskPolicyReceipt rejectRiskPolicy(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody RiskPolicyDecisionRequest body) {
        return desk.reject(idempotencyKey, versionId, body.reason());
    }

    /** The operation's version {@code ACTIVE} now; {@code 409 risk.PolicyNotActive} when none is. */
    @GetMapping
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    public RiskPolicyDesk.RiskPolicyView readRiskPolicyInForce(@RequestParam("operation") String operation) {
        return desk.inForce(operation);
    }

    /** One policy version by id, in any state - the proposal a second person reviews before deciding it. */
    @GetMapping(path = "/{versionId}")
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    public RiskPolicyDesk.RiskPolicyView readRiskPolicyVersion(@PathVariable("versionId") String versionId) {
        return desk.version(versionId);
    }

    // -----------------------------------------------------------------

    /**
     * A whole policy version for one operation. Amounts are integer minor units of {@code currency} (the reference
     * {@code EUR}); {@code engine} names the engine version ({@code RISK_ENGINE_V1}); {@code rules} are evaluated in the
     * order given, each a {@code RuleKind} with its declared parameters by name - whole numbers, names and (for a
     * corridor set) a list of codes - a weight (1 to 1000) and/or a hard decision. A judged operation states its
     * {@code scoreBands} (contiguous over 0..1000), {@code failSafeBands} (contiguous amount bands from 0, the last
     * open-ended, each {@code ALLOW} or {@code DENY}), {@code challengeAssurance} ({@code MULTI_FACTOR}) and
     * {@code overrideThresholdMinor}; a withdrawal its {@code holdValidityHours} (72 when omitted), as does a card
     * payment's version whose bands or rules can decide {@code HOLD} (`P13-TSK-010`; none for one that never holds); the
     * {@code MONITORING} operation its {@code ratingBands} alone. Every value is judged by the domain
     * ({@code risk.PolicyInvalid}).
     */
    @ClosedBody
    public record RiskPolicyProposalRequest(
            @NotBlank @Size(min = 1, max = 32) String operation,
            @NotBlank @Size(min = 1, max = 32) String engine,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull @Size(min = 1, max = 64) List<@Valid @NotNull RiskRuleRequest> rules,
            @Size(max = 32) List<@Valid @NotNull RiskScoreBandRequest> scoreBands,
            @Size(max = 32) List<@Valid @NotNull RiskRatingBandRequest> ratingBands,
            @Size(max = 32) List<@Valid @NotNull RiskFailSafeBandRequest> failSafeBands,
            @Size(min = 1, max = 32) String challengeAssurance,
            Long overrideThresholdMinor,
            Integer holdValidityHours,
            @Size(max = 1000) String reason) {}

    /** One rule: its kind, the parameters the kind declares (by name), a weight and/or a hard decision. */
    @ClosedBody
    public record RiskRuleRequest(
            @NotBlank @Size(min = 1, max = 64) String kind,
            @Size(max = 16) Map<@NotBlank @Size(min = 1, max = 64) String, @NotNull Object> parameters,
            Integer weight,
            @Size(min = 1, max = 16) String hardDecision) {}

    /** A score band: the closed interval {@code [from, to]} of 0..1000 and the decision it maps to. */
    @ClosedBody
    public record RiskScoreBandRequest(@NotNull Integer from, @NotNull Integer to, @NotBlank @Size(max = 16) String decision) {}

    /** A rating band (the MONITORING operation): the closed interval of the signal score and the rating it maps to. */
    @ClosedBody
    public record RiskRatingBandRequest(@NotNull Integer from, @NotNull Integer to, @NotBlank @Size(max = 16) String rating) {}

    /** A fail-safe band: amounts {@code [fromMinor, toMinor)}, {@code toMinor} omitted for the last, ALLOW or DENY. */
    @ClosedBody
    public record RiskFailSafeBandRequest(@NotNull Long fromMinor, Long toMinor, @NotBlank @Size(max = 16) String decision) {}

    /** A decision's reason - required, judged by the domain ({@code risk.ReasonRequired}). */
    @ClosedBody
    public record RiskPolicyDecisionRequest(@Size(max = 1000) String reason) {}
}
