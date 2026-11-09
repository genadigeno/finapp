package com.finapp.platform.abuse;

/**
 * What the counter decided for one request (`P15-TSK-022`).
 *
 * <p>Both fields come from the one statement that counted, so what is told to the client is what the database held.
 *
 * @param admitted whether this request is within its window's limit
 * @param retryAfterSeconds whole seconds until the window ends on the database's clock, at least 1
 */
public record Admission(boolean admitted, long retryAfterSeconds) {

    public Admission {
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("retryAfterSeconds must be at least 1 but was " + retryAfterSeconds);
        }
    }
}
