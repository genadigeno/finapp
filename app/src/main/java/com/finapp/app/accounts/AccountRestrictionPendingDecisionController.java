package com.finapp.app.accounts;

import com.finapp.app.api.PendingDecisions.PendingDecisionPage;
import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account suspensions and reinstatements (`P17-TSK-028`) awaiting a decision (`P17-TSK-040`), in the one pending-decision shape, under the permission and the
 * multi-factor session its approve door requires. Read-only: every decision stays on the kind's own doors.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator/pending-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccountRestrictionPendingDecisionController {

    @NonNull private final AccountRestrictionPendingDecisionDesk desk;

    /** The queue, oldest first; each item's subject is what the proposal acts on. */
    @GetMapping("/accounts/restrictions")
    @RequiresPermission(PermissionName.ACCOUNT_RESTRICT_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public PendingDecisionPage listPendingAccountRestrictions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.pending(status, cursor, limit);
    }
}
