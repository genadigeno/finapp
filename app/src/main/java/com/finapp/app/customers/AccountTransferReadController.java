package com.finapp.app.customers;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
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
 * The operator's account and transfer reads (`P17-TSK-027`, gaps G-32, G-35; finapp-admin-panel B6.3, ADR-C132): a customer's
 * agreements, one agreement with its balances as of a stated instant, its ledger lines, and the customer's transfers within a
 * bounded window, one with its journal entry. Under {@code ACCOUNT_READ} and {@code TRANSFER_READ}, each on a
 * {@code MULTI_FACTOR} session as every customer read is, and every answer recorded ({@code RecordedReads}). Amounts are decimal
 * strings with their currency; the balances are the ledger's own, never computed here.
 */
@RestController
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AccountTransferReadController {

    @NonNull private final AccountTransferReadDesk desk;

    /** A customer's agreements, every status: a §7 collection, newest opening first. Recorded once per page. */
    @GetMapping("/customers/{id}/accounts")
    @RequiresPermission(PermissionName.ACCOUNT_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public AccountTransferReadDesk.AgreementPage listCustomerAccounts(
            @PathVariable("id") String customerId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.agreements(customerId, cursor, limit);
    }

    /**
     * One agreement with its posted, held and available balances per currency, and {@code asOf}: the database's instant of the
     * one snapshot all three were read in. Recorded, the agreement the target.
     */
    @GetMapping("/accounts/{id}")
    @RequiresPermission(PermissionName.ACCOUNT_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public AccountTransferReadDesk.AccountDetail readAccount(@PathVariable("id") String accountId) {
        return desk.agreement(accountId);
    }

    /**
     * An agreement's ledger lines in one {@code currency} (ISO 4217, required: one ledger account per currency): a §7 collection,
     * newest entry first. Recorded once per page.
     */
    @GetMapping("/accounts/{id}/entries")
    @RequiresPermission(PermissionName.ACCOUNT_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public AccountTransferReadDesk.LinePage listAccountEntries(
            @PathVariable("id") String accountId,
            @RequestParam(name = "currency", required = false) String currency,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.lines(accountId, currency, cursor, limit);
    }

    /**
     * A customer's transfers initiated in {@code [from, to)} - {@code customerId}, {@code from} and {@code to} required, 92 days
     * at most - of one {@code status} when named: a §7 collection, newest first. Recorded once per page.
     */
    @GetMapping("/transfers")
    @RequiresPermission(PermissionName.TRANSFER_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public AccountTransferReadDesk.TransferPage listCustomerTransfers(
            @RequestParam(name = "customerId", required = false) String customerId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.transfers(customerId, status, from, to, cursor, limit);
    }

    /** One transfer with its journal entry's identifier. Recorded, the transfer the target. */
    @GetMapping("/transfers/{id}")
    @RequiresPermission(PermissionName.TRANSFER_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public AccountTransferReadDesk.TransferDetail readTransfer(@PathVariable("id") String transferId) {
        return desk.transfer(transferId);
    }
}
