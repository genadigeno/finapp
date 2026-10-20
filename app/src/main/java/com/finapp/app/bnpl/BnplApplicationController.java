package com.finapp.app.bnpl;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.bnpl.BnplSpans;
import com.finapp.identity.Session;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.sharedkernel.security.Sensitive;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * The customer's BNPL application doors (`P12-TSK-010`; PHASE_12_PLAN.md section 9, BNPL_LIFECYCLES.md section 4.2):
 * apply at checkout - keyed per principal ({@code bnpl.application:CUSTOMER:<id>}), a {@code MULTI_FACTOR} act,
 * {@code 202} with the application {@code AWAITING_DECISION} and credit's decision request opened beside it; read one's
 * own application; and withdraw it while credit's request is still cancellable - keyed
 * ({@code bnpl.application-withdrawal:CUSTOMER:<id>}), the platform's conditional step-up. The body is closed: no
 * amount, rate, offer, score, merchant or decision field exists to be sent - the amount is the session's gross, and the
 * session is named by its TOKEN, the checkout's capability (the confirm door's {@code CheckoutSessions.byToken}
 * precedent; an id is not a capability), in the body, never the URL.
 */
@RestController
@RequestMapping(path = "/me/bnpl-applications", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class BnplApplicationController {

    @NonNull private final BnplApplicationDesk desk;
    @NonNull private final BnplFlowScope flow;

    /** Applies for a BNPL product at checkout - one act opening credit's decision request in the same commit. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BnplApplicationDesk.BnplApplicationView submitBnplApplication(
            @Valid @RequestBody BnplApplicationBody body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.door(BnplSpans.APPLY, () -> desk.submit(current(request), idempotencyKey, body));
    }

    /** The caller's own application: its status, and - once declined - credit's reasons in the customer's words. */
    @GetMapping("/{id}")
    public BnplApplicationDesk.BnplApplicationView readBnplApplication(
            @PathVariable("id") String id, HttpServletRequest request) {
        return desk.read(current(request), id);
    }

    /** Withdraws the caller's application, cancelling credit's request - only before credit has evaluated it. */
    @PostMapping("/{id}/withdrawal")
    @RequiresIdempotencyKey
    public BnplApplicationDesk.BnplApplicationView withdrawBnplApplication(
            @PathVariable("id") String id,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        return flow.doorOnApplication(BnplSpans.WITHDRAW, id, () -> desk.withdraw(current(request), id, idempotencyKey));
    }

    /**
     * A BNPL application: the checkout session's token (wrapped from deserialisation - never logged, stored or echoed),
     * the product and the plan. Closed - an unknown field is {@code 422}.
     *
     * @param sessionToken the checkout session this purchase is in - the capability the customer holds
     * @param product {@code BNPL_PAY_IN_4} or {@code BNPL_PAY_IN_N}
     * @param plan one of the product's plans - {@code PAY_IN_4}, or {@code PAY_IN_3_MONTHS}, {@code PAY_IN_6_MONTHS},
     *     {@code PAY_IN_12_MONTHS}
     */
    @ClosedBody
    public record BnplApplicationBody(
            @NotNull Sensitive<String> sessionToken,
            @NotBlank @Size(max = 32) String product,
            @NotBlank @Size(max = 32) String plan) {}

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/bnpl-applications is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
