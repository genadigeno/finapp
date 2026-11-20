package com.finapp.app.accounts;

import com.finapp.accounts.RestrictionKind;
import com.finapp.accounts.RestrictionStatus;
import com.finapp.app.api.ClosedBody;
import com.finapp.app.api.RecordedRead;
import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * An agreement's suspension and reinstatement by four eyes (`P17-TSK-028`, ADR-0153; finapp-admin-panel B6.5, gap G-33): a
 * holder of {@code ACCOUNT_RESTRICT} proposes - {@code RISK_ANALYST} - and a holder of {@code ACCOUNT_RESTRICT_APPROVE} who
 * did not propose it decides - {@code RISK_OFFICER} (the owner's ruling of 2026-11-20). Every act on a {@code MULTI_FACTOR}
 * session, keyed, with a reason; refusals recorded.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccountRestrictionController {

    @NonNull private final AccountRestrictionDesk desk;

    /** Proposes the agreement's suspension: refused unless it is {@code ACTIVE} with no open proposal. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/accounts/{id}/suspension", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNT_RESTRICT)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public AccountRestrictionDesk.RestrictionReceipt proposeAccountSuspension(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody RestrictionProposalRequest body) {
        return desk.propose(idempotencyKey, id, RestrictionKind.SUSPENSION, body.reasonCode(), body.reason());
    }

    /** Proposes the agreement's reinstatement: refused unless it is {@code SUSPENDED} with no open proposal. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/accounts/{id}/reinstatement", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNT_RESTRICT)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public AccountRestrictionDesk.RestrictionReceipt proposeAccountReinstatement(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody RestrictionProposalRequest body) {
        return desk.propose(idempotencyKey, id, RestrictionKind.REINSTATEMENT, body.reasonCode(), body.reason());
    }

    /**
     * One restriction - the agreement, the kind, the reason code, the reason and who proposed it - what its approver decides
     * (`P17-TSK-124`). Every serving recorded ({@code accounts.RestrictionRead}); unknown or malformed 404.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @GetMapping("/account-restrictions/{id}")
    @RequiresPermission(PermissionName.ACCOUNT_RESTRICT_APPROVE)
    @RecordedRead
    public AccountRestrictionDesk.AccountRestrictionView readAccountRestriction(@PathVariable("id") String id) {
        return desk.read(id);
    }

    /** Approves a restriction the caller did not propose: the agreement moves. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/account-restrictions/{id}/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNT_RESTRICT_APPROVE)
    @RequiresIdempotencyKey
    public AccountRestrictionDesk.RestrictionReceipt approveAccountRestriction(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody RestrictionDecisionRequest body) {
        return desk.decide(idempotencyKey, id, RestrictionStatus.APPROVED, body.reason());
    }

    /** Rejects a restriction the caller did not propose: nothing moves. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/account-restrictions/{id}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.ACCOUNT_RESTRICT_APPROVE)
    @RequiresIdempotencyKey
    public AccountRestrictionDesk.RestrictionReceipt rejectAccountRestriction(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody RestrictionDecisionRequest body) {
        return desk.decide(idempotencyKey, id, RestrictionStatus.REJECTED, body.reason());
    }

    /** A reason code of the kind's vocabulary and a person's reason. */
    @ClosedBody
    public record RestrictionProposalRequest(@NotBlank String reasonCode, @NotBlank @Size(min = 1, max = 1000) String reason) {

        @Override
        public String toString() {
            // The reason is a person's prose about a customer's account: it never reaches a log.
            return "RestrictionProposalRequest[" + reasonCode + ", reason=" + (reason == null ? 0 : reason.length()) + " chars]";
        }
    }

    /** The decider's reason. */
    @ClosedBody
    public record RestrictionDecisionRequest(@NotBlank @Size(min = 1, max = 1000) String reason) {

        @Override
        public String toString() {
            return "RestrictionDecisionRequest[reason=" + (reason == null ? 0 : reason.length()) + " chars]";
        }
    }
}
