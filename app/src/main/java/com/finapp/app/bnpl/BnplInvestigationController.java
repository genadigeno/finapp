package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
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
 * The BNPL investigator's agreement doors (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 9), behind
 * {@code BNPL_INVESTIGATE} (role {@code BNPL_OFFICER}): the agreement read, its explanation from its rows alone, and its
 * replay with a reason - each served audited ({@code bnpl.AgreementRead}, {@code bnpl.ExplanationRead},
 * {@code bnpl.Replayed}), none changing anything, never of the investigator's own party's agreement
 * ({@code 403 bnpl.SelfDealingRefused}, recorded). An unknown agreement is the uniform {@code 404 bnpl.NotFound}.
 */
@RestController
@RequestMapping(path = "/operator/bnpl-agreements", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BnplInvestigationController {

    @NonNull private final BnplInvestigationDesk desk;

    /** The agreement: its latest version, its obligation and its purchase - audited per serving. */
    @GetMapping("/{id}")
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplInvestigationDesk.BnplAgreementReadView investigateBnplAgreement(@PathVariable("id") String id) {
        return desk.read(id);
    }

    /** The agreement explained: every version, row and journal entry, and its subledger proof - audited per serving. */
    @GetMapping("/{id}/explanation")
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplInvestigationDesk.BnplExplanationView explainBnplAgreement(@PathVariable("id") String id) {
        return desk.explain(id);
    }

    /** The agreement replayed by its pinned engines and terms - IDENTICAL or DIVERGED; audited with its reason. */
    @PostMapping(path = "/{id}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.BNPL_INVESTIGATE)
    public BnplInvestigationDesk.BnplReplayView replayBnplAgreement(
            @PathVariable("id") String id, @Valid @RequestBody BnplReplayRequest body) {
        return desk.replay(id, body.reason());
    }

    /** Why the agreement is replayed - required, judged by the domain ({@code bnpl.ReasonRequired}). */
    @ClosedBody
    public record BnplReplayRequest(@Size(max = 1000) String reason) {}
}
