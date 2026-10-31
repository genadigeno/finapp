package com.finapp.app.accounting;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The General Ledger's operator reads (`P14-TSK-007`; ADR-0113 decision 2; {@code PHASE_14_PLAN.md} section 10;
 * {@code INV-ACC-02}, {@code INV-ACC-06}, {@code INV-ACC-14}), every one {@code ACCOUNTING_READ} - the accountant's alone
 * (identity {@code V025}: the grants a partition) - computed in one read-only snapshot and recorded
 * {@code accounting.GlRead} at every serving: a period's GL figures, one GL account's activity drilled to its journal
 * lines by keyset page, and one journal entry's GL view. Reads only - no idempotency key, nothing written but the audit
 * row. An operator surface only; no customer or merchant route reads accounting
 * ({@code NoCustomerSurfaceReadsAccountingTest}). The handlers' names are distinctive (the springdoc {@code operationId}
 * rule).
 */
@RestController
@RequestMapping(path = "/operator/accounting/gl", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class GeneralLedgerController {

    @NonNull private final GeneralLedgerDesk desk;

    /**
     * A period's GL ({@code period} a calendar month {@code YYYY-MM}; {@code currency} an optional ISO code narrowing it):
     * every GL account and currency that moved - debits, credits, the net on the account's normal side, the line count
     * and the components by ledger purpose, owner kind and counterparty - each naming the mapping version it is computed
     * under; the lines no rule maps; and, per currency, the GL against the ledger's own totals.
     */
    @GetMapping("/balances")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public GeneralLedgerDesk.GlBalancesView readGeneralLedgerBalances(
            @RequestParam("period") String period,
            @RequestParam(name = "currency", required = false) String currency) {
        return desk.balances(period, currency);
    }

    /**
     * One GL account's activity in one currency and period: its figure and one keyset page of the journal lines composing
     * it, in posting-date, entry and line order ({@code limit} 1 to 500, default 100; {@code after} the previous page's
     * {@code next}). Every page's lines, with the others', sum to the figure.
     */
    @GetMapping("/accounts/{code}/activity")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public GeneralLedgerDesk.GlActivityView readGeneralLedgerAccountActivity(
            @PathVariable("code") String code,
            @RequestParam("period") String period,
            @RequestParam("currency") String currency,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return desk.activity(code, period, currency, after, limit);
    }

    /** One journal entry's GL view: each line with the GL account it maps to under its posting month's version. */
    @GetMapping("/entries/{entryId}")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public GeneralLedgerDesk.GlEntryView readGeneralLedgerEntry(@PathVariable("entryId") String entryId) {
        return desk.entry(entryId);
    }
}
