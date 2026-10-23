package com.finapp.app.risk;

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
 * The analyst's risk assessment doors (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 9, 11; {@code INV-RSK-01},
 * {@code INV-RSK-04}): a sealed assessment read with its inputs, hits, score, decision and reasons, audited
 * {@code risk.AssessmentRead}; and its replay from the sealed inputs under the pinned policy version and engine -
 * {@code IDENTICAL} or {@code DIVERGED}, a reason required, audited {@code risk.Replayed}. Behind
 * {@code RISK_INVESTIGATE} (held by {@code RISK_ANALYST}); the analyst's own party refused
 * {@code 403 risk.SelfDealingRefused} and recorded {@code FAILED}. No customer or merchant route reads any of this
 * ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/assessments", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskAssessmentController {

    @NonNull private final RiskAssessmentDesk desk;

    /** The sealed assessment, whole. */
    @GetMapping(path = "/{assessmentId}")
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskAssessmentDesk.RiskAssessmentView readRiskAssessment(@PathVariable("assessmentId") String assessmentId) {
        return desk.read(assessmentId);
    }

    /** The assessment replayed from its seal - audited, a reason required, nothing changed. */
    @PostMapping(path = "/{assessmentId}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskAssessmentDesk.RiskReplayView replayRiskAssessment(@PathVariable("assessmentId") String assessmentId,
            @Valid @RequestBody RiskReplayRequest body) {
        return desk.replay(assessmentId, body.reason());
    }

    /** Why the assessment is replayed - required, judged by the desk so a blank one carries its own code. */
    public record RiskReplayRequest(@Size(max = 1000) String reason) {}
}
