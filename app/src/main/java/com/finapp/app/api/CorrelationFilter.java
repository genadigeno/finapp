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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <h2>The inbound header is untrusted</h2>
 *
 * <p>A client may supply {@value #HEADER} so its own logs and ours can be joined. That value
 * arrives from outside and is treated accordingly: {@code CorrelationId} validates it against a
 * default-deny charset and rejects rather than sanitises, because a correlation identifier ends
 * up in log lines and a permissive one is a log-injection vector.
 *
 * <p>A rejected header does <strong>not</strong> fail the request. A malformed diagnostic hint is
 * not a reason to refuse someone's payment: the platform generates its own identifier and carries
 * on. The rejection is logged — without echoing the offending value, which would put the very
 * bytes we refused into the log we were protecting.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    /** The inbound and outbound header. Also the name clients quote in support requests. */
    public static final String HEADER = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);

    private final IdGenerator ids;

    public CorrelationFilter(IdGenerator ids) {
        this.ids = ids;
    }

    @Override
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        CorrelationId correlationId = inboundOrGenerated(request);
        // Set before the chain runs, so it is present even if the response is committed early.
        response.setHeader(HEADER, correlationId.value());

        try (CorrelationContext.Scope ignored =
                CorrelationContext.enter(Correlation.startingWith(correlationId))) {
            chain.doFilter(request, response);
        }
    }

    private CorrelationId inboundOrGenerated(HttpServletRequest request) {
        String supplied = request.getHeader(HEADER);
        if (supplied == null || supplied.isBlank()) {
            return CorrelationId.generate(ids);
        }
        try {
            return CorrelationId.of(supplied);
        } catch (IllegalArgumentException | NullPointerException rejected) {
            // Deliberately not logging the value. CorrelationId refuses it precisely because it
            // could contain characters that corrupt a log line, and writing it out to explain
            // that we refused it would achieve exactly what the refusal prevented.
            log.warn(
                    "Rejected a malformed {} header on {}; generating one instead",
                    HEADER,
                    request.getRequestURI());
            return CorrelationId.generate(ids);
        }
    }
}
