package com.finapp.app.api;

import com.finapp.consent.ConsentErrorCode;
import com.finapp.consent.ConsentNotGrantedException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.ProblemDetail;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotencyInProgressException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every API failure as {@code application/problem+json}, and nothing else.
 *
 * <h2>Why this extends Spring's own handler</h2>
 *
 * <p>The errors that matter are the ones we did not raise: an unknown path, a method the route
 * does not support, a body that will not parse, a media type nobody reads, a missing parameter,
 * a path variable of the wrong type. The framework rejects all of these <em>before</em> our code
 * runs and by default answers in its own shape — so a client sees two error formats depending on
 * how far into the request it got, and nothing in a normal test suite notices because each looks
 * reasonable alone.
 *
 * <p>This class was first written with one {@code @ExceptionHandler} per framework exception. A
 * review found the gap that approach always leaves: a missing query parameter and a wrong-typed
 * path variable both returned <strong>{@code 500 api.InternalError}</strong> — unambiguous
 * client mistakes reported as platform failures. A client may retry a 500 forever on a request
 * that can never succeed, and a spike of malformed requests is indistinguishable from an outage
 * on every error-rate dashboard.
 *
 * <p>Enumerating types fixes the ones somebody thought of. A second attempt — testing for
 * Spring's {@code ErrorResponse} interface — fixed the missing parameter and still missed the
 * type mismatch, which does not implement it. The failure is structural: the set of framework
 * exceptions is Spring's to define, so the mapping from exception to status has to be Spring's
 * too. {@link ResponseEntityExceptionHandler} is where Spring keeps it, and every one of them
 * arrives at {@link #handleExceptionInternal} with the status already decided. All this class
 * does then is render our body instead of theirs.
 *
 * <h2>What never reaches the client</h2>
 *
 * <p>Exception messages, stack traces, class names, SQL, provider payloads. The body is built
 * from the {@link ErrorCode} alone plus authored text where a throw site supplied any; there is
 * no path from a {@code Throwable} to the response. The exception goes to the log with the
 * flow's correlation identifier, and the client receives that identifier — the one thing that
 * usefully connects a person reporting a problem to the record of it ({@code INV-AUD-02}).
 */
@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** RFC 9457's media type. Not {@code application/json}: the shape is a contract of its own. */
    public static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    /** Raised deliberately by our own code, carrying the code that says what went wrong. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetailBody> handleApiException(ApiException exception, HttpServletRequest request) {
        // Warn, not error: a client error is the API working. The message may name whatever makes
        // it diagnosable, because it goes to the log and not to the wire.
        LOGGER.warn("API error {}: {}", exception.errorCode().code(), exception.getMessage(), exception);
        return render(
                ProblemDetail.of(
                        exception.errorCode(),
                        request.getRequestURI(),
                        exception.clientDetail().orElse(null)));
    }

    /**
     * Anything with no more specific handler — a genuine accident.
     *
     * <p>Logged at error, because nobody expected it; rendered with a body that says nothing,
     * because its message is the least trustworthy string in the system to be publishing.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetailBody> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled error on {}", request.getRequestURI(), exception);
        return render(ProblemDetail.of(PlatformErrorCode.INTERNAL_ERROR, request.getRequestURI()));
    }

    /**
     * A body that ran past the size limit while being read.
     *
     * <p>Spring reports it as an unreadable message, because that is what a message converter
     * sees when the stream refuses to continue. Left alone it would surface as
     * {@code api.MalformedRequest} - true in a narrow sense and useless to the caller, who
     * would have no idea their body was simply too big. The cause chain is searched rather than
     * the top-level type, since the converter wraps whatever it caught.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof BoundedRequest.RequestTooLargeException tooLarge) {
                String path = pathOf(request);
                LOGGER.warn("Request body to {} exceeded {} bytes", path, tooLarge.maxBytes());
                return ResponseEntity.status(HttpStatus.valueOf(PlatformErrorCode.PAYLOAD_TOO_LARGE.status()))
                        .contentType(PROBLEM_JSON)
                        .body(ProblemDetailBody.from(ProblemDetail.of(
                                                PlatformErrorCode.PAYLOAD_TOO_LARGE,
                                                path,
                                                "The maximum request body is "+tooLarge.maxBytes()+" bytes.")));
            }
        }
        return super.handleHttpMessageNotReadable(exception, headers, status, request);
    }

    /**
     * A request that was well-formed and not valid.
     *
     * <p>Rendered as **422**, not the 400 Spring defaults to. The distinction is the one
     * {@code ERROR_CONTRACT.md} §3 keeps: 400 means the serialiser is wrong and only a developer
     * can act on it; 422 means the data is wrong and the person filling in the form can.
     *
     * <p>The detail names the fields and the constraint each broke. It does <strong>not</strong>
     * include the rejected values: those are the caller's own input, and echoing untrusted bytes
     * into a response is how a validation message becomes a reflection vector
     * ({@code INV-AUD-02}).
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String detail =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField() + " " + error.getDefaultMessage())
                        .sorted()
                        .collect(java.util.stream.Collectors.joining("; "));
        return validationFailed(detail, request);
    }

    /**
     * A known idempotency key presented with a materially different request ({@code INV-IDEM-03}).
     *
     * <p>409, never a silent second effect and never the first request's response. Replaying a
     * stored outcome here would answer a question nobody asked; re-executing would produce the
     * second effect the whole mechanism exists to prevent.
     *
     * <p>The detail names neither the key nor the difference. The key is the caller's own input and
     * echoing it is how a header becomes a reflection vector; what the two requests disagree about
     * is something only the caller can know and only the caller needs to.
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetailBody> handleIdempotencyConflict(
            IdempotencyConflictException exception, HttpServletRequest request) {

        LOGGER.warn("Idempotency key reused for a different request on {}", request.getRequestURI());
        return render(
                ProblemDetail.of(
                        PlatformErrorCode.CONFLICT,
                        request.getRequestURI(),
                        "This " + IdempotencyKeyHeader.NAME
                                + " was already used for a different request. Use a new key for a"
                                + " new action, or resend the original request unchanged."));
    }

    /**
     * The key is claimed by a command whose outcome is not yet known.
     *
     * <p>Its own code rather than {@code api.Conflict}: this one means <em>wait and retry the same
     * request</em>, and the other means <em>stop</em>. It is deliberately not reported as a
     * failure - assuming a command failed because its outcome is unknown is the assumption
     * {@code INV-LIFE-03} exists to forbid.
     */
    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ProblemDetailBody> handleIdempotencyInProgress(
            IdempotencyInProgressException exception, HttpServletRequest request) {

        LOGGER.warn("Idempotency key still in progress on {}", request.getRequestURI());
        return render(
                ProblemDetail.of(
                        PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
                        request.getRequestURI(),
                        "An identical request is still being processed. Retry it shortly with the"
                                + " same " + IdempotencyKeyHeader.NAME + "."));
    }

    /**
     * A consent-gated capability was invoked with no current basis for the purpose it requires
     * ({@code INV-CNS-01}).
     *
     * <p>The mapping lives here rather than in a controller so every consent-gated surface —
     * this phase's {@code POST /v1/me/kyc} and whatever later phases gate — answers with one
     * code (`P2-TSK-006`, the surface that declared it). The detail is <strong>actionable and
     * cause-blind</strong>: which of the three causes refused — no history, a withdrawal, a
     * grant lapsed by a re-consent-demanding version — is exactly what {@code INV-CNS-01} keeps
     * indistinguishable, and the exception deliberately cannot say. Naming the purpose
     * discloses nothing: purposes are a closed enum shared by everyone, published by
     * {@code GET /v1/me/consents} to every caller.
     */
    @ExceptionHandler(ConsentNotGrantedException.class)
    public ResponseEntity<ProblemDetailBody> handleConsentNotGranted(
            ConsentNotGrantedException exception, HttpServletRequest request) {

        // Warn, not error: a gate refusing is the control working, and the log may name the
        // purpose because it goes to the log and not to a stranger.
        LOGGER.warn(
                "Consent-gated capability refused on {}: no current basis for {}",
                request.getRequestURI(),
                exception.purpose());
        return render(
                ProblemDetail.of(
                        ConsentErrorCode.CONSENT_REQUIRED,
                        request.getRequestURI(),
                        "Grant consent for purpose " + exception.purpose()
                                + " (POST /v1/me/consents, against the current text version)"
                                + " and retry."));
    }

    /**
     * A constraint broken on a parameter of a {@code @Validated} bean.
     *
     * <p>The third of Spring's three validation mechanisms, and the one its base handler does not
     * cover at all. Left alone it reached the catch-all and became {@code 500 api.InternalError} —
     * the worst of the three answers, because the caller can fix it and is told they cannot.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException exception, WebRequest request) {

        String detail =
                exception.getConstraintViolations().stream()
                        // The violation's message is the constraint's, which is ours. Its invalid
                        // value is the caller's, and never goes back (INV-AUD-02).
                        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .sorted()
                        .collect(java.util.stream.Collectors.joining("; "));
        return validationFailed(detail, request);
    }

    /** Field names and constraint messages, both of which are ours. Never the values. */
    private static String describe(HandlerMethodValidationException validation) {
        return validation.getParameterValidationResults().stream()
                .flatMap(
                        result ->
                                result.getResolvableErrors().stream()
                                        .map(
                                                error ->
                                                        result.getMethodParameter().getParameterName()
                                                                + " "
                                                                + error.getDefaultMessage()))
                .sorted()
                .collect(java.util.stream.Collectors.joining("; "));
    }

    /**
     * One rendering for every way a constraint can be broken.
     *
     * <p>Three mechanisms reach it — a request body, a method parameter, and a method parameter
     * of a {@code @Validated} bean. Left to Spring's defaults they produced 422, 400 and 500
     * respectively, which is three answers to one question and no contract at all.
     */
    private ResponseEntity<Object> validationFailed(String detail, WebRequest request) {
        String path = pathOf(request);
        LOGGER.warn("Validation failed on {}: {}", path, detail);
        return ResponseEntity.status(HttpStatus.valueOf(PlatformErrorCode.VALIDATION_FAILED.status()))
                .contentType(PROBLEM_JSON)
                .body(
                        ProblemDetailBody.from(
                                ProblemDetail.of(
                                        PlatformErrorCode.VALIDATION_FAILED,
                                        path,
                                        detail.isBlank() ? null : detail)));
    }

    /**
     * Every framework error passes through here with its status already decided by Spring.
     *
     * <p>The single point at which their shape becomes ours. Overriding this rather than writing
     * a handler per exception type is what makes the coverage complete: a Spring version that
     * adds a new web exception routes it here too.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {

        // A constraint broken on a method parameter, rather than on a request body.
        //
        // Checked here rather than by overriding Spring's own
        // handleHandlerMethodValidationException, which never runs: that exception extends
        // ResponseStatusException, so the base class dispatches it through a more general branch
        // and it arrives here with 400 already chosen. The override compiled, looked right, and
        // was dead code — found only because the isolated run and the full suite disagreed,
        // since which mechanism Spring uses depends on whether any bean in the context is
        // @Validated.
        //
        // This method is the funnel every framework error provably passes through, so a mapping
        // placed here cannot be routed around by Spring's internal dispatch.
        if (exception instanceof HandlerMethodValidationException validation) {
            return validationFailed(describe(validation), request);
        }

        ErrorCode code = codeForStatus(statusCode.value());
        String path = pathOf(request);
        if (statusCode.is5xxServerError()) {
            LOGGER.error("Framework error {} on {}", statusCode.value(), path, exception);
        } else {
            LOGGER.warn(
                    "Framework error {} on {} rendered as {}",
                    statusCode.value(),
                    path,
                    code.code(),
                    exception);
        }
        return ResponseEntity.status(HttpStatus.valueOf(code.status()))
                .contentType(PROBLEM_JSON)
                .body(ProblemDetailBody.from(ProblemDetail.of(code, path)));
    }

    /**
     * The code a framework status is reported as.
     *
     * <p>An unmapped 4xx becomes {@code api.MalformedRequest} rather than an internal error:
     * whatever it was, the caller can act on it and we could not understand the request.
     * Reporting a client's mistake as our failure is the specific defect this mapping exists to
     * prevent, and the unmapped case is logged so the gap is visible rather than approximated
     * silently.
     */
    private static ErrorCode codeForStatus(int status) {
        return switch (status) {
            case 400 -> PlatformErrorCode.MALFORMED_REQUEST;
            case 401 -> PlatformErrorCode.UNAUTHENTICATED;
            case 403 -> PlatformErrorCode.FORBIDDEN;
            case 404 -> PlatformErrorCode.NOT_FOUND;
            case 405 -> PlatformErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> PlatformErrorCode.NOT_ACCEPTABLE;
            case 409 -> PlatformErrorCode.CONFLICT;
            case 413 -> PlatformErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> PlatformErrorCode.VALIDATION_FAILED;
            default -> status >= 400 && status < 500
                            ? PlatformErrorCode.MALFORMED_REQUEST
                            : PlatformErrorCode.INTERNAL_ERROR;
        };
    }

    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getRequestURI()
                : request.getDescription(false);
    }

    private static ResponseEntity<ProblemDetailBody> render(ProblemDetail problem) {
        // Mapped to an explicit wire record rather than serialised directly: the JSON members of
        // a published contract are a decision, not a by-product of the serialiser's defaults.
        // See ProblemDetailBody for the two failures that made the point.
        return ResponseEntity.status(HttpStatus.valueOf(problem.status()))
                .contentType(PROBLEM_JSON)
                .body(ProblemDetailBody.from(problem));
    }
}
