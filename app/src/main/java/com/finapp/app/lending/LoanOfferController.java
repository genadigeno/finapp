package com.finapp.app.lending;

import com.finapp.lending.LendingSpans;
import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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
 * The customer's loan offer doors (`P11-TSK-011`; PHASE_11_PLAN.md section 9, LENDING_LIFECYCLES.md section 4.4): read
 * one's own offer - its terms, the illustrative schedule or the minimum-payment rule, the expiry and the document - and
 * decline it while it is {@code OFFERED} and unexpired, keyed ({@code lending.offer-decline:CUSTOMER:<id>}) under the
 * platform's conditional step-up. The decline has no body. And accept it (`P11-TSK-012`): a {@code MULTI_FACTOR} act,
 * keyed ({@code lending.offer-acceptance:CUSTOMER:<id>}), echoing the offer's terms hash and naming the payout method
 * path X needs - {@code 201} with the loan account opened.
 */
@RestController
@RequestMapping(path = "/me/loan-offers", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class LoanOfferController {

    @NonNull private final LoanOfferDesk desk;
    @NonNull private final LoanAccountDesk accounts;
    @NonNull private final LendingFlowScope flow;

    /** The caller's own offer, exactly as recorded. */
    @GetMapping("/{id}")
    public LoanOfferDesk.LoanOfferView readLoanOffer(@PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    /** Declines the caller's offer - only while it is offered and unexpired on the database clock. */
    @PostMapping("/{id}/decline")
    @RequiresIdempotencyKey
    public LoanOfferDesk.LoanOfferDecision declineLoanOffer(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.doorOnOffer(LendingSpans.DECLINE, id, () -> desk.decline(current(request), id, idempotencyKey));
    }

    /**
     * Accepts the caller's offer - the agreement committed, credit's decision consumed, the account opened - only while
     * it is offered and unexpired on the database clock, and only for the terms whose hash is echoed.
     */
    @PostMapping(path = "/{id}/acceptance", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public LoanAccountDesk.LoanAcceptedView acceptLoanOffer(
            @PathVariable("id") String id,
            @Valid @RequestBody LoanAcceptanceBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.door(LendingSpans.ACCEPT, () -> accounts.accept(current(request), id, idempotencyKey, body,
                Optional.ofNullable(request.getRemoteAddr()), Optional.ofNullable(request.getHeader(HttpHeaders.USER_AGENT))));
    }

    /**
     * An acceptance: the offer's terms hash as the offer read showed it (lowercase hexadecimal), and the customer's own
     * bank-account payment method when the application preferred an external payout - absent otherwise. Closed - an
     * unknown field is {@code 422}; nothing about the evidence is the body's to claim.
     */
    @ClosedBody
    public record LoanAcceptanceBody(
            @NotBlank @Size(max = 64) String termsSha256,
            @Size(max = 36) String payoutMethodId) {}

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/loan-offers is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
