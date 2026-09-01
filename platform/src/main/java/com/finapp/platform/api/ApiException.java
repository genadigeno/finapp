package com.finapp.platform.api;

import java.io.Serial;
import java.util.Objects;
import java.util.Optional;

/**
 * A failure that the client should be told about, carrying the code that says which.
 *
 * <p>How code below the API surface signals a contract error without knowing anything about
 * HTTP. The renderer turns it into a {@link ProblemDetail}; nothing else about it reaches the
 * response.
 *
 * <h2>The message is for us; the detail is for them</h2>
 *
 * <p>An exception's message is written for whoever reads the log — it names the account, the
 * amount, the provider, whatever made the failure understandable. That is exactly what must not
 * be sent to a client, so the two are separate fields here and only {@link #clientDetail()} is
 * ever rendered.
 *
 * <p>Separating them is the point. A single-field design forces every author to decide, at each
 * throw site, whether their message is safe to publish — and the answer will eventually be wrong
 * on a busy day, in a class nobody reviews closely, for an error nobody sees until it matters.
 * Here the unsafe default is unreachable: {@code getMessage()} has nowhere to go.
 */
public class ApiException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final String clientDetail;

    /** No detail reaches the client: the code's own title says everything it will see. */
    public ApiException(ErrorCode errorCode, String logMessage) {
        this(errorCode, logMessage, null, null);
    }

    /**
     * @param logMessage for the log. May name whatever makes the failure diagnosable
     * @param clientDetail authored text safe to show a stranger, or {@code null}. Never
     *     {@code logMessage}, and never an exception's message
     */
    public ApiException(ErrorCode errorCode, String logMessage, String clientDetail) {
        this(errorCode, logMessage, clientDetail, null);
    }

    public ApiException(ErrorCode errorCode, String logMessage, String clientDetail, Throwable cause) {
        super(logMessage, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        if (clientDetail != null && clientDetail.isBlank()) {
            throw new IllegalArgumentException("clientDetail must not be blank when supplied");
        }
        this.clientDetail = clientDetail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Authored text for the client, if the throw site supplied any. */
    public Optional<String> clientDetail() {
        return Optional.ofNullable(clientDetail);
    }
}
