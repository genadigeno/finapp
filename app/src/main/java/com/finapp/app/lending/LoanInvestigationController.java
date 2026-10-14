package com.finapp.app.lending;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.RequiresSession;
import com.finapp.identity.PermissionName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lending investigator's loan doors (`P11-TSK-029`; PHASE_11_PLAN.md section 9), behind {@code LENDING_INVESTIGATE}:
 * a loan account's explanation from its rows alone, and its replay with a reason - each served audited, neither changing
 * anything, never of the investigator's own party's loan.
 */
@RestController
@RequestMapping(path = "/operator/loans", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class LoanInvestigationController {

    @NonNull private final LoanInvestigationDesk desk;

    /** The loan explained: every version, row and journal entry, and its subledger proof - audited per serving. */
    @GetMapping("/{id}/explanation")
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LoanInvestigationDesk.LoanExplanationView explainLoan(@PathVariable("id") String id) {
        return desk.explain(id);
    }

    /** The loan replayed by its pinned engines and terms - IDENTICAL or DIVERGED; audited with its reason. */
    @PostMapping(path = "/{id}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.LENDING_INVESTIGATE)
    public LoanInvestigationDesk.LoanReplayView replayLoan(
            @PathVariable("id") String id, @Valid @RequestBody LoanReplayRequest body) {
        return desk.replay(id, body.reason());
    }

    /** Why the loan is replayed - required, judged by the domain ({@code lending.ReasonRequired}). */
    @ClosedBody
    public record LoanReplayRequest(@Size(max = 1000) String reason) {}
}
