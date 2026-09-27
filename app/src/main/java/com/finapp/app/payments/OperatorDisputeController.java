package com.finapp.app.payments;

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
 * The operator's dispute reads (`P7-TSK-012`, ADR-0061): one dispute, or one payment's
 * disputes — across tenants by design, under {@link PermissionName#DISPUTE_ADMINISTER}, and
 * every dispute shown on the record ({@code payments.DisputeRead}). A payment with no
 * merchant (a wallet top-up) has only this surface.
 *
 * <p>Handler names are deliberately distinctive (the springdoc {@code operationId} rule,
 * {@code OpenApiContractTest}).
 */
@RestController
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OperatorDisputeController {

    @NonNull private final DisputeOperations disputes;

    /** One dispute with its trail and reconciliation's keys; unknown and malformed are one 404. */
    @GetMapping("/disputes/{disputeId}")
    @RequiresPermission(PermissionName.DISPUTE_ADMINISTER)
    public DisputeOperations.OperatorDisputeView viewDisputeAsOperator(
            @PathVariable("disputeId") String disputeId) {
        return disputes.readForOperator(disputeId);
    }

    /** One payment's disputes, oldest first — an unknown payment is the 404. */
    @GetMapping("/payments/{intentId}/disputes")
    @RequiresPermission(PermissionName.DISPUTE_ADMINISTER)
    public DisputeOperations.OperatorDisputeList listPaymentDisputesAsOperator(
            @PathVariable("intentId") String intentId) {
        return disputes.listForPaymentForOperator(intentId);
    }
}
