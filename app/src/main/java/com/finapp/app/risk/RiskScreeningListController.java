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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * The risk officer's screening list doors (`P13-TSK-022`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.10;
 * {@code RISK_LIFECYCLES.md} section 4.10): a sanctions or PEP list version recorded by one person and activated by a
 * second from a {@code MULTI_FACTOR} session ({@code 202}: the rescreening of every {@code ACTIVE} party is enqueued by
 * {@code RiskActionSchedule}'s rescreening leg, never in the door's transaction), each act keyed per principal
 * ({@code risk.list:<type>:<id>}) and reasoned, both behind {@code RISK_POLICY_ADMINISTER}, held by {@code RISK_OFFICER}.
 * A person's doors: the operator the session types {@code CUSTOMER} is a person here. No list content crosses them - the
 * list is the provider's, kyc's - and no customer or merchant route reads a list version ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/screening-lists", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskScreeningListController {

    @NonNull private final RiskScreeningListDesk desk;

    /** Records a list version - the fact that the provider's version of a list exists; a recorded label converges. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RiskScreeningListDesk.RiskScreeningListReceipt recordScreeningListVersion(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody RiskScreeningListVersionRequest body) {
        return desk.record(idempotencyKey, body);
    }

    /**
     * Activates a recorded version - a different person's act, from a {@code MULTI_FACTOR} session; retires the list's
     * predecessor in the same transaction and leaves the rescreening enqueued for the sweep ({@code 202}).
     */
    @PostMapping(path = "/{listVersionId}/activation", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RiskScreeningListDesk.RiskScreeningListReceipt activateScreeningListVersion(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("listVersionId") String listVersionId,
            @Valid @RequestBody RiskScreeningListActivationRequest body) {
        return desk.activate(idempotencyKey, listVersionId, body.reason());
    }

    // -----------------------------------------------------------------

    /**
     * A list version: the list ({@code SANCTIONS} or {@code PEP}), the provider's label for its version (1 to 64 letters,
     * digits, {@code .}, {@code _}, {@code :} or {@code -}, starting with a letter or digit) and the reason - required,
     * judged by the domain ({@code risk.ReasonRequired}).
     */
    @ClosedBody
    public record RiskScreeningListVersionRequest(
            @NotBlank @Pattern(regexp = "SANCTIONS|PEP") String list,
            @NotBlank @Size(min = 1, max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String version,
            @Size(max = 1000) String reason) {}

    /** An activation's reason - required, judged by the domain ({@code risk.ReasonRequired}). */
    @ClosedBody
    public record RiskScreeningListActivationRequest(@Size(max = 1000) String reason) {}
}
