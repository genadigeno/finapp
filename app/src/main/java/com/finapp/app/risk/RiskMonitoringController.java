package com.finapp.app.risk;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The risk officer's monitoring door (`P13-TSK-019`; {@code PHASE_13_PLAN.md} section 9; ADR-0111 decision 4;
 * {@code INV-RSK-09}): a reprocessing of the monitoring intake ordered from an earlier watermark - keyed, reasoned,
 * audited {@code risk.ReprocessingOrdered}, answered {@code 202} because the re-run is the sweep's to perform. Behind
 * {@code RISK_POLICY_ADMINISTER}, held by {@code RISK_OFFICER}. No customer or merchant route reads any of this
 * ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/monitoring", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskMonitoringController {

    @NonNull private final RiskMonitoringDesk desk;

    /**
     * Orders the intake re-run from {@code fromSequence} - a millisecond of the journal's ids, the watermark's own
     * figure - which must lie at or before the watermark; the sweep re-reads from there and creates nothing new.
     */
    @PostMapping(path = "/reprocessing", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RISK_POLICY_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RiskMonitoringDesk.ReprocessingReceipt orderReprocessing(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody ReprocessingRequest body) {
        return desk.reprocess(idempotencyKey, body.fromSequence(), body.reason());
    }

    /**
     * The point to restart from and the reason - required, judged by the domain ({@code risk.ReasonRequired};
     * {@code risk.ReprocessingNotBehind} for a point ahead of the watermark).
     */
    @ClosedBody
    public record ReprocessingRequest(@NotNull Long fromSequence, @Size(max = 1000) String reason) {}
}
