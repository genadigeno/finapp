package com.finapp.app.lending;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's loan offer doors (`P11-TSK-011`; PHASE_11_PLAN.md section 9, LENDING_LIFECYCLES.md section 4.4): read
 * one's own offer - its terms, the illustrative schedule or the minimum-payment rule, the expiry and the document - and
 * decline it while it is {@code OFFERED} and unexpired, keyed ({@code lending.offer-decline:CUSTOMER:<id>}) under the
 * platform's conditional step-up. The decline has no body. The acceptance is `P11-TSK-012`'s door.
 */
@RestController
@RequestMapping(path = "/me/loan-offers", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class LoanOfferController {

    @NonNull private final LoanOfferDesk desk;

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
        return desk.decline(current(request), id, idempotencyKey);
    }

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
