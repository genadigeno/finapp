package com.finapp.risk;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What every risk proof shares (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 13.3, 15; F8; the Phase 11 lesson I2;
 * bnpl's {@code BnplProofs#requireSnapshot}): a proof reads <strong>one read-only {@code REPEATABLE READ}
 * snapshot</strong> - refused any other connection, so no proof can write, and two statements of one census can never
 * see two worlds - and names at most {@link #NAMED} identifiers in any list of differences (the rest counted).
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
