package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.bnpl.BnplSpans;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's BNPL early payoff doors (`P12-TSK-022`; {@code PHASE_12_PLAN.md} section 9, A18, A21; lending's
 * {@code LoanController} payoff doors): quote what ends one's own agreement on a date - today or up to the pinned horizon
 * ahead - and pay it off on that date from one's own wallet, no fee. Another party's agreement, and a quote not of it, is
 * the uniform {@code 404}. The amount is never the body's to name.
 */
@RestController
@RequestMapping(path = "/me/bnpl-agreements", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class BnplPayoffController {

    @NonNull private final BnplPayoffDesk desk;
    @NonNull private final BnplFlowScope flow;

    /**
     * Quotes the payoff of the caller's own agreement: what ends it on the good-through date - today when the body names
     * none - with what it is made of; pay-in-N's interest projected to the eve of that date. A quote moves no money. Keyed
     * ({@code bnpl.payoff-quote:CUSTOMER:<id>}), the platform's conditional step-up, {@code 201} with the quote.
     */
    @PostMapping(path = "/{id}/payoff-quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplPayoffDesk.BnplPayoffQuoteView quoteBnplPayoff(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplPayoffQuoteBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.doorOnAgreement(BnplSpans.QUOTE, id, () -> desk.quote(BnplOfferController.current(request), id, idempotencyKey, body));
    }

    /**
     * Pays off the caller's own agreement on its quote's good-through date: every unbilled instalment billed, the held
     * credit applied, the amount recomputed - a quote no longer ending the agreement is {@code 409 bnpl.PayoffQuoteStale},
     * nothing posted - then one repayment from the wallet allocated to every component, the credit balance refunded and
     * the agreement closed ({@code PAID_OFF_EARLY}). Keyed ({@code bnpl.payoff:CUSTOMER:<id>}), {@code MULTI_FACTOR},
     * {@code 201} with the payoff.
     */
    @PostMapping(path = "/{id}/payoff", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public BnplPayoffDesk.BnplPayoffView payOffBnplAgreement(
            @PathVariable("id") String id,
            @Valid @RequestBody BnplPayoffBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.doorOnAgreement(BnplSpans.PAYOFF, id, () -> desk.execute(BnplOfferController.current(request), id, idempotencyKey, body));
    }

    /**
     * A payoff quote: the good-through date, an ISO date - today when absent. Closed - an unknown field is {@code 422}; the
     * figure is the domain's to compute, never the body's to name.
     */
    @ClosedBody
    public record BnplPayoffQuoteBody(@Size(max = 10) String goodThrough) {}

    /** A payoff: the quote it executes. Closed - an unknown field is {@code 422}; the amount is the quote's. */
    @ClosedBody
    public record BnplPayoffBody(@NotBlank @Size(max = 36) String quoteId) {}
}
