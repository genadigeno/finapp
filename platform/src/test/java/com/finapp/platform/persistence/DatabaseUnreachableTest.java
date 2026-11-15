package com.finapp.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a lost database is, and what is not (`P16-REM-002`; {@code THREAT_MODEL.md} section 6 item 61 (i)): each cause the error
 * contract answers {@code 503 api.TemporarilyUnavailable}, an exhausted pool kept apart as the overload it is, and a statement's own
 * failure on a live connection left to its own answer - read down the cause chain, and from a store's message, where the SQLState
 * is all a store keeps.
 */
@DisplayName("a lost database told apart from an overload and from a statement's own failure (P16-REM-002)")
class DatabaseUnreachableTest {

    /** The pool's timeout as Hikari raises it when it could not make a connection: the last attempt's state and cause. */
    private static SQLTransientConnectionException poolCouldNotConnect() {
        SQLException refused = new SQLException("Connection to db:5432 refused.", "08001");
        return new SQLTransientConnectionException("finapp - Connection is not available, request timed out after 3000ms.", "08001", refused);
    }

    /** The pool's timeout when every connection was made and is busy: no state, no cause. */
    private static SQLTransientConnectionException poolExhausted() {
        return new SQLTransientConnectionException("finapp - Connection is not available, request timed out after 3000ms.");
    }

    @Test
    @DisplayName("a connection that could not be made or was lost is the database lost: class 08, and the driver's connection exception")
    void aConnectionExceptionIsLost() {
        assertThat(DatabaseUnreachable.is(new SQLException("An I/O error occurred while sending to the backend.", "08006"))).isTrue();
        assertThat(DatabaseUnreachable.is(new SQLException("Connection refused.", "08001"))).isTrue();
        assertThat(DatabaseUnreachable.is(new SQLException("This connection has been closed.", "08003"))).isTrue();
        assertThat(DatabaseUnreachable.is(new SQLNonTransientConnectionException("This connection has been closed."))).isTrue();
    }

    @Test
    @DisplayName("a server going away or not yet back - a failover's and a shutdown's 57P01, 57P02, 57P03 - is the database lost")
    void aServerGoneIsLost() {
        assertThat(DatabaseUnreachable.is(new SQLException("terminating connection due to administrator command", "57P01"))).isTrue();
        assertThat(DatabaseUnreachable.is(new SQLException("terminating connection due to crash of another server process", "57P02")))
                .isTrue();
        assertThat(DatabaseUnreachable.is(new SQLException("the database system is starting up", "57P03"))).isTrue();
    }

    @Test
    @DisplayName("the pool's wait ending because no connection could be made is the database lost; an exhausted pool is not")
    void thePoolSaysWhichByItsCause() {
        assertThat(DatabaseUnreachable.is(poolCouldNotConnect())).as("no connection could be made").isTrue();
        assertThat(DatabaseUnreachable.is(poolExhausted())).as("every connection made and busy: an overload").isFalse();
        assertThat(DatabaseUnreachable.is(new SQLTransientConnectionException("timed out", null,
                new SQLException("canceling statement due to statement timeout", "57014")))).as("a cause that is not a lost database").isFalse();
    }

    @Test
    @DisplayName("a statement's own failure on a live connection keeps its own answer: a timeout, a constraint, a serialisation refusal")
    void aStatementsOwnFailureIsNot() {
        assertThat(DatabaseUnreachable.is(new SQLException("canceling statement due to statement timeout", "57014"))).isFalse();
        assertThat(DatabaseUnreachable.is(new SQLException("duplicate key value violates unique constraint", "23505"))).isFalse();
        assertThat(DatabaseUnreachable.is(new SQLException("could not serialize access", "40001"))).isFalse();
        assertThat(DatabaseUnreachable.is(new IllegalStateException("a defect"))).isFalse();
    }

    @Test
    @DisplayName("read down the cause chain, through whatever wraps it: a transaction that could not begin, a store's exception")
    void theCauseChainIsRead() {
        assertThat(DatabaseUnreachable.is(new IllegalStateException("Could not open JDBC Connection for transaction", poolCouldNotConnect())))
                .isTrue();
        assertThat(DatabaseUnreachable.is(new IllegalStateException("Could not open JDBC Connection for transaction", poolExhausted())))
                .as("wrapped, an exhausted pool is still an overload").isFalse();
        assertThat(DatabaseUnreachable.is(new RuntimeException("outer", new RuntimeException("inner",
                new SQLException("terminating connection due to administrator command", "57P01"))))).isTrue();
    }

    @Test
    @DisplayName("a store keeps only the SQLState, in text, in either spelling: read from the message, and an unknown state is nothing")
    void aStoresMessageIsRead() {
        assertThat(DatabaseUnreachable.is(new IllegalStateException(DatabaseFailure.describe("posting the entry",
                new SQLException("An I/O error occurred while sending to the backend.", "08006"))))).as("DatabaseFailure#describe").isTrue();
        assertThat(DatabaseUnreachable.is(new IllegalStateException("could not insert the line: SQLSTATE 57P01"))).as("a module store's").isTrue();
        assertThat(DatabaseUnreachable.is(new IllegalStateException(DatabaseFailure.describe("posting the entry", poolExhausted()))))
                .as("the busy pool's timeout, described: no state").isFalse();
        assertThat(DatabaseUnreachable.is(new IllegalStateException("could not insert the line: SQLSTATE 23505"))).isFalse();
    }
}
