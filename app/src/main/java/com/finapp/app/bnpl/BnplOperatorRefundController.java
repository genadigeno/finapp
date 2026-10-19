package com.finapp.app.bnpl;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/operator/bnpl-purchases/{id}/refunds} (`P12-TSK-021`; ADR-0105 decision 9, OQ-9 - the operator door
 * beside the merchant's, its route and permission this task's design): an operator refunds a BNPL purchase on its
 * merchant's behalf - for a merchant that cannot act over its key (suspended, or instructing out of band). Its own route,
 * because the authentication populations are disjoint (ADR-0052; {@code OperatorMerchantPayoutController}'s precedent).
 * {@code PAYMENT_REFUND} - the payments refund's one permission, the money-operating population (the ADR's default);
 * keyed per operator ({@code bnpl.refund:<actor type>:<id>} - {@code CUSTOMER}-typed for an operator session today), a
 * reason required and audited with actor, target, reason, correlation and outcome; an operator's refund of their own party's purchase is {@code 403 bnpl.SelfDealingRefused},
 * recorded {@code FAILED}. The same T-ref body as the merchant's door: no four-eyes and no threshold review (OQ-9).
 */
@RestController
@RequestMapping(path = "/operator/bnpl-purchases", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BnplOperatorRefundController {

    @NonNull private final BnplRefundDesk desk;

    /** An operator refunds the BNPL purchase on its merchant's behalf. */
    @PostMapping(path = "/{id}/refunds", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.PAYMENT_REFUND)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplRefundDesk.BnplOperatorRefundView refundBnplPurchaseForMerchant(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplRefundController.BnplRefundBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return desk.refundAsOperator(id, idempotencyKey, body);
    }
}
