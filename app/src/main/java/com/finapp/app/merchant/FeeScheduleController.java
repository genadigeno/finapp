package com.finapp.app.merchant;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
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
 * <p><strong>No idempotency key.</strong> Every keyed command on this platform is keyed
 * because a duplicate produces a duplicate <em>effect</em>; a duplicated version creation
 * produces a second version with identical content that prices identically. The reasoning is
 * recorded at {@code FeeSchedules}, where it can be revisited if that ever stops being true.
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

    /** Creates a named pricing identity. It carries no price until a version is added. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    @ResponseStatus(HttpStatus.CREATED)
    public FeeScheduleOperations.FeeScheduleView createFeeSchedule(
            @Valid @RequestBody CreateFeeScheduleRequest body) {
        return feeSchedules.create(body);
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
     * Adds an immutable version, effective forward. A backdated {@code effectiveFrom} is a
     * {@code 422}: it would reprice captures that already happened, which is the one thing
     * {@code INV-MER-03} exists to forbid.
     */
    @PostMapping(path = "/{id}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.FEE_ADMINISTER)
    @ResponseStatus(HttpStatus.CREATED)
    public FeeScheduleOperations.FeeScheduleVersionView createFeeScheduleVersion(
            @PathVariable("id") String id, @Valid @RequestBody CreateFeeScheduleVersionRequest body) {
        return feeSchedules.addVersion(id, body);
    }
}
