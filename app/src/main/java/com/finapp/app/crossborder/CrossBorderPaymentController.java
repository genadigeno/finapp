package com.finapp.app.crossborder;

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
 * The customer's cross-border payments (`P9-TSK-019`, `PHASE_9_PLAN.md` section 9): authorize an offer -
 * asynchronous, {@code 202} with the shaped status - and follow it. Step-up when a factor is enrolled; the
 * caller's own payments only.
 */
@RestController
@RequestMapping(path = "/me/cross-border/payments", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class CrossBorderPaymentController {

    @NonNull private final CrossBorderPaymentDesk desk;
    @NonNull private final com.finapp.platform.telemetry.Spans domainSpans;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CrossBorderPaymentDesk.CrossBorderPaymentView authorizeCrossBorderPayment(
            @Valid @RequestBody CrossBorderPaymentRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return domainSpans.within(com.finapp.app.telemetry.Phase9Spans.AUTHORIZE, java.util.Map.of(),
                () -> desk.authorize(current(request), idempotencyKey, body));
    }

    /**
     * Requests the payment's cancellation by recall (`P9-TSK-024`). It takes no body: the path names the payment, and
     * a request with no inputs has nothing a client could smuggle into it - no rate, no amount.
     */
    @PostMapping("/{id}/cancellation")
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CrossBorderPaymentDesk.CrossBorderPaymentView cancelCrossBorderPayment(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return desk.cancel(current(request), idempotencyKey, id);
    }

    @GetMapping("/{id}")
    public CrossBorderPaymentDesk.CrossBorderPaymentView readCrossBorderPayment(
            @PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/cross-border/payments is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }

    /** The offer to authorize, by its quote's id. A closed body: never a rate, never an amount. */
    @ClosedBody
    public record CrossBorderPaymentRequest(@NotBlank @Size(min = 1, max = 64) String quoteId) {}
}
