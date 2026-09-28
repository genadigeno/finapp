package com.finapp.app.payments;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.payments.DisputeResponseKind;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's dispute surfaces (`P7-TSK-012`, ADR-0061): one dispute, or one payment's
 * disputes — across tenants by design, under {@link PermissionName#DISPUTE_ADMINISTER}, and
 * every dispute shown on the record ({@code payments.DisputeRead}). A payment with no merchant
 * (a wallet top-up) has only this surface — and, since `P7-TSK-014`, only this surface can answer
 * its chargeback: evidence and a representment or acceptance on behalf, each reasoned
 * ({@code PHASE_7_PLAN.md} §11), refused on a payment that has a merchant.
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

    /**
     * Attaches a document on behalf, for a payment with no merchant (`P7-TSK-014`), reasoned —
     * encrypted before it is stored, on the record. {@code 201} for the created and the converged
     * upload alike.
     */
    @PostMapping(path = "/disputes/{disputeId}/evidence", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.DISPUTE_ADMINISTER)
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeOperations.EvidenceView uploadDisputeEvidenceAsOperator(
            @PathVariable("disputeId") String disputeId,
            @Valid @RequestBody OperatorDisputeEvidenceUploadRequest body) {
        return disputes.uploadAsOperator(disputeId, body);
    }

    /** Any dispute's document with its content — across tenants by permission, on the record. */
    @GetMapping("/disputes/{disputeId}/evidence/{evidenceId}")
    @RequiresPermission(PermissionName.DISPUTE_ADMINISTER)
    public DisputeOperations.EvidenceContentView viewDisputeEvidenceAsOperator(
            @PathVariable("disputeId") String disputeId,
            @PathVariable("evidenceId") String evidenceId) {
        return disputes.readEvidenceAsOperator(disputeId, evidenceId);
    }

    /**
     * Contests a chargeback on a payment with no merchant (`P7-TSK-014`), keyed and reasoned.
     * {@code 201} with the honest status.
     */
    @PostMapping(path = "/disputes/{disputeId}/representment", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.DISPUTE_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeOperations.ResponseAnswer representDisputeAsOperator(
            @PathVariable("disputeId") String disputeId,
            @Valid @RequestBody OperatorDisputeResponseRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return disputes.respondAsOperator(
                disputeId, DisputeResponseKind.REPRESENTMENT, body, idempotencyKey);
    }

    /** Concedes a chargeback on a payment with no merchant (`P7-TSK-014`), keyed and reasoned. */
    @PostMapping(path = "/disputes/{disputeId}/acceptance", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.DISPUTE_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeOperations.ResponseAnswer acceptDisputeAsOperator(
            @PathVariable("disputeId") String disputeId,
            @Valid @RequestBody OperatorDisputeResponseRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return disputes.respondAsOperator(
                disputeId, DisputeResponseKind.ACCEPTANCE, body, idempotencyKey);
    }
}
