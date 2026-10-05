package com.finapp.crossborder;

import java.io.Serial;

/** A storage failure in the crossborder schema - loud, never a domain outcome. */
public final class CrossborderStorageException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public CrossborderStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
