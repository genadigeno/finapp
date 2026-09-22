package com.finapp.app.checkout;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * `/v1/checkout/sessions/confirmation` (`P6-TSK-007`, ADR-0053): the customer's half — the one
 * route on this platform that demands <strong>two</strong> credentials.
 *
 * <h2>Session AND token, and why both</h2>
 *
 * <p>{@code @RequiresSession} proves <em>who</em> is paying — the instrument must be theirs,
 * and the payment is recorded against their party. The token <strong>in the body</strong>
 * proves <em>which offer</em> they are paying, and is the only thing that resolves it: a
 * customer arriving from a merchant's checkout page has no reason to know the session's
 * identifier, and giving them one would make the route addressable by anybody who could guess
 * it.
 *
 * <p><strong>In the body rather than the path, and a build rule forced it.</strong> The first
 * design put the token in the URL, which is how hosted checkout usually looks;
 * {@code CredentialReachesNoEmittedSinkTest} refused it, on its own recorded reasoning that a
 * secret in a URL is in every access log, proxy log and browser history between here and the
 * caller. A request body is the one place this platform lets a secret travel — see
 * {@link ConfirmSessionRequest}.
 *
 * <p>Neither alone is enough, and that is the design: a stolen token buys nothing without a
 * session to pay from, and a session buys nothing without an offer to pay for.
 *
 * <h2>A retry converges</h2>
 *
 * <p>No idempotency key: the machine is the idempotency (the phase plan §9's <em>machine
 * convergence</em>). A second confirm continues the payment the first one opened — finishing a
 * capture stranded by a crash on the way — and answers the session's real state. A session
 * that has genuinely left the flow answers {@code 409} saying which state refused.
 *
 * <h2>The answer is the session's CURRENT state, not an echo</h2>
 *
 * <p>{@code COMPLETED} with an order once the capture landed; still {@code PAYMENT_PENDING}
 * when the provider has not decided, which is honest rather than optimistic
 * ({@code INV-LIFE-03} at the contract, Phase 5's shape unchanged). A declined payment is not
 * an HTTP error: the command was accepted and its domain outcome recorded.
 */
@RestController
@RequestMapping(
        path = "/checkout/sessions/confirmation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
public class CheckoutConfirmationController {

    private final CheckoutService checkout;

    public CheckoutConfirmationController(CheckoutService checkout) {
        this.checkout = Objects.requireNonNull(checkout, "checkout must not be null");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public CheckoutService.SessionView confirmCheckoutSession(
            HttpServletRequest request, @Valid @RequestBody ConfirmSessionRequest body) {
        return checkout.confirm(current(request), body);
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        throw new IllegalStateException(
                "No authenticated session on the request: the confirmation is reachable"
                        + " without SessionAuthenticationInterceptor having run");
    }
}
