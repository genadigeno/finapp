package com.finapp.app.accounting;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The posting rule register's operator read (`P14-TSK-002`; ADR-0117 decision 1; {@code PHASE_14_PLAN.md} section 10):
 * {@code GET /v1/operator/accounting/posting-rules}, {@code ACCOUNTING_READ} - the accountant's alone (identity
 * {@code V025}: the grants a partition) - recorded {@code accounting.PostingRulesRead} at every serving. An operator
 * surface only; no customer or merchant route reads accounting ({@code NoCustomerSurfaceReadsAccountingTest}). The
 * handler's name is distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting/posting-rules", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class PostingRuleController {

    @NonNull private final PostingRuleDesk desk;

    /** The register as built: every rule and version, its line shape and its dating class. */
    @GetMapping
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public PostingRuleDesk.PostingRuleRegisterView readPostingRuleRegister() {
        return desk.read();
    }
}
