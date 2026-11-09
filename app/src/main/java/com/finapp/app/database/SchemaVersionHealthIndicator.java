package com.finapp.app.database;

import com.finapp.app.database.SchemaVersionGuard.Verdict;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The readiness contributor {@code schemaVersion} (`P15-TSK-005`): {@code OUT_OF_SERVICE} while any schema is older than
 * this build expects, or that has not been established; {@code UP} on the expected schema or a newer one
 * ({@link SchemaVersionGuard}). A member of the readiness group and of no other ({@code application.yaml}): a restart does
 * not apply a migration, so liveness never reads it (ADR-0016).
 *
 * <p>It reports no detail. The endpoint publishes a status and nothing else ({@code show-details: never}), and the guard's
 * log line names the schema.
 */
@RequiredArgsConstructor
public final class SchemaVersionHealthIndicator implements HealthIndicator {

    @NonNull private final SchemaVersionGuard guard;

    @Override
    public Health health() {
        return guard.verdict() == Verdict.READY ? Health.up().build() : Health.outOfService().build();
    }
}
