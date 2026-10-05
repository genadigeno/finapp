package com.finapp.app.fx;

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
 * The FX investigator's door (`P9-TSK-013`, PHASE_9_PLAN.md section 9): a trade's provenance behind
 * {@code FX_INVESTIGATE}, held by {@code RECONCILIATION_OPERATOR}. Read-only and audited at every
 * serving; nothing here moves a value.
 */
@RestController
@RequestMapping(path = "/operator/fx", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FxProvenanceController {

    @NonNull private final FxProvenanceDesk desk;

    /** The trade's whole rate chain, its entry, its cover and the cover's execution. */
    @GetMapping("/trades/{id}/provenance")
    @RequiresPermission(PermissionName.FX_INVESTIGATE)
    public FxProvenanceDesk.ProvenanceView readFxTradeProvenance(@PathVariable("id") String id) {
        return desk.provenance(id);
    }
}
