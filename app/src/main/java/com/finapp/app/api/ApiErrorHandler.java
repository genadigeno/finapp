package com.finapp.app.api;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every API failure as {@code application/problem+json}, and nothing else.
 *
 * <p><strong>The handlers that matter are the ones for errors we did not raise.</strong> Writing
 * an {@code @ExceptionHandler} for your own exception type is the obvious half and the easy one.
 * The half that gets forgotten is everything the framework rejects <em>before</em> any of our
 * code runs — an unknown path, a method the route does not support, a body that will not parse,
 * a media type nobody reads. Those come back in the framework's own shape, so a client sees two
 * different error formats depending on how far into the request it got, and nothing in a normal
 * test suite notices because both look reasonable in isolation.
 *
 * <p>Each is mapped explicitly below, and each has a test.
 *
 * <h2>What never reaches the client</h2>
 *
 * <p>Exception messages, stack traces, class names, SQL, provider payloads. The response body is
 * assembled from the {@link com.finapp.platform.api.ErrorCode} alone, plus authored text where a
 * throw site supplied any — there is no path from a {@code Throwable} to the response. The
 * exception itself goes to the log with the flow's correlation identifier, and the client
 * receives that identifier, which is the one thing that usefully connects a person reporting a
 * problem to the record of it ({@code INV-AUD-02}).
 *
 * <p><strong>The catch-all is deliberately last and deliberately silent.</strong> Any exception
 * with no more specific handler becomes a 500 whose body says nothing about what happened. That
 * is not caution for its own sake: an unhandled exception is by definition one nobody reasoned
 * about, so its message is the least trustworthy string in the system to be publishing.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** RFC 9457's media type. Not {@code application/json}: the shape is a contract of its own. */
    public static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    /** Raised deliberately by our own code, carrying the code that says what went wrong. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetailBody> handleApiException(
            ApiException exception, HttpServletRequest request) {
        // Logged at warn rather than error: a client error is the API working. The message may
        // name whatever makes it diagnosable, because it is going to the log and not the wire.
        log.warn("API error {}: {}", exception.errorCode().code(), exception.getMessage(), exception);
        return render(
                ProblemDetail.of(
                        exception.errorCode(),
                        request.getRequestURI(),
                        exception.clientDetail().orElse(null)));
    }

    /** No route matches — raised by the framework before anything of ours runs. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetailBody> handleNotFound(
            NoResourceFoundException exception, HttpServletRequest request) {
        return render(ProblemDetail.of(PlatformErrorCode.NOT_FOUND, request.getRequestURI()));
    }

    /** The path exists; the method does not. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetailBody> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return render(
                ProblemDetail.of(PlatformErrorCode.METHOD_NOT_ALLOWED, request.getRequestURI()));
    }

    /** The body's media type is not one this endpoint reads. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetailBody> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return render(
                ProblemDetail.of(PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE, request.getRequestURI()));
    }

    /**
     * The body could not be parsed.
     *
     * <p>No detail, deliberately. Jackson's parse errors are precise and quote the input — which
     * is helpful in a log and is an echo of attacker-controlled bytes in a response.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetailBody> handleMalformedBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        log.warn("Malformed request body on {}", request.getRequestURI(), exception);
        return render(ProblemDetail.of(PlatformErrorCode.MALFORMED_REQUEST, request.getRequestURI()));
    }

    /**
     * Anything else at all.
     *
     * <p>Logged at error with the exception, because this is the one nobody expected; rendered
     * with a body that says nothing, because this is the one whose message we understand least.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetailBody> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        log.error("Unhandled error on {}", request.getRequestURI(), exception);
        return render(ProblemDetail.of(PlatformErrorCode.INTERNAL_ERROR, request.getRequestURI()));
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
