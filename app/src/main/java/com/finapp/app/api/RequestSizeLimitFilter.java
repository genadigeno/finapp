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

    /**
     * The one route whose JSON envelope legitimately exceeds the global bound (`P8-TSK-003`,
     * ADR-0066 §4): a settlement upload's base64 of up to 8 MiB decoded is ~11.2 MiB on the
     * wire. Exactly this method and path — a per-route carve-out, never a raised global
     * limit, so every other surface keeps the 1 MiB bound. The DOMAIN still refuses decoded
     * content over 8,388,608 bytes as {@code 413 settlement.FileTooLarge} with its audit
     * record written; this bound only lets that envelope arrive.
     */
    static final String SETTLEMENT_UPLOAD_PATH = "/v1/operator/settlement/files";

    private final ProblemDetailWriter problems;
    private final long maxBytes;
    private final long settlementUploadMaxBytes;

    public RequestSizeLimitFilter(
            ProblemDetailWriter problems,
            @Value("${finapp.api.max-request-bytes:1048576}") long maxBytes,
            @Value("${finapp.api.settlement-upload-max-request-bytes:12582912}")
                    long settlementUploadMaxBytes) {
        this.problems = problems;
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxRequestBytes must be positive but was " + maxBytes);
        }
        if (settlementUploadMaxBytes < maxBytes) {
            throw new IllegalArgumentException(
                    "settlementUploadMaxRequestBytes must be at least the global bound but was "
                            + settlementUploadMaxBytes);
        }
        this.maxBytes = maxBytes;
        this.settlementUploadMaxBytes = settlementUploadMaxBytes;
    }

    /** The limit in bytes; 1 MiB by default, overridable per environment. */
    public long maxBytes() {
        return maxBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long bound = boundFor(request);
        long declared = request.getContentLengthLong();
        if (declared > bound) {
            // Refused without reading the body. The detail names OUR limit, not anything the
            // caller sent - a client can act on "the maximum is 1048576 bytes", and echoing what
            // they sent would be repeating untrusted input back out.
            log.warn(
                    "Refused a request to {} declaring {} bytes; the limit is {}",
                    request.getRequestURI(),
                    declared,
                    bound);
            problems.write(
                    request,
                    response,
                    PlatformErrorCode.PAYLOAD_TOO_LARGE,
                    "The maximum request body is " + bound + " bytes.");
            return;
        }
        // No declared length, or one within the limit. Either way the body is wrapped, because a
        // Content-Length is a claim by the caller and a chunked request makes no claim at all.
        chain.doFilter(new BoundedRequest(request, bound), response);
    }

    /** Exactly the settlement upload gets its envelope bound; every other request the global. */
    private long boundFor(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                        && SETTLEMENT_UPLOAD_PATH.equals(request.getRequestURI())
                ? settlementUploadMaxBytes
                : maxBytes;
    }
}
