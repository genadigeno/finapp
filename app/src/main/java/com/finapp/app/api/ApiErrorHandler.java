package com.finapp.app.api;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
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

    private static final Logger errors = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** RFC 9457's media type. Not {@code application/json}: the shape is a contract of its own. */
    public static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    /** Raised deliberately by our own code, carrying the code that says what went wrong. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetailBody> handleApiException(
            ApiException exception, HttpServletRequest request) {
        // Warn, not error: a client error is the API working. The message may name whatever makes
        // it diagnosable, because it goes to the log and not to the wire.
        errors.warn(
                "API error {}: {}", exception.errorCode().code(), exception.getMessage(), exception);
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
    public ResponseEntity<ProblemDetailBody> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        errors.error("Unhandled error on {}", request.getRequestURI(), exception);
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
                errors.warn("Request body to {} exceeded {} bytes", path, tooLarge.maxBytes());
                return ResponseEntity.status(HttpStatus.valueOf(PlatformErrorCode.PAYLOAD_TOO_LARGE.status()))
                        .contentType(PROBLEM_JSON)
                        .body(
                                ProblemDetailBody.from(
                                        ProblemDetail.of(
                                                PlatformErrorCode.PAYLOAD_TOO_LARGE,
                                                path,
                                                "The maximum request body is "
                                                        + tooLarge.maxBytes()
                                                        + " bytes.")));
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
        String path = pathOf(request);
        errors.warn("Validation failed on {}: {}", path, detail);
        return ResponseEntity.status(
                        HttpStatus.valueOf(PlatformErrorCode.VALIDATION_FAILED.status()))
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

        ErrorCode code = codeForStatus(statusCode.value());
        String path = pathOf(request);
        if (statusCode.is5xxServerError()) {
            errors.error("Framework error {} on {}", statusCode.value(), path, exception);
        } else {
            errors.warn(
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
            default ->
                    status >= 400 && status < 500
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
