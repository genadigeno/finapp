package com.finapp.app.credit;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scorecard administrator's doors (`P10-TSK-011`; ADR-0086 section 3, PHASE_10_PLAN.md section 11): scorecard
 * model versions under four eyes - every route behind {@code CREDIT_POLICY_ADMINISTER}, held by
 * {@code CREDIT_POLICY_OFFICER}, and every act keyed per principal.
 */
@RestController
@RequestMapping(path = "/operator/credit/scorecards", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ScorecardAdministrationController {

    @NonNull private final ScorecardAdministrationDesk desk;

    /** Proposes a whole new version - its bands born with it and frozen. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public ScorecardAdministrationDesk.ScorecardReceipt proposeScorecard(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody ScorecardProposalRequest body) {
        return desk.propose(idempotencyKey, body);
    }

    /** Activates a pending version - a different person's act; retires its predecessor. */
    @PostMapping(path = "/{versionId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    public ScorecardAdministrationDesk.ScorecardReceipt approveScorecard(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody ScorecardDecisionRequest body) {
        return desk.approve(idempotencyKey, versionId, body.reason());
    }

    /** Rejects a pending version - anyone, the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/{versionId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    public ScorecardAdministrationDesk.ScorecardReceipt rejectScorecard(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("versionId") String versionId,
            @Valid @RequestBody ScorecardDecisionRequest body) {
        return desk.reject(idempotencyKey, versionId, body.reason());
    }

    // -----------------------------------------------------------------

    /** A whole points table: the family, the base and every scored attribute's bands, with the proposer's reason. */
    @ClosedBody
    public record ScorecardProposalRequest(
            @NotBlank @Size(min = 1, max = 32) String family,
            @NotNull Integer basePoints,
            @NotEmpty @Size(max = 32) List<@Valid @NotNull AttributeBandsRequest> attributes,
            @Size(max = 1000) String reason) {}

    /** One attribute's absent band and its value bands, in order. */
    @ClosedBody
    public record AttributeBandsRequest(
            @NotBlank @Size(min = 1, max = 64) String code,
            @NotNull Integer absentPoints,
            @NotEmpty @Size(max = 32) List<@Valid @NotNull BandRequest> bands) {}

    /** {@code [lower, upper)} - either bound absent for unbounded - or a code set; never both. */
    @ClosedBody
    public record BandRequest(
            Long lower, Long upper, @Size(max = 64) List<@NotBlank @Size(min = 1, max = 64) String> codes,
            @NotNull Integer points) {}

    /** A decision's reason - required, judged by the domain ({@code credit.ReasonRequired}). */
    @ClosedBody
    public record ScorecardDecisionRequest(@Size(max = 1000) String reason) {}
}
