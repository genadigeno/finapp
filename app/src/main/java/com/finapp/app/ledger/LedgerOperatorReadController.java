package com.finapp.app.ledger;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * `/v1/operator/ledger` (`P17-TSK-022`, gaps G-110 and G-111; finapp-admin-panel B5.1, B5.2): the ledger as an accountant browses
 * it - the account register and the journal entry search - each a §7 collection under {@code ACCOUNTING_READ}, the permission the
 * GL's reads use, every page recorded ({@link LedgerCollectionDesk}).
 */
@RestController
@RequestMapping(path = "/operator/ledger", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class LedgerOperatorReadController {

    @NonNull private final LedgerCollectionDesk desk;

    /**
     * The account register: the chart of accounts as the ledger holds it, in {@code (purpose, currency, accountId)} order, each
     * owner a reference and never a name (ADR-C132 decision 3). {@code purpose} and {@code currency} narrow it; {@code limit} 50
     * unless named, 200 at most; the opaque {@code cursor} of the page before. Recorded {@code ledger.AccountsListed}.
     */
    @GetMapping("/accounts")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public LedgerCollectionDesk.LedgerAccountPage listLedgerAccounts(
            @RequestParam(name = "purpose", required = false) String purpose,
            @RequestParam(name = "currency", required = false) String currency,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.accounts(purpose, currency, cursor, limit);
    }

    /**
     * The journal entry search: the entries posted on a date of the window {@code from}..{@code to} (posting dates, both
     * required, inclusive, at most 31), newest posting date first, the entry's id breaking the tie. {@code reference} - the
     * economic event's identifier, matched exactly and never served - and {@code accountId} - an account the entry has a line on
     * - narrow it. Each item's movement per currency is exact, a decimal string with its currency. Recorded
     * {@code ledger.EntriesSearched}; a read past its statement timeout {@code 503}, nothing served.
     */
    @GetMapping("/entries")
    @RequiresPermission(PermissionName.ACCOUNTING_READ)
    public LedgerCollectionDesk.JournalEntryPage searchJournalEntries(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "reference", required = false) String reference,
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.entries(from, to, reference, accountId, cursor, limit);
    }
}
