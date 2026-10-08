package com.finapp.app.credit;

import com.finapp.app.api.ClosedBody;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * The customer's credit decision request doors (`P10-TSK-014`; PHASE_10_PLAN.md section 9): submit - keyed,
 * asynchronous, {@code 202} with the request {@code SUBMITTED} - read one's own request, and cancel it before it is
 * evaluated. The body is closed and holds the terms alone: no score, rate, limit or decision field exists to be sent.
 */
@RestController
@RequestMapping(path = "/me/credit/decision-requests", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class CreditDecisionRequestController {

    @NonNull private final CreditDecisionRequestDesk desk;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CreditDecisionRequestDesk.CreditDecisionRequestView submit(
            @Valid @RequestBody CreditDecisionRequestBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.submit(current(request), idempotencyKey, body);
    }

    @GetMapping("/{id}")
    public CreditDecisionRequestDesk.CreditDecisionRequestView read(@PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    @PostMapping("/{id}/cancellation")
    @RequiresIdempotencyKey
    public CreditDecisionRequestDesk.CreditDecisionRequestView cancel(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.cancel(current(request), id, idempotencyKey);
    }

    /**
     * A decision request's terms: the product, the amount and its currency as an exact decimal, the term in months (an
     * instalment product's alone), and the declared monthly figures, optional. Closed - an unknown field is {@code 422}.
     */
    @ClosedBody
    public record CreditDecisionRequestBody(
            @NotBlank @Size(max = 32) String product,
            @NotBlank @Size(max = 32) String amount,
            @NotBlank @Size(max = 3) String currency,
            @Min(1) @Max(600) Integer termMonths,
            @Size(max = 32) String declaredMonthlyIncome,
            @Size(max = 32) String declaredMonthlyExpenditure) {}

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/credit is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
