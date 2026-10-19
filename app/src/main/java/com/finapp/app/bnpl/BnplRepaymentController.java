package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * The customer's BNPL repayment doors (`P12-TSK-018`; {@code PHASE_12_PLAN.md} section 9, {@code BNPL_LIFECYCLES.md}
 * section 4.13; owner decision B6 - the wallet is the only source): repay one's own agreement from one's own wallet -
 * allocated by the agreement's pinned order, oldest due first, the rest held as credit; the repayment that settles every
 * receivable closes the agreement and refunds its credit balance - keyed ({@code bnpl.repayment:CUSTOMER:<id>}) under the
 * platform's conditional step-up, {@code 201} with the repayment; and read one's own repayments. Another party's agreement
 * is the uniform {@code 404}. The source is never the body's to name.
 */
@RestController
@RequestMapping(path = "/me/bnpl-agreements", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class BnplRepaymentController {

    @NonNull private final BnplRepaymentDesk desk;

    /** Repays the caller's own agreement from their wallet. */
    @PostMapping(path = "/{id}/repayments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplRepaymentDesk.BnplRepaymentView repayBnplAgreement(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplRepaymentBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.repay(BnplOfferController.current(request), id, idempotencyKey, body);
    }

    /** The caller's own agreement's repayments and their allocations, newest first. */
    @GetMapping("/{id}/repayments")
    public BnplRepaymentDesk.BnplRepaymentListView listBnplRepayments(@PathVariable("id") String id,
            HttpServletRequest request) {
        return desk.list(BnplOfferController.current(request), id);
    }

    /**
     * A repayment: the amount and its currency, the amount an exact decimal at the currency's minor units. Closed - an
     * unknown field is {@code 422}; the source is the customer's own wallet, never the body's to name (B6).
     */
    @ClosedBody
    public record BnplRepaymentBody(
            @NotBlank @Size(max = 32) String amount,
            @NotBlank @Size(max = 3) String currency) {}
}
