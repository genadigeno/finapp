package com.finapp.app.merchant;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
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
 * `/v1/operator/fee-schedules` (`P6-TSK-004`, ADR-0050): what the platform charges.
 *
 * <p><strong>There is no {@code PUT} and no {@code DELETE} on this surface, and that is the
 * contract rather than an omission.</strong> A fee schedule version is immutable
 * ({@code INV-MER-03}); a price change is a {@code POST} of a new version effective forward.
 * The API shape and the schema's withheld grant say the same thing, which is what makes the
 * claim believable to a reader of either.
 *
 * <p><strong>Keyed, and a version by four eyes</strong> (`P17-TSK-123`; finapp-admin-panel ADR-C135 decisions 2 and 3,
 * ADR-0125 decision 4). <em>Read "No idempotency key" until then: a duplicated version creation produced a second version
 * pricing identically.</em> A schedule's creation is one person's act, keyed: a retry answers the schedule created first.
 * A version is a <em>proposal</em> now, keyed, answered {@code PROPOSED}; a holder of {@code FEE_SCHEDULE_APPROVE} who
 * did not propose it mints it ({@link FeeProposalController}). A duplicated proposal is a second row a second person would
 * have to decide, which is why the key exists now.
 *
 * <p><strong>Handler names are deliberately distinctive</strong> — {@code createFeeSchedule},
 * not {@code create}. springdoc derives each {@code operationId} from the method name and
 * renumbers collisions <em>order-dependently</em>, so a generic name in a new file is a
 * breaking change to somebody else's published endpoint. Found twice (`P6-TSK-003`,
 * `P6-TSK-002`) and now a build rule: {@code OpenApiContractTest} refuses any suffixed
 * {@code operationId} outright.
 */
@RestController
@RequestMapping(path = "/operator/fee-schedules", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FeeScheduleController {

    @NonNull private final FeeScheduleOperations feeSchedules;

    /** Creates a named pricing identity, keyed. It carries no price until a version is approved. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public FeeScheduleOperations.FeeScheduleView createFeeSchedule(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody CreateFeeScheduleRequest body) {
        return feeSchedules.create(idempotencyKey, body);
    }

    /** Every schedule, newest first. The platform's own pricing; there is no tenant here. */
    @GetMapping
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    public List<FeeScheduleOperations.FeeScheduleView> listFeeSchedules() {
        return feeSchedules.list();
    }

    /** The schedule and its versions, newest-effective first. Unknown and malformed are one 404. */
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    public FeeScheduleOperations.FeeScheduleDetailView viewFeeSchedule(
            @PathVariable("id") String id) {
        return feeSchedules.view(id);
    }

    /**
     * Proposes an immutable version, effective forward, keyed (`P17-TSK-123`): the answer is the proposal, {@code PROPOSED};
     * a second person's approval mints it. A backdated {@code effectiveFrom} is a {@code 422} at once: it would reprice
     * captures that already happened, which is the one thing {@code INV-MER-03} exists to forbid - and judged again at the
     * approval.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{id}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public FeeScheduleOperations.FeeScheduleVersionProposalReceipt createFeeScheduleVersion(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String id, @Valid @RequestBody CreateFeeScheduleVersionRequest body) {
        return feeSchedules.proposeVersion(idempotencyKey, id, body);
    }
}
