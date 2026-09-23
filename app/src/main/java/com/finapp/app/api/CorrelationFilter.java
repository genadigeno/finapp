package com.finapp.app.api;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation identifier, and every response a copy of it.
 *
 * <p>Closes the gap P0-TSK-024 left recorded: the error contract carries a {@code correlationId}
 * member that nothing populated in production, so the one thing a client could quote when
 * reporting a problem was always absent.
 *
 * <h2>It must wrap error handling, not just the handler</h2>
 *
 * <p>The scope is entered here, in a filter at {@link Ordered#HIGHEST_PRECEDENCE}, and closed
 * only after the whole chain has run — including {@code @ExceptionHandler}. That ordering is the
 * requirement, not a preference. Establishing the scope inside a controller was tried while
 * writing P0-TSK-024's tests and failed: a {@code try}-with-resources closes before the
 * exception it raised reaches an exception handler, so the renderer ran with nothing in scope
 * and the identifier reached the log and never the client. It is the same shape as the outbox
 * relay defect, where a {@code catch} attached to a try-with-resources ran after the resource
 * closed.
 *
 * <h2>The platform owns the identifier; the caller's value is only echoed</h2>
 *
 * <p>ADR-0034. The correlation identifier is <strong>always</strong> minted here. An inbound
 * {@value #HEADER} is never adopted as it, because that value reaches every log line, every span,
 * four durable columns and every problem-detail body — and a caller can put anything in it.
 * Probing during {@code P0-TSK-033} confirmed {@code jane.doe@example.com},
 * {@code acct:GB29NWBK60161331926819}, {@code customer-1990-05-14} and {@code +447700900123} were
 * all accepted verbatim, which is a caller writing personal and financial data into systems with
 * different access control and months of retention ({@code INV-AUD-02}).
 *
 * <p><strong>Narrowing the charset was considered and does not work</strong>, which is why this is
 * structural rather than lexical. A date of birth, a phone number and an account number are all
 * alphanumeric, and any charset narrow enough to exclude them cannot carry a UUID or a W3C trace
 * value — which is the entire reason the header is accepted. A lexical control cannot express the
 * property required.
 *
 * <p>A well-formed inbound value therefore becomes a <em>client reference</em>: echoed back in
 * {@value #CLIENT_HEADER} and carried nowhere else. Not the MDC, not a span, not a column, not the
 * problem detail. The client keeps its join — it logs our identifier from the response, and a
 * gateway can match a response to a request it no longer holds a connection for — and our logs
 * stop being searchable by a caller-chosen string, which is precisely the property that made the
 * disclosure possible.
 *
 * <p>The charset and bound remain, and are still enforced by {@code CorrelationId}. They are no
 * longer the disclosure control — they never could be — but they are still what stops the echoed
 * value being a log-injection or response-splitting vector, and what bounds it.
 *
 * <p>A rejected header does <strong>not</strong> fail the request. A malformed diagnostic hint is
 * not a reason to refuse someone's payment: the platform's identifier is unaffected and nothing is
 * echoed. The rejection is logged — without the offending value, which would put the very bytes we
 * refused into the log we were protecting.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
@RequiredArgsConstructor
public class CorrelationFilter extends OncePerRequestFilter {

    /**
     * The platform's identifier, on every response. The name clients quote in support requests.
     *
     * <p>Accepted on a request too, but since ADR-0034 an inbound value is a <em>hint returned to
     * the sender</em> rather than the identifier of the flow.
     */
    public static final String HEADER = "X-Correlation-Id";

    /**
     * The caller's own value, echoed back untouched.
     *
     * <p>Present only when the caller supplied a well-formed {@value #HEADER}. It exists so an
     * asynchronous caller or a gateway can match a response to a request, and it reaches no sink:
     * echoing a validated, bounded value back to whoever sent it discloses nothing they did not
     * already have.
     */
    public static final String CLIENT_HEADER = "X-Client-Correlation-Id";

    private final IdGenerator ids;

    @Override
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Always ours. Never the caller's (ADR-0034).
        CorrelationId correlationId = CorrelationId.generate(ids);

        // Set before the chain runs, so both are present even if the response is committed early.
        response.setHeader(HEADER, correlationId.value());
        clientReference(request).ifPresent(reference -> response.setHeader(CLIENT_HEADER, reference));

        try (CorrelationContext.Scope ignored =
                CorrelationContext.enter(Correlation.startingWith(correlationId))) {
            chain.doFilter(request, response);
        }
    }

    /**
     * The caller's value, if it is safe to echo.
     *
     * <p>Validated through {@link CorrelationId} rather than by a second copy of the rules: the
     * charset and bound that make a value safe to put in a response header are the same ones that
     * made it safe to put in a log line, and two copies would drift. The returned {@code String}
     * is deliberately not a {@code CorrelationId} — it is not one, and giving it that type is how
     * it would end up being passed to something that propagates it.
     */
    private Optional<String> clientReference(HttpServletRequest request) {
        String supplied = request.getHeader(HEADER);
        if (supplied == null || supplied.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(CorrelationId.of(supplied).value());
        } catch (IllegalArgumentException | NullPointerException rejected) {
            // Deliberately not logging the value. CorrelationId refuses it precisely because it
            // could contain characters that corrupt a log line, and writing it out to explain
            // that we refused it would achieve exactly what the refusal prevented.
            log.warn(
                    "Rejected a malformed {} header on {}; it is not echoed",
                    HEADER,
                    request.getRequestURI());
            return Optional.empty();
        }
    }
}
