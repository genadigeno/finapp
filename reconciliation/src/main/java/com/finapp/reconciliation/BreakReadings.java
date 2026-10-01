package com.finapp.reconciliation;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;

/**
 * The open breaks, counted (`P8-TSK-024`): what {@code finapp.reconciliation.break.open} and
 * {@code .break.age} read - per type and severity, a count and the oldest raise. Counts and
 * instants only, never a value at issue (ADR-0072).
 */
public interface BreakReadings {

    record OpenBreaks(BreakType type, Severity severity, long count, Instant oldestRaisedAt) {}

    List<OpenBreaks> openBreaks(Connection connection);
}
