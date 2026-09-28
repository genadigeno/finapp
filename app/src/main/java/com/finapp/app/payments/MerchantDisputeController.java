package com.finapp.app.payments;

import com.finapp.app.merchant.MerchantKeyAuthenticationInterceptor;
import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.payments.DisputeResponseKind;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
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
 * A merchant's own disputes (`P7-TSK-012`, ADR-0061): what the network is contesting of its
 * sales, stage by stage — and, since `P7-TSK-014`, the merchant's answer: evidence attached,
 * then a representment or an acceptance (ADR-0061 §7).
 *
 * <h2>The tenant is the key</h2>
 *
 * <p>No merchant identifier in any route: the merchant is the one the key authenticated, and its
 * disputes are those on payments that credited its own payable — the predicate rides every
 * statement ({@code INV-MER-01}), so another merchant's dispute or document is the same 404 as
 * one that does not exist, for a read, an upload and an answer alike.
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

    /**
     * Attaches a document to one of the merchant's disputes (`P7-TSK-014`) — encrypted before it
     * is stored, on the record. {@code 201} for the created and the converged upload alike: a
     * retry after a lost response and a first upload are the same intent (the KYC upload's
     * contract).
     */
    @PostMapping(path = "/{disputeId}/evidence", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresMerchantKey
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeOperations.EvidenceView uploadMerchantDisputeEvidence(
            HttpServletRequest request,
            @PathVariable("disputeId") String disputeId,
            @Valid @RequestBody DisputeEvidenceUploadRequest body) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return disputes.uploadAsMerchant(merchant.merchantId(), disputeId, body);
    }

    /** One of the merchant's documents with its content — every such read on the record. */
    @GetMapping("/{disputeId}/evidence/{evidenceId}")
    @RequiresMerchantKey
    public DisputeOperations.EvidenceContentView viewMerchantDisputeEvidence(
            HttpServletRequest request,
            @PathVariable("disputeId") String disputeId,
            @PathVariable("evidenceId") String evidenceId) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return disputes.readEvidenceAsMerchant(merchant.merchantId(), disputeId, evidenceId);
    }

    /**
     * Contests one of the merchant's chargebacks with the dispute's evidence (`P7-TSK-014`),
     * keyed — or replays the recorded judgement for a retried key ({@code INV-IDEM-01}); a
     * reused key for a different request is the distinct {@code 409} ({@code INV-IDEM-03}).
     * {@code 201} with the honest status: {@code SUBMITTED}, {@code FAILED} or {@code UNKNOWN}.
     */
    @PostMapping("/{disputeId}/representment")
    @RequiresMerchantKey
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeOperations.ResponseAnswer representMerchantDispute(
            HttpServletRequest request,
            @PathVariable("disputeId") String disputeId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return disputes.respondAsMerchant(
                merchant.merchantId(), disputeId, DisputeResponseKind.REPRESENTMENT,
                idempotencyKey);
    }

    /**
     * Concedes one of the merchant's chargebacks rather than contest it (`P7-TSK-014`), keyed —
     * the network then states {@code ACCEPTED}.
     */
    @PostMapping("/{disputeId}/acceptance")
    @RequiresMerchantKey
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeOperations.ResponseAnswer acceptMerchantDispute(
            HttpServletRequest request,
            @PathVariable("disputeId") String disputeId,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        AuthenticatedMerchant merchant = MerchantKeyAuthenticationInterceptor.require(request);
        return disputes.respondAsMerchant(
                merchant.merchantId(), disputeId, DisputeResponseKind.ACCEPTANCE, idempotencyKey);
    }
}
