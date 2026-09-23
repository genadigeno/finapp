package com.finapp.app.paymentmethods;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.paymentmethods.PaymentMethodId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's payment instruments over HTTP (`P5-TSK-005`): attach under the conditional second
 * factor, list, detach — the beneficiary surface's shape at the instrument boundary.
 *
 * <h2>The step-up point</h2>
 *
 * <p>Attaching an instrument is where an account takeover monetises, so the attach demands
 * {@code MULTI_FACTOR} <strong>of an identity that has a factor</strong> — a domain check in
 * {@link PaymentMethodService}, not an annotation here, because the requirement is conditional
 * on enrolment and a static annotation would lock out every password-only customer (the
 * `P4-TSK-007` reasoning verbatim). The class-level rule is {@link RequiresSession}.
 *
 * <h2>Ownership</h2>
 *
 * <p>The {@code /v1/me} shape: attach and list take no identifier of anything at all — the
 * body carries only the one-time tokenisation grant — and the detach's one path identifier
 * resolves through statements whose ownership predicate is {@code party_id = ?} (ADR-0031):
 * unknown, not-yours and malformed are one {@code 404}, while the caller's own
 * already-detached row converges on {@code 204}.
 */
@RestController
@RequestMapping(path = "/me/payment-methods", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class PaymentMethodController {

    @NonNull private final PaymentMethodService paymentMethods;

    /**
     * Attaches the instrument the grant tokenises to, or converges on the caller's live row
     * for it — {@code 201} either way (the convergence idiom): what distinguishes creation is
     * the records, never the answer. No idempotency key: attaching an instrument moves no
     * money, and the natural-key convergence is the retry mechanism.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentMethodService.PaymentMethodView attach(
            @Valid @RequestBody AttachPaymentMethodRequest body, HttpServletRequest request) {
        return paymentMethods.attach(current(request), body.clientToken());
    }

    /**
     * The caller's live payment methods, oldest first.
     *
     * <p>Named {@code listPaymentMethods} rather than {@code list}: springdoc derives the
     * published operationId from the Java method name, and a second {@code list} handler
     * renames the beneficiary surface's PUBLISHED {@code list} to {@code list_1} - a breaking
     * change to an endpoint this class never touched, caught by the contract classifier and
     * withdrawn (the `P2-TSK-006` {@code view_1} precedent).
     */
    @GetMapping
    public List<PaymentMethodService.PaymentMethodView> listPaymentMethods(
            HttpServletRequest request) {
        return paymentMethods.list(current(request));
    }

    /**
     * Detaches the caller's instrument — {@code 204} for the converged repeat as well as the
     * detach (a retried {@code DELETE} whose first response was lost must not read as a
     * failure). The detached row survives as evidence (`P5-TSK-004`).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void detach(@PathVariable("id") String id, HttpServletRequest request) {
        if (!paymentMethods.detach(current(request), parsedOrAbsent(id))) {
            throw paymentMethodNotFound();
        }
    }

    // -----------------------------------------------------------------

    /**
     * A malformed identifier is treated as an identifier that names nothing — one {@code 404}
     * with unknown and not-yours (malformed-equals-absent, the standing idiom).
     */
    private static PaymentMethodId parsedOrAbsent(String raw) {
        try {
            return PaymentMethodId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw paymentMethodNotFound();
        }
    }

    private static ApiException paymentMethodNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No payment method of the caller's matches the requested identifier",
                "no such payment method");
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/payment-methods is reachable"
                        + " without SessionAuthenticationInterceptor having run");
    }
}
