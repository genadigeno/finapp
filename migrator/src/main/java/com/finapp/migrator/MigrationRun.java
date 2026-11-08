package com.finapp.migrator;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * Applies the schemas in order, one after another, each under a bounded retry.
 *
 * <p>No atomicity is claimed across schemas, because none exists: each migration is its own transaction. A run that stops
 * half way leaves every applied migration applied and the rest pending, and the next run starts at the first pending one.
 * That is the whole of its restart logic.
 */
@RequiredArgsConstructor
final class MigrationRun {

    /** One schema's migrations. Returns how many this run applied; throws what Flyway threw. */
    interface SchemaMigration {
        int migrate(String schema);
    }

    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final SchemaMigration migration;
    private final Pause pause;
    private final int lockAttempts;
    private final Duration lockBackoff;
    private final PrintStream out;

    ExitCode apply(List<String> schemas) {
        int total = 0;
        for (String schema : schemas) {
            int attempt = 0;
            while (true) {
                attempt++;
                try {
                    int applied = migration.migrate(schema);
                    total += applied;
                    out.println("finapp-migrator schema=" + schema + " applied=" + applied + " attempts=" + attempt);
                    break;
                } catch (RuntimeException failure) {
                    FailureClass kind = FailureClass.of(failure);
                    if (kind != FailureClass.LOCK_CONTENTION || attempt >= lockAttempts) {
                        out.println("finapp-migrator schema=" + schema + " attempts=" + attempt + " failure=" + kind);
                        failure.printStackTrace(out);
                        out.println("finapp-migrator result=" + kind.exitCode() + " schema=" + schema);
                        return kind.exitCode();
                    }
                    out.println("finapp-migrator schema=" + schema + " attempts=" + attempt
                            + " waiting: a lock was not granted within lock_timeout");
                    if (!paused()) {
                        out.println("finapp-migrator result=" + ExitCode.TRANSIENT + " schema=" + schema + " interrupted");
                        return ExitCode.TRANSIENT;
                    }
                }
            }
        }
        out.println("finapp-migrator result=" + ExitCode.APPLIED + " schemas=" + schemas.size() + " applied=" + total);
        return ExitCode.APPLIED;
    }

    private boolean paused() {
        try {
            pause.sleep(lockBackoff);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
