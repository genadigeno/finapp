package com.finapp.app.risk;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    @NonNull private final RiskCollectionDesk collections;

    /**
     * The sealed assessments of a window, newest first (`P17-TSK-020`, gap G-91): a §7 collection of heads over the required
     * {@code from} and {@code to} (instants, {@code [from, to)}, 31 days at most), filtered by {@code partyId},
     * {@code operation} and {@code decision} when named; {@code limit} 50 unless named, 200 at most; the opaque {@code cursor}
     * of the page before. The reader's own party's assessments withheld; a search naming that party {@code 403
     * risk.SelfDealingRefused}, recorded {@code FAILED}; every page recorded {@code risk.AssessmentsSearched}.
     */
    @GetMapping
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskCollectionDesk.RiskAssessmentPage searchRiskAssessments(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "partyId", required = false) String partyId,
            @RequestParam(name = "operation", required = false) String operation,
            @RequestParam(name = "decision", required = false) String decision,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return collections.assessments(from, to, partyId, operation, decision, cursor, limit);
    }

    /** The sealed assessment, whole. */
    @GetMapping(path = "/{assessmentId}")
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskAssessmentDesk.RiskAssessmentView readRiskAssessment(@PathVariable("assessmentId") String assessmentId) {
        return desk.read(assessmentId);
    }

    /** The assessment replayed from its seal - audited, a reason required, nothing changed. */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/{assessmentId}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskAssessmentDesk.RiskReplayView replayRiskAssessment(@PathVariable("assessmentId") String assessmentId,
            @Valid @RequestBody RiskReplayRequest body) {
        return desk.replay(assessmentId, body.reason());
    }

    /**
     * A page of a UTC day's assessments replayed under their own pinned pairs (`P13-TSK-023`) - audited, a reason
     * required, nothing changed; {@code IDENTICAL} or {@code DIVERGED}, the page's first divergent named; at most
     * {@code limit} (200 unless named, 1000 at most) after the cursor {@code after}, the answer's {@code next} the cursor
     * of the page that follows.
     */
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @PostMapping(path = "/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskAssessmentDesk.RiskDayReplayView replayRiskAssessmentsOfDay(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "limit", required = false) String limit,
            @Valid @RequestBody RiskReplayRequest body) {
        return desk.replayDay(date, after, limit, body.reason());
    }

    /** Why the assessment is replayed - required, judged by the desk so a blank one carries its own code. */
    public record RiskReplayRequest(@Size(max = 1000) String reason) {}
}
