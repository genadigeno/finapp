package com.finapp.platform.api;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Objects;
import java.util.Optional;

/**
 * An API failure, in the shape RFC 9457 defines for {@code application/problem+json}.
 *
 * <p>Framework-free on purpose. This is a <em>published contract</em> — clients are written
 * against it and it outlives any web stack we happen to be running — so it is a value type in
 * the platform, and rendering it to JSON is {@code app}'s job
 * ({@code MODULE_ARCHITECTURE.md} §M10). Building it on the framework's own problem-detail class
 * would make an external contract a function of an internal dependency.
 *
 * <h2>What may never be in here</h2>
 *
 * <p>An exception message, a stack trace, a SQL fragment, a provider's raw response, a file path,
 * a class name. Those are the things a client cannot act on and an attacker can: they describe
 * our internals, and {@code INV-AUD-02} forbids them in API responses as firmly as in logs.
 *
 * <p>The design enforces that structurally rather than by discipline. {@link #title} comes from
 * the {@link ErrorCode} and is fixed at compile time. {@link #detail} is optional and must be
 * <strong>authored text</strong> — there is no constructor that takes a {@code Throwable},
 * because the moment one exists somebody will pass {@code e.getMessage()} into it on a tired
 * afternoon and nothing will fail.
 *
 * @param type a URI identifying the problem kind. Dereferenceable documentation later; stable
 *     now, because clients may match on it
 * @param title the human summary, from the error code
 * @param status the HTTP status, from the error code
 * @param code the machine-readable identifier a client switches on
 * @param detail optional authored specifics — "the field 'amount' is required". Never derived
 *     from an exception
 * @param instance optional identifier of this occurrence — the request path
 * @param correlationId the flow this failure belongs to, so a client can quote it and support
 *     can find it. Absent when nothing established one
 */
public record ProblemDetail(
        String type,
        String title,
        int status,
        String code,
        Optional<String> detail,
        Optional<String> instance,
        Optional<CorrelationId> correlationId) {

    /** The namespace problem types live under. Not dereferenceable yet; stable regardless. */
    public static final String TYPE_PREFIX = "https://finapp.example/problems/";

    public ProblemDetail {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(detail, "detail must not be null; use Optional.empty()");
        Objects.requireNonNull(instance, "instance must not be null; use Optional.empty()");
        Objects.requireNonNull(correlationId, "correlationId must not be null; use Optional.empty()");
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status but was " + status);
        }
        detail.ifPresent(
                present -> {
                    if (present.isBlank()) {
                        throw new IllegalArgumentException("detail must not be blank when present");
                    }
                });
    }

    /**
     * Builds a problem detail for {@code errorCode}, taking the correlation from the current
     * scope if there is one.
     *
     * <p>Note what this method cannot be given: an exception. Everything it renders comes from
     * the code itself or from the caller's own authored text.
     */
    public static ProblemDetail of(ErrorCode errorCode, String instance) {
        return of(errorCode, instance, null);
    }

    /**
     * @param detail authored specifics, or {@code null} for none. <strong>Never</strong> an
     *     exception's message: the parameter exists for text a developer wrote knowing it would
     *     be shown to a stranger
     */
    public static ProblemDetail of(ErrorCode errorCode, String instance, String detail) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return new ProblemDetail(
                TYPE_PREFIX + errorCode.code(),
                errorCode.title(),
                errorCode.status(),
                errorCode.code(),
                Optional.ofNullable(detail),
                Optional.ofNullable(instance),
                CorrelationContext.current().map(Correlation::correlationId));
    }
}
