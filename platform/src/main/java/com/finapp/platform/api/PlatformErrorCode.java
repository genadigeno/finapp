package com.finapp.platform.api;

/**
 * The failures the API surface itself can produce, before any business module is reached.
 *
 * <p>Deliberately small and deliberately generic. These are the errors that belong to the
 * <em>protocol</em> — a route that does not exist, a body that will not parse, a media type
 * nobody accepts — and every one of them is raised by the framework before domain code runs.
 * Business failures are the modules' own codes, not entries here.
 *
 * <p><strong>Why they are enumerated at all.</strong> Spring produces most of these on its own,
 * with its own body shape. A client would then see two different error formats depending on
 * whether the request reached our code — which is the failure `.claude/rules/api-design.md` is
 * warning about when it asks for explicit error contracts, and it is invisible in testing
 * because the happy path and the handled path both look fine.
 */
public enum PlatformErrorCode implements ErrorCode {

    /** The request body could not be parsed, or was structurally unusable. */
    MALFORMED_REQUEST("api.MalformedRequest", 400, "The request could not be understood."),

    /**
     * The request was well-formed but failed validation.
     *
     * <p>422 rather than 400: the syntax was fine and the content was not, and the distinction is
     * what tells a client whether to fix its serialiser or its data.
     */
    VALIDATION_FAILED("api.ValidationFailed", 422, "The request was not valid."),

    /**
     * An endpoint that requires {@code Idempotency-Key} was called without one.
     *
     * <p>Its own code rather than {@link #VALIDATION_FAILED}, because the remediation is
     * different in kind and a client can automate it: generate a key and retry. "Your request was
     * not valid" tells a client library to stop; "you owe me an idempotency key" tells it exactly
     * what to add. That is what a machine-readable code is for.
     *
     * <p>422 rather than 400: the request was syntactically fine. It is the same distinction
     * {@link #VALIDATION_FAILED} draws, applied to a missing header rather than a missing field.
     *
     * <p>A key that is <em>present and unusable</em> is {@link #VALIDATION_FAILED}, not this: the
     * client supplied one and must fix it, which is the ordinary validation shape.
     */
    IDEMPOTENCY_KEY_REQUIRED(
            "api.IdempotencyKeyRequired", 422, "This operation requires an idempotency key."),

    /** No route matches. */
    NOT_FOUND("api.NotFound", 404, "The requested resource does not exist."),

    /** The route exists; the method does not. */
    METHOD_NOT_ALLOWED("api.MethodNotAllowed", 405, "That method is not allowed here."),

    /** The body's media type is not one this endpoint reads. */
    UNSUPPORTED_MEDIA_TYPE("api.UnsupportedMediaType", 415, "That media type is not supported."),

    /** No representation this endpoint can produce is acceptable to the caller. */
    NOT_ACCEPTABLE("api.NotAcceptable", 406, "No acceptable representation is available."),

    /** The request body exceeded the accepted size. */
    PAYLOAD_TOO_LARGE("api.PayloadTooLarge", 413, "The request was too large."),

    /**
     * The request conflicts with the current state of the resource.
     *
     * <p>What an idempotency-key reuse with a different request becomes at the boundary
     * ({@code INV-IDEM-03}), among other things.
     */
    CONFLICT("api.Conflict", 409, "The request conflicts with the current state."),

    /** The caller is not authenticated. */
    UNAUTHENTICATED("api.Unauthenticated", 401, "Authentication is required."),

    /** The caller is authenticated and not permitted. */
    FORBIDDEN("api.Forbidden", 403, "You do not have permission to do that."),

    /**
     * Something failed that the client cannot do anything about.
     *
     * <p>The catch-all, and the one that matters most for {@code INV-AUD-02}: its title says
     * nothing, because everything a client could usefully learn here is something we must not
     * tell it. The detail goes to the log with the correlation identifier, and the client gets
     * that identifier to quote.
     */
    INTERNAL_ERROR("api.InternalError", 500, "Something went wrong on our side.");

    private final String code;
    private final int status;
    private final String title;

    PlatformErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
