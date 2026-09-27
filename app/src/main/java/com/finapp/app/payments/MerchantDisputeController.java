package com.finapp.app.payments;

import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.merchant.AuthenticatedMerchant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A merchant's own disputes (`P7-TSK-012`, ADR-0061): what the network is contesting of its
 * sales, stage by stage — read-only here; contesting and accepting are `P7-TSK-014`'s.
 *
 * <h2>The tenant is the key</h2>
 *
 * <p>No merchant identifier in either route: the merchant is the one the key authenticated,
 * and its disputes are those on payments that credited its own payable — the predicate rides
 * the statement ({@code INV-MER-01}), so another merchant's dispute is the same 404 as one
 * that does not exist.
 *
 * <p>Handler names are distinct platform-wide ({@code listMerchantDisputes}): springdoc derives
 * each {@code operationId} from them.
 */
@RestController
@RequestMapping(path = "/merchant/disputes", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class MerchantDisputeController {

    @NonNull private final DisputeOperations disputes;

    /**
     * The merchant's disputes, newest first — at most 100, and {@code truncated} says when
     * there were more, so a client can tell it has not seen everything.
     */
    @GetMapping
    @RequiresMerchantKey
    public DisputeOperations.MerchantDisputeList listMerchantDisputes(HttpServletRequest request) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return disputes.listForMerchant(merchant.merchantId());
    }

    /** One of the merchant's disputes with its trail. Another merchant's and unknown are one 404. */
    @GetMapping("/{disputeId}")
    @RequiresMerchantKey
    public DisputeOperations.MerchantDisputeView viewMerchantDispute(
            HttpServletRequest request, @PathVariable("disputeId") String disputeId) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return disputes.readForMerchant(merchant.merchantId(), disputeId);
    }
}
