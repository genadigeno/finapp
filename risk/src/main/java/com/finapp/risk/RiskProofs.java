package com.finapp.risk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What every risk proof shares (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 13.3, 15; F8; the Phase 11 lesson I2;
 * bnpl's {@code BnplProofs#requireSnapshot}): a proof reads <strong>one read-only {@code REPEATABLE READ}
 * snapshot</strong> - refused any other connection, so no proof can write, and two statements of one census can never
 * see two worlds - and names at most {@link #NAMED} identifiers in any list of differences (the rest counted).
 *
 * <p><strong>Bounded</strong> (the review's I1, I3): a caller arms {@link #bound} on the snapshot first - PostgreSQL's
 * {@code statement_timeout}, {@code SET LOCAL} (gone with the transaction) - so no statement of a proof, a report or a
 * replay page outlives it; a cancelled statement is {@code 57014} ({@link RiskStorageException#QUERY_CANCELED}), the
 * read fails whole and nothing was served. A proof's statements are a fixed set, a velocity report's that set plus at
 * most {@link RiskOperationsReports#PLACED} memoised placements, and a replay page's at most a page's, so the bound on a
 * statement bounds the read - in time; the rows a statement returns (thirty days of counted assessments for the velocity
 * proof) are bounded by the timeout, not by a cap.
 */
public final class RiskProofs {

    /** The longest list of identifiers a proof names; a longer list is counted and truncated. */
    public static final int NAMED = 20;

    private RiskProofs() {}

    /** {@code snapshot} is read-only, in a transaction, at {@code REPEATABLE READ} - or the proof is refused. */
    public static void requireSnapshot(Connection snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            if (!snapshot.isReadOnly() || snapshot.getAutoCommit()
                    || snapshot.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
                throw new IllegalStateException(
                        "a risk proof reads one read-only REPEATABLE READ snapshot - never a connection that could write");
            }
        } catch (SQLException failure) {
            throw new RiskStorageException("risk storage failed inspecting a proof's connection: SQLSTATE "
                    + failure.getSQLState());
        }
    }

    /** A proof snapshot's statement timeout unless configured ({@code finapp.risk.proofs.statement-timeout}). */
    public static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Arms {@code statement_timeout} for the rest of {@code snapshot}'s transaction ({@code set_config(..., true)}, the
     * {@code JdbcEvaluationBudget} idiom) - a setting, never a write, so a read-only snapshot admits it.
     *
     * @throws IllegalArgumentException a timeout under one millisecond - PostgreSQL reads zero as "no timeout"
     */
    public static void bound(Connection snapshot, Duration statementTimeout) {
        requireSnapshot(snapshot);
        Objects.requireNonNull(statementTimeout, "statementTimeout");
        if (statementTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("a proof's statement timeout is at least one millisecond");
        }
        try (PreparedStatement set = snapshot.prepareStatement("SELECT set_config('statement_timeout', ?, true)")) {
            set.setString(1, statementTimeout.toMillis() + "ms");
            try (ResultSet armed = set.executeQuery()) {
                if (!armed.next()) {
                    throw new RiskStorageException("risk storage failed arming a proof's statement timeout: no row");
                }
            }
        } catch (SQLException failure) {
            throw new RiskStorageException("risk storage failed arming a proof's statement timeout: SQLSTATE "
                    + failure.getSQLState());
        }
    }

    /**
     * The database's order of two identifiers - bytewise, unsigned ({@code uuid}'s B-tree order), which for UUIDv7 is
     * the minting order; {@link UUID#compareTo} compares signed halves and is not.
     */
    public static int order(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    /** At most {@link #NAMED} of {@code all}, in their order. */
    public static <T> List<T> named(List<T> all) {
        return List.copyOf(all.subList(0, Math.min(NAMED, all.size())));
    }
}
