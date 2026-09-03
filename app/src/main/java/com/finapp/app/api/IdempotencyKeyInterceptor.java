package com.finapp.app.api;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a request to a {@link RequiresIdempotencyKey} endpoint that carries no usable key.
 *
 * <p><strong>An interceptor, not a filter, and the difference is load-bearing twice over.</strong>
 *
 * <ul>
 *   <li>A filter runs before the dispatcher has chosen a handler, so it cannot know whether
 *       <em>this</em> endpoint declares the requirement. It would have to match on paths — a
 *       second, drifting copy of the routing table.
 *   <li>A filter runs <em>outside</em> {@code @ExceptionHandler}, so anything it throws produces
 *       the container's default page rather than the error contract. {@code P0-TSK-025} met that
 *       and had to render the contract by hand inside its filters. An interceptor runs inside the
 *       dispatcher, so {@link ApiErrorHandler} handles what it throws — the contract is rendered
 *       once, in the one place that knows how.
 * </ul>
 *
 * <p><strong>Rejected before the handler is entered.</strong> {@code preHandle} returning false
 * stops dispatch, so a command endpoint never begins work it would have to undo. That is the same
 * property {@code RequestValidationTest} asserts by counting handler entries rather than reading
 * the response — a rejection issued after the handler ran and did half the work looks identical
 * from outside.
 *
 * <p><strong>Safe methods are exempt.</strong> {@code GET} and {@code HEAD} move no money, so a
 * key means nothing on them; {@code API_CONVENTIONS.md} §6 says they ignore it. The exemption is
 * on the method rather than on the annotation so that annotating a controller class does not
 * accidentally demand a key from its read endpoints.
 */
class IdempotencyKeyInterceptor implements HandlerInterceptor {

    /**
     * Methods that cannot move money, so a key on them is meaningless.
     *
     * <p>{@code OPTIONS} and {@code TRACE} are here for the same reason and are not merely
     * "also safe": a preflight demanding an idempotency key would break CORS for every browser
     * client before the real request was ever sent.
     */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {

        if (!(handler instanceof HandlerMethod handlerMethod) || isSafe(request)) {
            return true;
        }
        if (!requiresKey(handlerMethod)) {
            return true;
        }

        String key = request.getHeader(IdempotencyKeyHeader.NAME);
        if (key == null) {
            throw new ApiException(
                    PlatformErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "No " + IdempotencyKeyHeader.NAME + " on a request to "
                            + handlerMethod.getShortLogMessage(),
                    IdempotencyKeyHeader.NAME
                            + " is required for this operation. Generate one per business action"
                            + " and reuse it when retrying.");
        }

        Optional<String> rejection = IdempotencyKeyHeader.rejectionReason(key);
        if (rejection.isPresent()) {
            // VALIDATION_FAILED, not IDEMPOTENCY_KEY_REQUIRED: the client supplied a key and must
            // fix it, which is the ordinary validation shape rather than a missing precondition.
            //
            // The reason never quotes the value. It is the caller's own input, and echoing it back
            // is how a header becomes a reflection vector (INV-AUD-02).
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "Unusable " + IdempotencyKeyHeader.NAME + ": " + rejection.get(),
                    IdempotencyKeyHeader.NAME + " " + rejection.get() + ".");
        }
        return true;
    }

    private static boolean isSafe(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod());
    }

    /** The method's own declaration, or its controller's. */
    private static boolean requiresKey(HandlerMethod handlerMethod) {
        return handlerMethod.getMethodAnnotation(RequiresIdempotencyKey.class) != null
                || handlerMethod.getBeanType().isAnnotationPresent(RequiresIdempotencyKey.class);
    }
}
