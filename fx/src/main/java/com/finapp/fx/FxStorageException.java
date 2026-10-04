package com.finapp.fx;

import java.io.Serial;

/** A storage failure in the fx schema - loud, never a domain outcome. */
public final class FxStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public FxStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
