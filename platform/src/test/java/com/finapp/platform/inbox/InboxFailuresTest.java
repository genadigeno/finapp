package com.finapp.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.kafka.BrokerAccessRefusedException;
import java.io.EOFException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What may count against a record, and what never may (`P15-TSK-028`). */
@DisplayName("a failure of the infrastructure or of the build never counts against a record (P15-TSK-028)")
class InboxFailuresTest {

    @ParameterizedTest(name = "SQLState {0}")
    @ValueSource(strings = {"08006", "08001", "40001", "40P01", "53300", "53200", "55P03", "57P01", "57014", "58030", "XX000"})
    @DisplayName("a connection loss, a rollback, an exhausted resource, a lock, an operator's act or an internal error is transient")
    void aDatabaseBlipIsTransient(String sqlState) {
        assertThat(InboxFailures.isTransient(new SQLException("x", sqlState))).isTrue();
    }

    @Test
    @DisplayName("the cause chain is read, because a store wraps what really happened")
    void theCauseChainIsRead() {
        RuntimeException wrapped =
                new InboxStorageException("Could not record", new RuntimeException(new SQLException("x", "08006")));
        assertThat(InboxFailures.isTransient(wrapped)).isTrue();
    }

    @Test
    @DisplayName("a pool that ran dry, a timeout, a dead socket and a refused broker credential are transient")
    void theNamedKindsAreTransient() {
        assertThat(InboxFailures.isTransient(new SQLTransientConnectionException("pool"))).isTrue();
        assertThat(InboxFailures.isTransient(new RuntimeException(new TimeoutException()))).isTrue();
        assertThat(InboxFailures.isTransient(new RuntimeException(new EOFException()))).isTrue();
        assertThat(InboxFailures.isTransient(new RuntimeException(new InterruptedException()))).isTrue();
        assertThat(InboxFailures.isTransient(new BrokerAccessRefusedException("SaslAuthenticationException"))).isTrue();
    }

    @Test
    @DisplayName("a build too old to read a version is not the record's fault")
    void anUnreadableVersionIsTransient() {
        assertThat(InboxFailures.isTransient(new EventNotYetReadable("newer than this build"))).isTrue();
    }

    @ParameterizedTest(name = "SQLState {0}")
    @ValueSource(strings = {"23505", "23514", "22P02", "42703", "25P02"})
    @DisplayName("a constraint the record broke, data it carried or a statement that can only fail again counts against it")
    void aFailureOfItsOwnCounts(String sqlState) {
        assertThat(InboxFailures.isTransient(new SQLException("x", sqlState))).isFalse();
    }

    @Test
    @DisplayName("a defect the record triggers counts, and an exception that is its own cause does not hang the walk")
    void anOrdinaryExceptionCounts() {
        assertThat(InboxFailures.isTransient(new IllegalArgumentException("unreadable payload"))).isFalse();
        RuntimeException cycle = new RuntimeException("a");
        cycle.initCause(new RuntimeException("b", cycle));
        assertThat(InboxFailures.isTransient(cycle)).isFalse();
    }

    @Test
    @DisplayName("an SQLException with no state, of no transient kind, counts")
    void noSqlStateCounts() {
        assertThat(InboxFailures.isTransient(new SQLException("no state"))).isFalse();
        assertThat(InboxFailures.isTransient(new SQLException("short", "0"))).isFalse();
    }
}
