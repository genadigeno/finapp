package com.finapp.migrator;

/** A configuration or identity this process must not run with: {@link ExitCode#REFUSED}, before anything changes. */
final class RefusedConfiguration extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RefusedConfiguration(String reason) {
        super(reason);
    }
}
