package com.finapp.app.payments;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.payments.PaymentIntentId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment surface (`P5-TSK-011`): the phase's machinery meets its customer.
 *
 * <h2>The asynchronous-outcome contract shape</h2>
 *
 * <p>{@code POST} answers {@code 201} with the intent view <strong>including status</strong>;
 * the confirmation answers {@code 200} with the intent's <em>real</em> state — {@code SUCCEEDED}
 * after the chained capture, {@code FAILED} with its mapped reason, or <strong>honestly
 * {@code PROCESSING}</strong> when the platform genuinely does not know yet
 * ({@code INV-LIFE-03} at the contract; `PHASE_5_PLAN.md` §9 — the shape `P4-TSK-008`
 * established, inherited unchanged by genuinely asynchronous outcomes). A judged failure is
 * never an HTTP error: the command was accepted and its domain outcome recorded.
 *
 * <h2>Ownership</h2>
 *
 * <p>The POST takes no identifier of anything a stranger could point at a victim — the wallet
 * resolves through the caller's own live customer inside the command, and the instrument must
 * be the caller's own. Every {@code '{id}'} route resolves through {@code party_id = ?} in the
 * statement: unknown, not-yours and malformed are one {@code 404} (the {@code P1-TSK-016}
 * reasoning).
 *
 * <h2>Confirmation and cancellation carry no idempotency key, deliberately</h2>
 *
 * <p>The machine is the idempotency (the `P3-TSK-021` precedent, named by the phase plan §9 as
 * <em>machine convergence</em>): a retried confirm converges on what the first did — and
 * finishes a stranded capture on the way — and a retried cancel converges on {@code CANCELLED}.
 */
@RestController
@RequestMapping(path = "/payments", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = Objects.requireNonNull(payments, "payments must not be null");
    }

    /**
     * Creates the caller's payment intent, or replays the judgement — {@code 201} for the
     * replay as well as the creation (the convergence idiom: what distinguishes the creating
     * call is the records, never the answer).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentService.PaymentView createPayment(
            @Valid @RequestBody PaymentCreateRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            HttpServletRequest request) {
        // Method names here are published operationIds (the P2-TSK-006 `view_1` lesson).
        return payments.create(current(request), body, idempotencyKey);
    }

    /**
     * Confirms the payment: commits the dispatch, asks the provider, applies the outcome, and
     * chains the capture after a synchronous authorization — answering the intent's real state.
     * {@code 200}, not {@code 201}: the resource already exists and this advances it.
     */
    @PostMapping(path = "/{id}/confirmation")
    public PaymentService.PaymentView confirmPayment(
            @PathVariable("id") String id, HttpServletRequest request) {
        return payments.confirm(current(request), parsedOrAbsent(id));
    }

    /**
     * Cancels the payment — winning only the confirmation window (ADR-0045: once the dispatch
     * has committed the provider may already have acted). {@code 200} with the cancelled view;
     * a payment past the window is the one {@code 409 payments.NotCancellable}.
     */
    @DeleteMapping("/{id}")
    public PaymentService.PaymentView cancelPayment(
            @PathVariable("id") String id, HttpServletRequest request) {
        return payments.cancel(current(request), parsedOrAbsent(id));
    }

    /** The caller's payment — its current state, the mapped reason included when it failed. */
    @GetMapping("/{id}")
    public PaymentService.PaymentView findPayment(
            @PathVariable("id") String id, HttpServletRequest request) {
        return payments
                .find(current(request), parsedOrAbsent(id))
                .orElseThrow(PaymentController::paymentNotFound);
    }

    /** The caller's payments, newest first. */
    @GetMapping
    public List<PaymentService.PaymentView> listPayments(HttpServletRequest request) {
        return payments.list(current(request));
    }

    // -----------------------------------------------------------------

    /**
     * A malformed identifier is treated as an identifier that names nothing — one {@code 404}
     * with unknown and not-yours ({@code P1-TSK-016}; malformed-equals-absent).
     */
    private static PaymentIntentId parsedOrAbsent(String raw) {
        try {
            return PaymentIntentId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw paymentNotFound();
        }
    }

    private static ApiException paymentNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No payment of the caller's matches the requested identifier",
                "no such payment");
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/payments is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
