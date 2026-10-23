package com.finapp.risk;

import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The savepoint and the statement timeout T-eval's steps run behind (`P13-TSK-004`; {@code PHASE_13_PLAN.md} section
 * 7.6; ADR-0108 decision 5; {@code INV-RSK-04}) - a port so the hermetic suites can prove the order of opening, keeping
 * and undoing without a database; {@link JdbcEvaluationBudget} is the composition's.
 *
 * <p>The contract, on the caller's connection inside the caller's transaction: {@link #open} sets a savepoint and,
 * when a timeout is given, arms {@code SET LOCAL statement_timeout} after it; {@link #keep} restores the timeout the
 * transaction had and releases the savepoint - the step's rows and locks become the transaction's; {@link #undo} rolls
 * back to the savepoint - the step's rows and locks are gone, its timeout with them - and restores the timeout. A
 * timed-out or failed statement aborts only the savepoint's subtransaction, so the caller's transaction survives and
 * commits the recorded fail-safe: "never an aborted caller".
 */
public interface EvaluationBudget {

    /** The budget every read of T-eval and T-advise runs under: 250 ms per statement (ADR-0108 decision 5). */
    Duration READ_TIMEOUT = Duration.ofMillis(250);

    /** Opens a savepoint on {@code unitOfWork}, the statement timeout armed when {@code timeout} is present. */
    Mark open(Connection unitOfWork, Optional<Duration> timeout);

    /** The step stands: the timeout restored, the savepoint released. */
    void keep(Connection unitOfWork, Mark mark);

    /** The step is undone: rolled back to the savepoint, the timeout restored. The caller's transaction is intact. */
    void undo(Connection unitOfWork, Mark mark);

    /** One open savepoint and, when a timeout was armed, the transaction's own value to restore. */
    record Mark(Object savepoint, Optional<String> restoredTimeout) {
        public Mark {
            Objects.requireNonNull(savepoint, "savepoint");
            Objects.requireNonNull(restoredTimeout, "restoredTimeout");
        }
    }
}
