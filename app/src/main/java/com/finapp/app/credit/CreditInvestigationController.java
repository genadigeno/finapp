package com.finapp.app.credit;

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
 * The investigator's credit doors (`P10-TSK-017`; ADR-0087, ADR-0085 point 7), behind {@code CREDIT_INVESTIGATE}: a
 * decision's explanation from rows alone, a credit record's raw evidence with a reason, and (`P10-TSK-019`) a decision's
 * replay with a reason - each serving audited.
 */
@RestController
@RequestMapping(path = "/operator/credit", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class CreditInvestigationController {

    @NonNull private final CreditInvestigationDesk desk;

    @GetMapping("/decisions/{id}/explanation")
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public CreditInvestigationDesk.CreditExplanationView explanation(@PathVariable("id") String id) {
        return desk.explain(id);
    }

    @PostMapping(path = "/records/{id}/evidence-read", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public CreditInvestigationDesk.CreditEvidenceView evidence(
            @PathVariable("id") String id, @Valid @RequestBody CreditEvidenceReadRequest body) {
        return desk.readEvidence(id, body.reason());
    }

    /** Replays a decision from its sealed snapshot and pinned versions - audited, a reason required, nothing changed. */
    @PostMapping(path = "/decisions/{id}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.CREDIT_INVESTIGATE)
    public CreditInvestigationDesk.CreditReplayView replay(
            @PathVariable("id") String id, @Valid @RequestBody CreditReplayRequest body) {
        return desk.replay(id, body.reason());
    }

    /** Why the decision is replayed - required, judged by the desk so a blank one carries its own code. */
    @ClosedBody
    public record CreditReplayRequest(@Size(max = 1000) String reason) {}

    /** Why the evidence is read - required, judged by the domain so a blank one carries its own code. */
    @ClosedBody
    public record CreditEvidenceReadRequest(@Size(max = 1000) String reason) {}
}
