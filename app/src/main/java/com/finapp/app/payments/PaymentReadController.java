package com.finapp.app.payments;

import com.finapp.app.session.RecordsRefusals;
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
 * The operator's payment reads (`P17-TSK-032`, finapp-admin-panel B7.2): the payment search and one payment, a party's payment
 * methods and the payments report under {@code PAYMENT_READ} (every answer recorded, {@code RecordedReads}); the dispute queue
 * under {@code DISPUTE_ADMINISTER} - the held card payments are risk's ({@code HeldPaymentController}). Each list is a §7 collection
 * ({@code API_CONVENTIONS.md}) - {@code limit} 50 unless named, 200 at most, the opaque {@code cursor} of the page before. Reads
 * only: no idempotency key and no step-up.
 *
 * <p>Handler names are distinct platform-wide: springdoc derives each {@code operationId} from them.
 */
@RestController
@RecordsRefusals
@RequestMapping(path = "/operator", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class PaymentReadController {

    @NonNull private final PaymentReadDesk desk;

    /**
     * The payments created in {@code [from, to)} - both required, 31 days at most - newest first and the id breaking the tie;
     * of one payer party or one merchant when named (never both), of one status and one rail when named. Every page recorded
     * {@code payments.PaymentsListed}.
     */
    @GetMapping("/payments")
    @RequiresPermission(PermissionName.PAYMENT_READ)
    public PaymentReadDesk.PaymentPage listOperatorPayments(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "partyId", required = false) String partyId,
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "rail", required = false) String rail,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.payments(from, to, partyId, merchantId, status, rail, cursor, limit);
    }

    /**
     * One payment with its attempt and the provider's references, its refunds, its latest routing decision and its disputes;
     * recorded {@code payments.PaymentRead}. Unknown and malformed are one 404.
     */
    @GetMapping("/payments/{intentId}")
    @RequiresPermission(PermissionName.PAYMENT_READ)
    public PaymentReadDesk.PaymentDetailView viewOperatorPayment(@PathVariable("intentId") String intentId) {
        return desk.payment(intentId);
    }

    /** A party's live payment methods, masked as the party's own list serves them; recorded. An unknown party is the 404. */
    @GetMapping("/parties/{partyId}/payment-methods")
    @RequiresPermission(PermissionName.PAYMENT_READ)
    public PaymentReadDesk.PaymentMethodList listPartyPaymentMethods(@PathVariable("partyId") String partyId) {
        return desk.paymentMethods(partyId);
    }

    /**
     * The payments report for a UTC day: its payments by rail - each rail one provider's adapter - attempt status and payment
     * status, and the unknown-state backlog as it stands, with the instant it was generated. Recorded.
     */
    @GetMapping("/reports/payments")
    @RequiresPermission(PermissionName.PAYMENT_READ)
    public PaymentReadDesk.PaymentsReportView readPaymentsReport(@RequestParam(name = "date", required = false) String date) {
        return desk.report(date);
    }

    /**
     * The dispute queue: the network's deadline earliest first, a dispute whose deadline is unstated last, the id breaking the
     * tie; of one stage and before one deadline when named. Every page recorded {@code payments.DisputeQueueRead}.
     */
    @GetMapping("/disputes")
    @RequiresPermission(PermissionName.DISPUTE_ADMINISTER)
    public PaymentReadDesk.DisputeQueuePage listDisputeQueue(
            @RequestParam(name = "stage", required = false) String stage,
            @RequestParam(name = "deadlineBefore", required = false) String deadlineBefore,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.disputeQueue(stage, deadlineBefore, cursor, limit);
    }
}
