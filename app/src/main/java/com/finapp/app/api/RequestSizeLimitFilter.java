package com.finapp.app.api;

import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request body larger than the platform will read.
 *
 * <p><strong>Why an explicit limit rather than the framework's.</strong> Spring Boot's size
 * settings cover form posts and multipart uploads; a JSON body is streamed with no default bound
 * at all. An unbounded request body is a denial-of-service vector that costs an attacker one
 * connection, and `.claude/rules/security.md` says external input is untrusted — a body of
 * unstated length is the least trusted input there is.
 *
 * <p>It is also what makes {@code api.PayloadTooLarge} real. A code in the catalogue that nothing
 * can raise is a promise to clients that nothing keeps.
 *
 * <h2>Both ways a body can be too large</h2>
 *
 * <p>A declared {@code Content-Length} over the limit is refused immediately, without reading a
 * byte — the cheap case, and the common one. A request that declares no length (chunked transfer
 * encoding) can only be discovered while reading, so the body is wrapped in a stream that stops
 * at the limit. Checking only {@code Content-Length} would be a limit an attacker opts out of by
 * omitting a header.
 *
 * <h2>Why it renders the response itself</h2>
 *
 * <p>A filter runs before the {@code DispatcherServlet}, so throwing here would never reach
 * {@link ApiErrorHandler} and the client would get the container's default error page — the
 * framework's own shape on a path the contract covers. {@link ProblemDetailWriter} exists for
 * that reason.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final ProblemDetailWriter problems;
    private final long maxBytes;

    public RequestSizeLimitFilter(
            ProblemDetailWriter problems,
            @Value("${finapp.api.max-request-bytes:1048576}") long maxBytes) {
        this.problems = problems;
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxRequestBytes must be positive but was " + maxBytes);
        }
        this.maxBytes = maxBytes;
    }

    /** The limit in bytes; 1 MiB by default, overridable per environment. */
    public long maxBytes() {
        return maxBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            // Refused without reading the body. The detail names OUR limit, not anything the
            // caller sent - a client can act on "the maximum is 1048576 bytes", and echoing what
            // they sent would be repeating untrusted input back out.
            log.warn(
                    "Refused a request to {} declaring {} bytes; the limit is {}",
                    request.getRequestURI(),
                    declared,
                    maxBytes);
            problems.write(
                    request,
                    response,
                    PlatformErrorCode.PAYLOAD_TOO_LARGE,
                    "The maximum request body is " + maxBytes + " bytes.");
            return;
        }
        // No declared length, or one within the limit. Either way the body is wrapped, because a
        // Content-Length is a claim by the caller and a chunked request makes no claim at all.
        chain.doFilter(new BoundedRequest(request, maxBytes), response);
    }
}
