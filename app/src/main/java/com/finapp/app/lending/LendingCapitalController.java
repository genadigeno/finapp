package com.finapp.app.lending;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
 * The lending treasury's doors (`P11-TSK-004`; ADR-0096 sections 4-6, PHASE_11_PLAN.md sections 9 and 11): capital
 * contributions under four eyes, each act behind {@code LENDING_CAPITAL_ADMINISTER} (role
 * {@code LENDING_TREASURY_OFFICER}) and keyed per principal ({@code lending.capital:<type>:<id>}); and the capital
 * report - headroom per currency, an audited report and never a metric (ADR-0072) - behind {@code LENDING_INVESTIGATE}.
 * An approval opens the contribution's expectation; capital exists only once the bank's statement shows the cash.
 */
@RestController
@RequestMapping(path = "/operator/lending", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LendingCapitalController {

    @NonNull private final LendingCapitalDesk desk;

    /** Proposes a contribution of the platform's own money - currency, amount, the transfer's bank reference. */
    @PostMapping(path = "/capital-contributions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LENDING_CAPITAL_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LendingCapitalDesk.CapitalContributionReceipt proposeCapitalContribution(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody ContributionProposalRequest body) {
        return desk.propose(idempotencyKey, body);
    }

    /** Approves a pending contribution - someone other than its proposer; opens its expectation. */
    @PostMapping(path = "/capital-contributions/{contributionId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LENDING_CAPITAL_ADMINISTER)
    @RequiresIdempotencyKey
    public LendingCapitalDesk.CapitalContributionReceipt approveCapitalContribution(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("contributionId") String contributionId,
            @Valid @RequestBody ContributionDecisionRequest body) {
        return desk.approve(idempotencyKey, contributionId, body.reason());
    }

    /** Rejects a pending contribution - anyone, the proposer's withdrawal included; a reason is required. */
    @PostMapping(path = "/capital-contributions/{contributionId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LENDING_CAPITAL_ADMINISTER)
    @RequiresIdempotencyKey
    public LendingCapitalDesk.CapitalContributionReceipt rejectCapitalContribution(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("contributionId") String contributionId,
            @Valid @RequestBody ContributionDecisionRequest body) {
        return desk.reject(idempotencyKey, contributionId, body.reason());
    }

    /**
     * The capital report: per supported currency the recognised capital, committed and outstanding principal, the
     * headroom and the safeguarding term, with the capital proof's verdict; and the most recent contributions with
     * their derived recognition. Audited per serving ({@code lending.ReportRead}).
     */
    @GetMapping(path = "/capital")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LendingCapitalDesk.CapitalReport readLendingCapital() {
        return desk.capital();
    }

    // -----------------------------------------------------------------

    /**
     * A contribution: its currency (ISO 4217), its amount in integer minor units of that currency, the bank remittance
     * reference the corporate transfer will carry ({@code LCAP-} and 4 to 12 digits), and the proposer's reason.
     */
    @ClosedBody
    public record ContributionProposalRequest(
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull Long amountMinor,
            @NotBlank @Size(min = 9, max = 17) String reference,
            @Size(max = 1000) String reason) {}

    /** A decision's reason - required, judged by the domain ({@code lending.ReasonRequired}). */
    @ClosedBody
    public record ContributionDecisionRequest(@Size(max = 1000) String reason) {}
}
