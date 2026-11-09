package com.finapp.app.abuse;

import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.PlatformErrorCode;
import java.io.Serial;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A door refused a request before anything else happened (`P15-TSK-022`). Handled by {@code ApiErrorHandler}, once and silently.
 *
 * <p>Two refusals, and neither says anything about what the request contained. Built with no stack trace: this is the path a flood
 * takes, and the refusal must be cheaper than whatever it refuses.
 */
public final class SourceLimitRefused extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final Long retryAfterSeconds;

    private SourceLimitRefused(ErrorCode errorCode, Long retryAfterSeconds) {
        super(errorCode.code(), null, false, false);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** This source has used its window. */
    public static SourceLimitRefused exceeded(long retryAfterSeconds) {
        return new SourceLimitRefused(PlatformErrorCode.TOO_MANY_REQUESTS, retryAfterSeconds);
    }

    /** The count could not be taken, so the door stays closed. */
    public static SourceLimitRefused storeUnavailable() {
        return new SourceLimitRefused(PlatformErrorCode.TEMPORARILY_UNAVAILABLE, null);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Seconds until the window ends, for a {@code Retry-After}; absent when no count was taken. */
    public OptionalLong retryAfterSeconds() {
        return retryAfterSeconds == null ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
    }
}
