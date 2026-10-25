package com.finapp.app.risk;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
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
 * The risk analyst's case doors (`P13-TSK-016`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.8; {@code RISK_LIFECYCLES.md}
 * section 4.6; ADR-0110 decisions 2, 5-7): a case opened by hand (on an open alert, or on a kind and a subject), the
 * investigator's acts (take, note, evidence by reference, escalate - each carrying the revision the analyst's screen
 * read), and a disposition proposed, approved by a second holder from a {@code MULTI_FACTOR} session, or rejected. The
 * acts behind {@code RISK_INVESTIGATE}, the decisions behind {@code RISK_CASE_APPROVE} (both held by {@code RISK_ANALYST}:
 * four eyes is two holders of the role), each keyed per principal ({@code risk.case:<type>:<id>}). A stale revision is
 * {@code 409 risk.CaseRevisionStale}, a disposed case {@code 409 risk.CaseNotOpen}, the proposer's approval
 * {@code 403 risk.SelfApprovalRefused} and the analyst's own party's case {@code 403 risk.SelfDealingRefused} - each
 * refusal recorded {@code FAILED}. Every answer is the case's head with its revision; the queue and the case read are
 * `-018`'s. No customer or merchant route reads any of this ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/cases", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskCaseController {

    @NonNull private final RiskCaseDesk desk;

    /** Opens a case by hand - or answers the subject's open case of the kind; never on the analyst's own party. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RiskCaseDesk.RiskCaseView openRiskCase(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody RiskCaseOpenRequest body) {
        return desk.open(idempotencyKey, body);
    }

    /** An investigator's act on the case: take, note, attach evidence by reference, escalate. */
    @PostMapping(path = "/{caseId}/actions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    @RequiresIdempotencyKey
    public RiskCaseDesk.RiskCaseView actOnRiskCase(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @Valid @RequestBody RiskCaseActionRequest body) {
        return desk.act(idempotencyKey, caseId, body);
    }

    /** Proposes the case's disposition - a one-person close decided at once, a two persons' one standing. */
    @PostMapping(path = "/{caseId}/dispositions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RiskCaseDesk.RiskCaseView proposeRiskCaseDisposition(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @Valid @RequestBody RiskCaseDispositionRequest body) {
        return desk.propose(idempotencyKey, caseId, body);
    }

    /**
     * Approves a standing disposition - a second holder's act, from a {@code MULTI_FACTOR} session (every approval is a
     * four-eyes one: {@code CONFIRMED_*}, {@code REFERRED}, or {@code PROHIBITED}); a prohibition is applied to the
     * profile after the approval commits.
     */
    @PostMapping(path = "/{caseId}/dispositions/{dispositionId}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_CASE_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public RiskCaseDesk.RiskCaseView approveRiskCaseDisposition(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @PathVariable("dispositionId") String dispositionId,
            @Valid @RequestBody RiskCaseDecisionRequest body) {
        return desk.approve(idempotencyKey, caseId, dispositionId, body);
    }

    /** Rejects a standing disposition - anyone, the proposer's withdrawal included; the case stays open. */
    @PostMapping(path = "/{caseId}/dispositions/{dispositionId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_CASE_APPROVE)
    @RequiresIdempotencyKey
    public RiskCaseDesk.RiskCaseView rejectRiskCaseDisposition(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("caseId") String caseId,
            @PathVariable("dispositionId") String dispositionId,
            @Valid @RequestBody RiskCaseDecisionRequest body) {
        return desk.reject(idempotencyKey, caseId, dispositionId, body);
    }

    // -----------------------------------------------------------------

    /**
     * A case opened by hand: on an open alert ({@code alertId} - its kind's case, its subject), or on a {@code kind}
     * ({@code FRAUD}, {@code ATO}, {@code AML} of a {@code PARTY}; {@code MERCHANT_ABUSE} of a {@code MERCHANT}) and the
     * subject's kind and id; the reason required and judged by the domain ({@code risk.ReasonRequired}).
     */
    @ClosedBody
    public record RiskCaseOpenRequest(
            @Size(max = 64) String alertId,
            @Pattern(regexp = "FRAUD|ATO|MERCHANT_ABUSE|AML|COLLECTIONS") String kind,
            @Pattern(regexp = "PARTY|MERCHANT") String subjectKind,
            @Size(max = 64) String subjectRef,
            @Size(max = 1000) String reason) {}

    /**
     * An investigator's act: {@code act} one of {@code TAKE}, {@code NOTE}, {@code ATTACH_EVIDENCE}, {@code ESCALATE};
     * the {@code revision} the analyst read (required); {@code text} the note, the evidence's reason or the escalation's
     * (required but for a take); {@code queue} the escalation's ({@code SECOND_LINE}, {@code SENIOR_REVIEW},
     * {@code SPECIALIST}); the evidence's references - signal, assessment and journal entry ids, at most 100 each.
     */
    @ClosedBody
    public record RiskCaseActionRequest(
            @NotNull @Pattern(regexp = "TAKE|NOTE|ATTACH_EVIDENCE|ESCALATE") String act,
            @NotNull @PositiveOrZero Integer revision,
            @Size(max = 1000) String text,
            @Size(max = 32) String queue,
            @Size(max = 100) List<@NotNull @Size(max = 64) String> signalIds,
            @Size(max = 100) List<@NotNull @Size(max = 64) String> assessmentIds,
            @Size(max = 100) List<@NotNull @Size(max = 64) String> journalEntryIds) {}

    /**
     * A disposition's proposal: the {@code revision} read, the {@code disposition} ({@code CLOSED_FALSE_POSITIVE},
     * {@code CLOSED_NO_ACTION}, {@code CONFIRMED_FRAUD}, {@code CONFIRMED_SUSPICIOUS}, {@code REFERRED}), whether it sets
     * the party's profile {@code PROHIBITED} ({@code prohibit}, a confirming disposition of a party's case alone; false
     * by default) and the reason.
     */
    @ClosedBody
    public record RiskCaseDispositionRequest(
            @NotNull @PositiveOrZero Integer revision,
            @Size(max = 64) String disposition,
            Boolean prohibit,
            @Size(max = 1000) String reason) {}

    /** A decision on a standing disposition: the {@code revision} read and the reason. */
    @ClosedBody
    public record RiskCaseDecisionRequest(
            @NotNull @PositiveOrZero Integer revision,
            @Size(max = 1000) String reason) {}
}
