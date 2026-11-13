package com.finapp.app.shedding;

import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.PlatformErrorCode;
import java.io.Serial;

/**
 * A request shed before its handler was entered (`P16-TSK-016`, ADR-0137): {@code 503 api.Overloaded} with {@code Retry-After}.
 * Handled by {@code ApiErrorHandler}, once and silently.
 *
 * <p>Built with no stack trace and no message beyond the code: this is the path an overload takes, and the refusal must cost less than
 * what it refuses. It carries nothing about why - no queue length, no permit count - so the answer is the same for every caller.
 */
public final class RequestShed extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    RequestShed(long retryAfterSeconds) {
        super(PlatformErrorCode.OVERLOADED.code(), null, false, false);
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("a shed answer asks for at least one second");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode errorCode() {
        return PlatformErrorCode.OVERLOADED;
    }

    /** Whole seconds for {@code Retry-After}, at least 1. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
