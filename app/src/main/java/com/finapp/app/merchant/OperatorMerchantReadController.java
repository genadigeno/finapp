package com.finapp.app.merchant;

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
 * The operator's merchant collections (`P17-TSK-033`, finapp-admin-panel B7.3, gaps G-50, G-51, G-52): finding a merchant,
 * its payouts and its transactions, each a §7 collection ({@code API_CONVENTIONS.md}) - {@code limit} 50 unless named, 200 at
 * most, the opaque {@code cursor} of the page before - under {@code MERCHANT_ADMINISTER}, the permission of every operator read
 * of a merchant. Reads only: no idempotency key and no step-up.
 *
 * <p>Handler names are distinct platform-wide: springdoc derives each {@code operationId} from them.
 */
@RestController
@RequestMapping(path = "/operator/merchants", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OperatorMerchantReadController {

    @NonNull private final MerchantCollectionDesk desk;

    /**
     * The merchants, newest onboarding first and the merchant's id breaking the tie; of one standing when {@code status}
     * names one; whose legal or display name is exactly {@code name} when named - never a pattern or free text. Unrecorded,
     * as one merchant's read is.
     */
    @GetMapping
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public MerchantCollectionDesk.MerchantPage listMerchants(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.merchants(status, name, cursor, limit);
    }

    /**
     * The merchant's payouts, newest first and the payout's id breaking the tie, each as the merchant's own read renders it -
     * the destination by its suffix. Every page recorded {@code merchant.PayoutsListed}. An unknown merchant is the one 404.
     */
    @GetMapping("/{merchantId}/payouts")
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public MerchantCollectionDesk.MerchantPayoutPage listMerchantPayouts(
            @PathVariable("merchantId") String merchantId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.payouts(merchantId, cursor, limit);
    }

    /** One of the merchant's payouts, current; recorded {@code merchant.PayoutRead}. Another merchant's is the one 404. */
    @GetMapping("/{merchantId}/payouts/{payoutId}")
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public MerchantPayoutOperations.PayoutView viewOperatorMerchantPayout(
            @PathVariable("merchantId") String merchantId, @PathVariable("payoutId") String payoutId) {
        return desk.payout(merchantId, payoutId);
    }

    /**
     * The merchant's movements whose posting date lies in {@code [from, to]} - today and the thirty days before it unless
     * named, thirty-one days at most - newest posting date first, then the journal entry descending, then the currency; each as
     * the merchant's own statement renders it. A list, not a statement: no opening, no closing, no sum across pages. Every
     * page recorded {@code merchant.TransactionsListed}. An unknown merchant is the one 404.
     */
    @GetMapping("/{merchantId}/transactions")
    @RequiresPermission(PermissionName.MERCHANT_ADMINISTER)
    public MerchantCollectionDesk.MerchantTransactionPage listOperatorMerchantTransactions(
            @PathVariable("merchantId") String merchantId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.transactions(merchantId, from, to, cursor, limit);
    }
}
