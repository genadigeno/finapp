package com.finapp.app.risk;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analyst's customer risk profile door (`P13-TSK-007`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.5;
 * {@code INV-RSK-10}): a party's rating history, behind {@code RISK_INVESTIGATE} (held by {@code RISK_ANALYST}),
 * audited {@code risk.ProfileRead}, the analyst's own party refused {@code 403 risk.SelfDealingRefused} and recorded
 * {@code FAILED}. No customer or merchant route reads any of this ({@code INV-RSK-08}).
 */
@RestController
@RequestMapping(path = "/operator/risk/parties", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RiskProfileController {

    @NonNull private final RiskProfileDesk desk;

    /** The party's customer risk profile: the current rating, if assessed, and every version with what set it. */
    @GetMapping(path = "/{partyId}/profile")
    @RequiresPermission(PermissionName.RISK_INVESTIGATE)
    public RiskProfileDesk.RiskProfileView readRiskProfile(@PathVariable("partyId") String partyId) {
        return desk.read(partyId);
    }
}
