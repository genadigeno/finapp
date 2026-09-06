package com.finapp.platform.persistence;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Turns a driver failure into something safe to put in a log line.
 *
 * <h2>Why this exists: PostgreSQL puts the whole row in the error</h2>
 *
 * <p>A {@code CHECK} constraint violation carries a {@code DETAIL} line reading <em>"Failing row
 * contains (…)"</em> — <strong>every column of the row that was refused</strong>. The driver puts it
 * in {@link SQLException#getMessage()}, so an exception that carries a {@code SQLException} as its
 * cause carries the row, and anything that logs that exception logs the row.
 *
 * <p>Found by the {@code P1-TSK-008} completion gate, by running the failing insert rather than
 * reasoning about it:
 *
 * <pre>
 * ERROR:  new row for relation "credential" violates check constraint
 *         "credential_derivation_is_encoded"
 * DETAIL:  Failing row contains (…, ARGON2ID, 1024, 1, 1, hunter2-my-actual-secret-password, …)
 * </pre>
 *
 * <p><strong>The constraint that exists to stop a plaintext being stored causes the plaintext to be
 * logged when it fires.</strong> {@code INV-AUD-02} violated by the mechanism protecting
 * {@code INV-IDN-01}, and it is not confined to credentials: the same {@code DETAIL} carries a
 * person's name out of {@code party.party} and a login identifier out of {@code identity.identity},
 * both of which have {@code CHECK} constraints of their own and both of which are classified above
 * {@code INTERNAL}.
 *
 * <h2>What is kept, and what is deliberately thrown away</h2>
 *
 * <p>Kept: the operation, whatever identifier the caller names, and the <strong>SQLState</strong> —
 * five characters that say <em>which class of failure</em> ({@code 23514} a check, {@code 23505} a
 * unique violation, {@code 23502} a null, {@code 08…} a connection). That is nearly all of the
 * diagnostic value.
 *
 * <p>Thrown away: the {@code SQLException} itself, cause chain and stack trace included. That is a
 * real cost - an operator loses the driver's own frames - and it is accepted for tables whose rows
 * are classified above {@code INTERNAL}, because the alternative is a log aggregator holding
 * passwords and names with months of retention.
 *
 * <p><strong>Where it is not needed.</strong> A table whose every column is {@code INTERNAL} loses
 * nothing by attaching the cause, and those call sites are left alone: this is not a rule about all
 * SQL, it is a rule about rows that must not be printed.
 */
public final class DatabaseFailure {

    private DatabaseFailure() {
        throw new AssertionError("not instantiable");
    }

    /**
     * A message naming the operation and the failure class, and nothing from the row.
     *
     * @param operation what was being attempted, in the caller's own words. May name an identifier -
     *     an identifier is not row content and is what makes the failure findable
     * @return a message safe to log, and safe to hand to an exception that will be logged
     */
    public static String describe(String operation, SQLException failure) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        // getSQLState() only. Never getMessage(), never getServerErrorMessage(), never the cause -
        // each of those is a path back to the row.
        String state = failure.getSQLState();
        return operation + " (SQLState " + (state == null ? "unknown" : state) + ")";
    }
}
